"""Control and bounded call-audio transport to one Android bridge.

The hub speaks the device protocol over any authenticated message link from
``phonxiety.net``: an iroh stream or the TLS WebSocket listener.
"""

from __future__ import annotations

import asyncio
import json
import logging
import statistics
import struct
import time
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

from .audio.format import FRAME_SAMPLES
from .audio.opus import DEFAULT_OPUS_BITRATE, OpusDecoder, OpusEncoder
from .net.link import MAX_CONTROL_CHARS, DeviceLink, LinkClosed

log = logging.getLogger(__name__)
AUDIO_HEADER = struct.Struct("!4sIQ")
AUDIO_MAGIC = b"PX01"  # PCM s16le
OPUS_MAGIC = b"PX02"  # one 20 ms Opus packet
PROBE_MAGIC = b"PXLT"
MARK_MAGIC = b"PXMK"  # no payload; the phone reports it drained once everything before it played
PROTOCOL_VERSION = 2
FRAME_PCM_BYTES = 1920
MAX_PCM_BYTES = 19200


class DeviceError(RuntimeError):
    pass


def _spread(values: list[float]) -> dict[str, int] | None:
    if not values:
        return None
    ordered = sorted(values)
    return {
        "median": round(statistics.median(ordered)),
        "p95": round(ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))]),
        "max": round(ordered[-1]),
    }


class DeviceHub:
    def __init__(self, audio_codec: str = "pcm", opus_bitrate: int = DEFAULT_OPUS_BITRATE) -> None:
        self.audio_codec = audio_codec
        self.opus_bitrate = opus_bitrate
        self._encoder: OpusEncoder | None = None
        self._decoder: OpusDecoder | None = None
        self._link: DeviceLink | None = None
        self._ready = asyncio.Event()
        self._attach_lock = asyncio.Lock()
        self._link_free = asyncio.Event()
        self._link_free.set()
        self._send_lock = asyncio.Lock()  # control messages
        self._audio_lock = asyncio.Lock()  # the audio path, separate so a stalled audio write never holds a command
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._outgoing: asyncio.Queue[tuple[str, int, bytes, int]] = asyncio.Queue(maxsize=10)
        self._events: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=64)
        self._progress = asyncio.Condition()
        self._call_id: str | None = None
        self._epoch = 0
        self._submitted_samples = 0
        self._dropped_samples = 0
        self._played_samples = 0
        self._queued_samples = 0
        self._mark = 0  # last playback mark sent on the audio path
        self._drained_mark = 0  # last mark the phone reported played out
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
            "audio_codec": self.stream_codec() if self.connected else None,
        }

    async def attach(self, link: DeviceLink) -> None:
        """Serve one authenticated phone link until it ends.

        A second link from the same phone and boot replaces the first: the phone only opens
        a new link after losing the old one, which the daemon may not have noticed yet. A
        link from another phone is refused while one is connected.
        """
        try:
            async with asyncio.timeout(10):
                hello = json.loads(await link.recv())
        except (LinkClosed, TimeoutError, ValueError):
            await link.close(1002, "Device handshake failed")
            return
        if not isinstance(hello, dict) or hello.get("type") != "hello" or hello.get("version") != PROTOCOL_VERSION:
            await link.close(1002, "Incompatible device handshake")
            return
        async with self._attach_lock:
            current = self._link
            if current is not None:
                same_phone = (hello.get("device"), hello.get("boot_id")) == (
                    self.info.get("device"), self.info.get("boot_id")
                ) and self.info.get("boot_id") is not None
                if not same_phone:
                    await link.close(1013, "A device is already connected")
                    return
                log.info("Phone %s reconnected from %s; closing its previous link", hello.get("device"), link.peer)
                await current.close(1012, "Replaced by a newer connection")
                try:
                    async with asyncio.timeout(5):
                        await self._link_free.wait()
                except TimeoutError:
                    await link.close(1013, "A device is already connected")
                    return
            self._link = link
            self._link_free.clear()
        self._ready.clear()
        workers: list[asyncio.Task[Any]] = []
        try:
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
                self._link_free.set()

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
                mark = event.get("drained_mark")
                if isinstance(mark, int) and mark > self._drained_mark:
                    self._drained_mark = mark
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
        if size <= 0 or size > MAX_PCM_BYTES:
            raise DeviceError("Invalid audio frame size")
        magic, epoch, sequence = AUDIO_HEADER.unpack_from(frame)
        if magic == PROBE_MAGIC:
            # The echo of an earlier probe carries that probe's id and must not count for this one.
            if self._probe is not None and epoch == self._probe_id:
                started = self._probe.pop(sequence, None)
                if started is not None:
                    self._probe_ms.append((time.monotonic() - started) * 1000)
            return
        if magic == AUDIO_MAGIC:
            if size % 2:
                raise DeviceError("Invalid PCM frame size")
        elif magic != OPUS_MAGIC:
            raise DeviceError("Invalid audio frame version")
        if self._call_id is None or epoch != self._epoch:
            return
        payload = frame[AUDIO_HEADER.size:]
        if magic == OPUS_MAGIC:
            if self._decoder is None:
                raise DeviceError("Opus frame on a PCM stream")
            try:
                payload = self._decoder.decode(payload)
            except Exception as exc:
                raise DeviceError("Invalid Opus frame") from exc
            if not payload:
                return
        self._frames_in += 1
        if self.on_audio is not None:
            await self.on_audio(self._call_id, payload)

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

    def stream_codec(self) -> str:
        """The configured format when the phone supports it, else PCM, which every phone speaks."""
        offered = self.info.get("capabilities", {}).get("audio_codecs")
        if self.audio_codec == "opus" and isinstance(offered, list) and "opus" in offered:
            return "opus"
        return "pcm"

    def begin_stream(self, call_id: str, epoch: int) -> dict[str, Any]:
        """Reset counters for a new audio epoch; returns the `start_audio` format fields."""
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
        codec = self.stream_codec()
        if codec == "opus":
            self._encoder, self._decoder = OpusEncoder(self.opus_bitrate), OpusDecoder()
        else:
            self._encoder = self._decoder = None
        log.info("call %s: %s audio; phone link at audio start: %s", call_id, codec, self._describe(self._link))
        return {"codec": "opus", "bitrate": self.opus_bitrate} if codec == "opus" else {"codec": "pcm"}

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
        self._encoder = self._decoder = None

    def send_audio(self, call_id: str, pcm: bytes) -> None:
        if not self.connected or self._call_id != call_id:
            raise DeviceError("audio_stream_not_active")
        if not pcm or len(pcm) % 2 or len(pcm) > MAX_PCM_BYTES:
            raise DeviceError("Invalid outgoing PCM frame")
        if self._encoder is None:
            self._enqueue(call_id, AUDIO_MAGIC, pcm, len(pcm) // 2)
            return
        for packet in self._encoder.encode(pcm):
            self._enqueue(call_id, OPUS_MAGIC, packet, FRAME_SAMPLES)

    def _enqueue(self, call_id: str, magic: bytes, payload: bytes, samples: int) -> None:
        if self._outgoing.full():
            # A stalled link must not delay speech: drop the oldest queued frame, keep the newest.
            _, _, _, stale = self._outgoing.get_nowait()
            self._outgoing.task_done()
            self._dropped_samples += stale
        frame = AUDIO_HEADER.pack(magic, self._epoch, self._submitted_samples) + payload
        self._outgoing.put_nowait((call_id, self._epoch, frame, samples))
        self._submitted_samples += samples

    async def _write_audio(self, link: DeviceLink) -> None:
        try:
            while True:
                call_id, epoch, frame, _ = await self._outgoing.get()
                try:
                    began = time.monotonic()
                    async with asyncio.timeout(2):
                        async with self._audio_lock:
                            if self._link is link and self._call_id == call_id and self._epoch == epoch:
                                await link.send(frame)
                                self._frames_out += 1
                    self._send_wait = max(self._send_wait, time.monotonic() - began)
                finally:
                    self._outgoing.task_done()
        except (asyncio.CancelledError, LinkClosed):
            raise
        except Exception:
            log.exception("Phone audio writer failed")
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

    async def link_test(self, seconds: float, codec: str | None = None, *, datagrams: bool = False) -> dict[str, Any]:
        """Load the idle link like a call and time it.

        Call-sized frames leave every 20 ms and the phone echoes them, so both
        directions carry what a conversation carries while pings measure how
        long control messages wait behind that audio. With `datagrams` the probes
        travel as QUIC datagrams (lost ones stay lost) instead of on the audio stream.
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
        # Probe frames are the size the format puts on the wire every 20 ms: the one a call
        # would use, or `codec` to measure another format's load (the phone echoes any size).
        codec = codec or self.stream_codec()
        payload = bytes(self.opus_bitrate // 8 // 50 if codec == "opus" else FRAME_PCM_BYTES)
        started = time.monotonic()
        stall = 0.0
        count = 0
        try:
            while (now := time.monotonic()) - started < seconds:
                if self._link is not link or self._call_id is not None or pinger.done():
                    break
                pending[count] = now
                probe = AUDIO_HEADER.pack(PROBE_MAGIC, self._probe_id, count) + payload
                if datagrams:
                    if not await link.send_datagram(probe):
                        raise DeviceError("datagrams_unavailable")
                else:
                    async with self._audio_lock:
                        await link.send(probe)
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
            "codec": codec,
            "transport": "datagram" if datagrams else "stream",
            "frame_bytes": len(payload),
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
        """Whether everything handed to the phone has played; the caller has stopped sending.

        A mark frame follows the last audio on the audio path; the phone reports it drained
        once everything before it has played. Counters cannot prove this (the phone counts
        its pre-roll and not what it dropped on overflow), and a ping on the control stream
        may overtake audio. `target_samples` is only reported when confirmation fails.
        """
        if not self.connected or self._call_id != call_id:
            return False
        self._mark += 1
        mark = self._mark
        if self._outgoing.full():
            _, _, _, stale = self._outgoing.get_nowait()
            self._outgoing.task_done()
            self._dropped_samples += stale
        self._outgoing.put_nowait((call_id, self._epoch, AUDIO_HEADER.pack(MARK_MAGIC, self._epoch, mark), 0))

        def done() -> bool:
            return not self.connected or self._call_id != call_id or self._drained_mark >= mark

        try:
            async with asyncio.timeout(timeout):
                async with self._progress:
                    await self._progress.wait_for(done)
        except TimeoutError:
            log.warning(
                "call %s: playback not confirmed: mark %d not drained (last %d); played %d of %d samples, %d queued",
                call_id, mark, self._drained_mark, self._played_samples, target_samples, self._queued_samples,
            )
            return False
        return self.connected and self._call_id == call_id

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
