"""Control and bounded PCM transport to one Android bridge.

The hub speaks the device protocol over any authenticated message link. Two
links exist: an iroh stream (``p2p.py``) and the TLS WebSocket listener below.
"""

from __future__ import annotations

import asyncio
import http
import json
import logging
import secrets
import ssl
import statistics
import struct
import time
import uuid
from collections.abc import Awaitable, Callable
from typing import Any, Protocol

from websockets.asyncio.server import Server, ServerConnection, serve
from websockets.exceptions import ConnectionClosed
from websockets.http11 import Request, Response

log = logging.getLogger(__name__)
DEVICE_PATH = "/device"
AUDIO_HEADER = struct.Struct("!4sIQ")
AUDIO_MAGIC = b"PX01"
PROBE_MAGIC = b"PXLT"
FRAME_PCM_BYTES = 1920
MAX_PCM_BYTES = 19200
MAX_CONTROL_CHARS = 65536
MAX_MESSAGE_BYTES = 2 * MAX_CONTROL_CHARS


class DeviceError(RuntimeError):
    pass


class LinkClosed(Exception):
    """The phone connection ended."""


def _spread(values: list[float]) -> dict[str, int] | None:
    if not values:
        return None
    ordered = sorted(values)
    return {
        "median": round(statistics.median(ordered)),
        "p95": round(ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))]),
        "max": round(ordered[-1]),
    }


class DeviceLink(Protocol):
    """One authenticated, ordered, message-framed connection to the phone."""

    peer: str

    def route(self) -> str | None:
        """How packets reach the phone right now, when the link can tell."""
        ...

    def paths(self) -> list[str]:
        """Every open path with its transport counters, for diagnostics."""
        ...

    async def recv(self) -> str | bytes: ...

    async def send(self, message: str | bytes) -> None: ...

    async def close(self, code: int, reason: str) -> None: ...


class DeviceHub:
    def __init__(self) -> None:
        self._link: DeviceLink | None = None
        self._ready = asyncio.Event()
        self._send_lock = asyncio.Lock()
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._outgoing: asyncio.Queue[tuple[str, int, bytes]] = asyncio.Queue(maxsize=10)
        self._events: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=64)
        self._progress = asyncio.Condition()
        self._call_id: str | None = None
        self._epoch = 0
        self._submitted_samples = 0
        self._dropped_samples = 0
        self._played_samples = 0
        self._queued_samples = 0
        self._frames_out = 0
        self._frames_in = 0
        self._send_wait = 0.0
        self._ping_wait = 0.0
        self._probe: dict[int, float] | None = None
        self._probe_ms: list[float] = []
        self._probe_id = 0
        self.info: dict[str, Any] = {}
        self.on_event: Callable[[dict[str, Any]], Awaitable[None]] | None = None
        self.on_audio: Callable[[str, bytes], Awaitable[None]] | None = None

    @property
    def connected(self) -> bool:
        return self._link is not None and self._ready.is_set()

    async def wait_connected(self, timeout: float) -> bool:
        """Give a phone that is reconnecting, e.g. after a daemon start, time to arrive."""
        try:
            async with asyncio.timeout(timeout):
                await self._ready.wait()
        except TimeoutError:
            return False
        return self.connected

    @property
    def submitted_samples(self) -> int:
        """Samples of the current stream handed to the link and not dropped."""
        return self._submitted_samples - self._dropped_samples

    @property
    def played_samples(self) -> int:
        return self._played_samples

    def status(self) -> dict[str, Any]:
        link = self._link
        return {
            "connected": self.connected,
            "device": self.info.get("device"),
            "peer": link.peer if link is not None and self.connected else None,
            "route": link.route() if link is not None and self.connected else None,
            "capabilities": self.info.get("capabilities", {}),
            "active_call": self.info.get("active_call"),
        }

    async def attach(self, link: DeviceLink) -> None:
        """Serve one authenticated phone link until it ends."""
        if self._link is not None:
            await link.close(1013, "A device is already connected")
            return
        self._link = link
        self._ready.clear()
        workers: list[asyncio.Task[Any]] = []
        try:
            async with asyncio.timeout(10):
                hello = json.loads(await link.recv())
            if not isinstance(hello, dict) or hello.get("type") != "hello" or hello.get("version") != 1:
                raise DeviceError("Incompatible device handshake")
            self.info = {key: hello.get(key) for key in ("device", "boot_id", "capabilities", "active_call")}
            self._ready.set()
            log.info("Phone %s connected from %s", self.info.get("device"), link.peer)
            workers = [
                asyncio.create_task(self._dispatch_events(), name="device-events"),
                asyncio.create_task(self._write_audio(link), name="device-audio"),
                asyncio.create_task(self._heartbeat(link), name="device-heartbeat"),
            ]
            self._events.put_nowait(hello)
            while True:
                message = await link.recv()
                if isinstance(message, bytes):
                    await self._receive_audio(message)
                else:
                    if len(message) > MAX_CONTROL_CHARS:
                        raise DeviceError("Oversized device control message")
                    event = json.loads(message)
                    if not isinstance(event, dict):
                        raise DeviceError("Invalid device control message")
                    await self._receive_event(event)
        except (LinkClosed, asyncio.CancelledError):
            pass
        except Exception:
            log.exception("Device link failed")
            await link.close(1002, "Device protocol error")
        finally:
            if self._link is link:
                log.info("Phone disconnected")
                self._link = None
                self._ready.clear()
                self.info = {}
                self.stop_stream()
                for future in tuple(self._pending.values()):
                    if not future.done():
                        future.set_exception(DeviceError("device_disconnected"))
                self._pending.clear()
                for worker in workers:
                    worker.cancel()
                await asyncio.gather(*workers, return_exceptions=True)
                self._clear(self._events)
                async with self._progress:
                    self._progress.notify_all()
                if self.on_event is not None:
                    await self.on_event({"type": "device_disconnected"})

    async def _receive_event(self, event: dict[str, Any]) -> None:
        kind = event.get("type")
        if kind == "response":
            future = self._pending.get(str(event.get("id")))
            if future is not None and not future.done():
                if event.get("ok") is True:
                    data = event.get("data", {})
                    future.set_result(data if isinstance(data, dict) else {})
                else:
                    future.set_exception(DeviceError(str(event.get("error", "device_command_failed"))[:1000]))
            return
        if kind == "playback":
            if event.get("call_id") == self._call_id and event.get("epoch") == self._epoch:
                played = event.get("played_samples")
                queued = event.get("queued_samples", 0)
                if not isinstance(played, int) or played < 0 or not isinstance(queued, int) or queued < 0:
                    raise DeviceError("Invalid playback counters")
                self._played_samples = max(self._played_samples, played)
                self._queued_samples = queued
                async with self._progress:
                    self._progress.notify_all()
            return
        if kind == "call_state":
            self.info["active_call"] = None if event.get("state") == "ended" else {
                "call_id": event.get("call_id"), "state": event.get("state")
            }
        try:
            self._events.put_nowait(event)
        except asyncio.QueueFull as exc:
            raise DeviceError("Device control event overflow") from exc

    async def _receive_audio(self, frame: bytes) -> None:
        size = len(frame) - AUDIO_HEADER.size
        if size <= 0 or size > MAX_PCM_BYTES or size % 2:
            raise DeviceError("Invalid PCM frame size")
        magic, epoch, sequence = AUDIO_HEADER.unpack_from(frame)
        if magic == PROBE_MAGIC:
            # The echo of an earlier probe carries that probe's id and must not count for this one.
            if self._probe is not None and epoch == self._probe_id:
                started = self._probe.pop(sequence, None)
                if started is not None:
                    self._probe_ms.append((time.monotonic() - started) * 1000)
            return
        if magic != AUDIO_MAGIC:
            raise DeviceError("Invalid PCM frame version")
        if self._call_id is not None and epoch == self._epoch:
            self._frames_in += 1
            if self.on_audio is not None:
                await self.on_audio(self._call_id, frame[AUDIO_HEADER.size:])

    async def _dispatch_events(self) -> None:
        while True:
            event = await self._events.get()
            try:
                if self.on_event is not None:
                    await self.on_event(event)
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("Device event handler failed")
            finally:
                self._events.task_done()

    async def command(self, command: str, call_id: str | None = None, *, timeout: float = 10, **payload: Any) -> dict[str, Any]:
        link = self._link
        if link is None or not self.connected:
            raise DeviceError("device_not_connected")
        command_id = str(uuid.uuid4())
        future: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
        self._pending[command_id] = future
        message = {"type": "command", "id": command_id, "command": command, **payload}
        if call_id is not None:
            message["call_id"] = call_id
        try:
            async with asyncio.timeout(timeout):
                async with self._send_lock:
                    if self._link is not link:
                        raise DeviceError("device_replaced")
                    await link.send(json.dumps(message, separators=(",", ":")))
                return await future
        except TimeoutError as exc:
            raise DeviceError(f"device_command_timeout: {command}") from exc
        except LinkClosed as exc:
            raise DeviceError("device_disconnected") from exc
        finally:
            self._pending.pop(command_id, None)
            if not future.done():
                future.cancel()

    @staticmethod
    def _describe(link: DeviceLink | None) -> str:
        """Open paths with their counters, or just the route, for one log line."""
        if link is None:
            return "no link"
        return "; ".join(link.paths()) or str(link.route())

    def begin_stream(self, call_id: str, epoch: int) -> None:
        self._clear(self._outgoing)
        self._call_id = call_id
        self._epoch = epoch
        self._submitted_samples = 0
        self._dropped_samples = 0
        self._played_samples = 0
        self._queued_samples = 0
        self._frames_out = 0
        self._frames_in = 0
        self._send_wait = 0.0
        self._ping_wait = 0.0
        log.info("call %s: phone link at audio start: %s", call_id, self._describe(self._link))

    def stop_stream(self) -> None:
        if self._call_id is not None:
            log.info(
                "call %s: link carried %d frames to the phone and %d back; longest send wait %d ms, "
                "slowest ping %d ms; link at the end: %s",
                self._call_id, self._frames_out, self._frames_in,
                round(self._send_wait * 1000), round(self._ping_wait * 1000),
                self._describe(self._link),
            )
        if self._dropped_samples:
            log.warning("Dropped %d ms of outgoing call audio on a stalled link", self._dropped_samples // 48)
        self._call_id = None
        self._submitted_samples = 0
        self._dropped_samples = 0
        self._clear(self._outgoing)

    def send_audio(self, call_id: str, pcm: bytes) -> None:
        if not self.connected or self._call_id != call_id:
            raise DeviceError("audio_stream_not_active")
        if not pcm or len(pcm) % 2 or len(pcm) > MAX_PCM_BYTES:
            raise DeviceError("Invalid outgoing PCM frame")
        if self._outgoing.full():
            # A stalled link must not delay speech: drop the oldest queued frame, keep the newest.
            _, _, stale = self._outgoing.get_nowait()
            self._outgoing.task_done()
            self._dropped_samples += (len(stale) - AUDIO_HEADER.size) // 2
        frame = AUDIO_HEADER.pack(AUDIO_MAGIC, self._epoch, self._submitted_samples) + pcm
        self._outgoing.put_nowait((call_id, self._epoch, frame))
        self._submitted_samples += len(pcm) // 2

    async def _write_audio(self, link: DeviceLink) -> None:
        try:
            while True:
                call_id, epoch, frame = await self._outgoing.get()
                try:
                    began = time.monotonic()
                    async with asyncio.timeout(2):
                        async with self._send_lock:
                            if self._link is link and self._call_id == call_id and self._epoch == epoch:
                                await link.send(frame)
                                self._frames_out += 1
                    self._send_wait = max(self._send_wait, time.monotonic() - began)
                finally:
                    self._outgoing.task_done()
        except (asyncio.CancelledError, LinkClosed):
            raise
        except Exception:
            log.exception("Phone PCM writer failed")
            await link.close(1011, "Audio transport failed")

    async def _heartbeat(self, link: DeviceLink) -> None:
        try:
            while self._link is link:
                began = time.monotonic()
                try:
                    await self.command("ping", timeout=5)
                finally:
                    self._ping_wait = max(self._ping_wait, time.monotonic() - began)
                await asyncio.sleep(2)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.warning(
                "Phone heartbeat failed after %d ms: %s (%s)",
                round((time.monotonic() - began) * 1000), exc, self._describe(link),
            )
            await link.close(1011, "Device heartbeat failed")

    async def link_test(self, seconds: float) -> dict[str, Any]:
        """Load the idle link like a call and time it.

        Call-sized frames leave every 20 ms and the phone echoes them, so both
        directions carry what a conversation carries while pings measure how
        long control messages wait behind that audio.
        """
        link = self._link
        if link is None or not self.connected:
            raise DeviceError("device_not_connected")
        if self._call_id is not None or self.info.get("active_call") or self._probe is not None:
            raise DeviceError("device_busy")
        pending = self._probe = {}
        self._probe_ms = []
        self._probe_id = (self._probe_id + 1) & 0xFFFFFFFF or 1
        pings: list[float] = []
        route = link.route()

        async def ping() -> None:
            while True:
                began = time.monotonic()
                await self.command("ping", timeout=5)
                pings.append((time.monotonic() - began) * 1000)
                await asyncio.sleep(0.25)

        pinger = asyncio.create_task(ping(), name="link-test-ping")
        payload = bytes(FRAME_PCM_BYTES)
        started = time.monotonic()
        stall = 0.0
        count = 0
        try:
            while (now := time.monotonic()) - started < seconds:
                if self._link is not link or self._call_id is not None or pinger.done():
                    break
                pending[count] = now
                async with self._send_lock:
                    await link.send(AUDIO_HEADER.pack(PROBE_MAGIC, self._probe_id, count) + payload)
                stall = max(stall, time.monotonic() - now)
                count += 1
                await asyncio.sleep(max(0.0, started + count * 0.02 - time.monotonic()))
            deadline = time.monotonic() + 1
            while pending and self._link is link and time.monotonic() < deadline:
                await asyncio.sleep(0.02)
        except LinkClosed:
            pass
        finally:
            pinger.cancel()
            await asyncio.gather(pinger, return_exceptions=True)
            self._probe = None
        alive = self._link is link
        frames, control = _spread(self._probe_ms), _spread(pings)
        return {
            "peer": link.peer,
            "route": route,
            "route_after": link.route() if alive else None,
            "seconds": round(time.monotonic() - started, 1),
            "frames_sent": count,
            "frames_echoed": len(self._probe_ms),
            "frame_ms": frames,
            "ping_ms": control,
            "send_stall_ms": round(stall * 1000),
            "link_lost": not alive,
            # Enough for a conversation: nearly every frame came back in time
            # and control messages did not wait behind the audio.
            "healthy": alive and count > 0 and len(self._probe_ms) >= count * 0.9
            and frames is not None and frames["p95"] <= 400
            and control is not None and control["max"] <= 1000,
        }

    async def wait_played(self, call_id: str, target_samples: int, timeout: float = 3) -> bool:
        try:
            async with asyncio.timeout(timeout):
                async with self._progress:
                    await self._progress.wait_for(
                        lambda: self._played_samples >= target_samples or not self.connected or self._call_id != call_id
                    )
            return self.connected and self._call_id == call_id and self._played_samples >= target_samples
        except TimeoutError:
            return False

    async def flush(self, call_id: str) -> None:
        if self._call_id != call_id:
            return
        self._clear(self._outgoing)
        await self.command("flush", call_id, epoch=self._epoch)

    async def close(self) -> None:
        link = self._link
        if link is not None:
            await link.close(1001, "Daemon stopping")

    @staticmethod
    def _clear(queue: asyncio.Queue[Any]) -> None:
        while not queue.empty():
            try:
                queue.get_nowait()
                queue.task_done()
            except asyncio.QueueEmpty:
                break


class _WebSocketLink:
    def __init__(self, connection: ServerConnection) -> None:
        self._connection = connection
        address = connection.remote_address
        self.peer = str(address[0]) if address else "unknown"

    def route(self) -> str | None:
        return None

    def paths(self) -> list[str]:
        return []

    async def recv(self) -> str | bytes:
        try:
            return await self._connection.recv()
        except ConnectionClosed as exc:
            raise LinkClosed() from exc

    async def send(self, message: str | bytes) -> None:
        try:
            await self._connection.send(message)
        except ConnectionClosed as exc:
            raise LinkClosed() from exc

    async def close(self, code: int, reason: str) -> None:
        await self._connection.close(code=code, reason=reason)


class WebSocketListener:
    """TLS WebSocket endpoint for phones that reach the daemon by address."""

    def __init__(self, hub: DeviceHub, token: str) -> None:
        self._hub = hub
        self._token = token
        self._server: Server | None = None

    @property
    def port(self) -> int | None:
        server = self._server
        return next(iter(server.sockets)).getsockname()[1] if server is not None else None

    async def start(self, host: str, port: int, tls: ssl.SSLContext) -> None:
        """Listen for the phone. Heartbeats are application pings, so protocol pings stay off."""
        self._server = await serve(
            lambda connection: self._hub.attach(_WebSocketLink(connection)),
            host,
            port,
            ssl=tls,
            process_request=self._authorize,
            compression=None,
            max_size=MAX_MESSAGE_BYTES,
            ping_interval=None,
            close_timeout=2,
        )

    def _authorize(self, connection: ServerConnection, request: Request) -> Response | None:
        if request.path != DEVICE_PATH:
            return connection.respond(http.HTTPStatus.NOT_FOUND, "not found\n")
        presented = request.headers.get("Authorization", "")
        if not secrets.compare_digest(presented.encode(), f"Bearer {self._token}".encode()):
            return connection.respond(http.HTTPStatus.UNAUTHORIZED, "unauthorized\n")
        return None

    async def close(self) -> None:
        server = self._server
        self._server = None
        if server is not None:
            server.close()
            await server.wait_closed()
