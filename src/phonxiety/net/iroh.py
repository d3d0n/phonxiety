"""Phone link over iroh: the phone dials the daemon's endpoint id.

iroh finds a direct path between the two endpoints (same network or hole
punched) and falls back to a relay, so neither side needs a VPN app or a
reachable address. The device protocol travels on one bidirectional stream in
the frames of `framing.py`; the first frame from the phone is
``{"type": "auth", "token": ...}``.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import secrets
import tempfile
from pathlib import Path
from typing import Any

import iroh

from .framing import BINARY, HEADER_BYTES, TEXT, FrameError, frame, parse_header
from .link import LinkClosed, LinkSink, LinkUnavailable

log = logging.getLogger(__name__)
ALPN = b"phonxiety/device/2"
KEY_NAME = "link.key"
HANDSHAKE_TIMEOUT_S = 10


def _secret_key(state_dir: Path) -> bytes:
    """The daemon's endpoint identity; created once, the phone is paired against it."""
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = state_dir / KEY_NAME
    try:
        key = path.read_bytes()
    except FileNotFoundError:
        key = iroh.SecretKey.generate().to_bytes()
        fd, temporary = tempfile.mkstemp(prefix=f".{KEY_NAME}-", dir=state_dir)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(key)
                stream.flush()
                os.fsync(stream.fileno())
            try:
                os.link(temporary, path)
            except FileExistsError:
                key = path.read_bytes()
        finally:
            os.unlink(temporary)
    if len(key) != 32:
        raise ValueError(f"Invalid link key file: {path}")
    return key


class IrohLink:
    """Device protocol on one iroh connection: control and audio on separate streams.

    The phone opens a bidirectional control stream (JSON text frames, the auth frame
    first) and a unidirectional audio stream to the daemon; the daemon opens one audio
    stream back. A lost packet on an audio stream therefore never holds back a command
    or a ping. Probe frames may also travel as QUIC datagrams (`send_datagram`).
    """

    def __init__(self, connection: Any, control: Any) -> None:
        self._connection = connection
        self._control_send = control.send()
        self._control_recv = control.recv()
        self._audio_send: Any = None
        self._inbox: asyncio.Queue[str | bytes | BaseException] = asyncio.Queue(maxsize=256)
        self._readers: list[asyncio.Task[None]] = []
        self.peer = f"iroh:{connection.remote_id().fmt_short()}"
        # The full id is what a relay allowlist needs.
        log.info("iroh connection from endpoint %s", connection.remote_id())

    async def open_audio(self) -> None:
        """After auth: take the phone's audio stream, open ours, start the readers."""
        audio_recv = await self._connection.accept_uni()
        self._audio_send = await self._connection.open_uni()
        with contextlib.suppress(AttributeError, iroh.IrohError):
            self._control_send.set_priority(1)
            self._audio_send.set_priority(0)
        self._readers = [
            asyncio.create_task(self._read(self._control_recv, TEXT), name="iroh-control"),
            asyncio.create_task(self._read(audio_recv, BINARY), name="iroh-audio"),
            asyncio.create_task(self._read_datagrams(), name="iroh-datagrams"),
        ]

    def route(self) -> str | None:
        """Selected path: `direct` with the phone's address or `relay` with the relay, and its round trip."""
        try:
            selected = next((path for path in self._connection.paths() if path.is_selected), None)
        except iroh.IrohError:
            return None
        if selected is None:
            return None
        return f"{'relay' if selected.is_relay else 'direct'} {selected.remote_addr}, {selected.rtt_ms} ms"

    def paths(self) -> list[str]:
        """Every open path, the selected one starred, with its transport counters."""
        try:
            snapshots = self._connection.paths()
        except iroh.IrohError:
            return []
        return [
            f"{'*' if path.is_selected else ''}{'relay' if path.is_relay else 'direct'} {path.remote_addr} "
            f"rtt {path.rtt_ms} ms, sent {path.stats.udp_tx_datagrams}, received {path.stats.udp_rx_datagrams}, "
            f"lost {path.stats.lost_packets}, cwnd {path.stats.cwnd}, mtu {path.stats.current_mtu}"
            for path in snapshots
        ]

    @staticmethod
    async def _frame(stream: Any) -> tuple[int, bytes]:
        try:
            kind, length = parse_header(await stream.read_exact(HEADER_BYTES))
            return kind, await stream.read_exact(length)
        except iroh.IrohError as exc:
            raise LinkClosed() from exc

    async def _read(self, stream: Any, expected: int) -> None:
        try:
            while True:
                kind, payload = await self._frame(stream)
                if kind != expected:
                    raise FrameError("Frame on the wrong stream")
                await self._inbox.put(payload.decode("utf-8") if kind == TEXT else payload)
        except asyncio.CancelledError:
            raise
        except BaseException as exc:  # handed to recv(), which raises it in the hub
            await self._inbox.put(exc)

    async def _read_datagrams(self) -> None:
        try:
            while True:
                await self._inbox.put(bytes(await self._connection.read_datagram()))
        except iroh.IrohError:
            return  # the connection is closing; the stream readers report it

    async def recv(self) -> str | bytes:
        if not self._readers:  # the auth frame, before the audio streams exist
            kind, payload = await self._frame(self._control_recv)
            if kind != TEXT:
                raise FrameError("Binary frame on the control stream")
            return payload.decode("utf-8")
        item = await self._inbox.get()
        if isinstance(item, BaseException):
            raise item
        return item

    async def send(self, message: str | bytes) -> None:
        stream = self._control_send if isinstance(message, str) else self._audio_send
        try:
            await stream.write_all(frame(message))
        except iroh.IrohError as exc:
            raise LinkClosed() from exc

    async def send_datagram(self, data: bytes) -> bool:
        try:
            if len(data) > (self._connection.max_datagram_size() or 0):
                return False
            await self._connection.send_datagram(data)
            return True
        except iroh.IrohError:
            return False

    async def close(self, code: int, reason: str) -> None:
        for reader in self._readers:
            reader.cancel()
        with contextlib.suppress(iroh.IrohError):
            self._connection.close(code, reason.encode("utf-8")[:120])


class IrohListener:
    def __init__(self, hub: LinkSink, token: str, state_dir: Path, relay_urls: tuple[str, ...] = ()) -> None:
        self._hub = hub
        self._token = token
        self._state_dir = state_dir
        self._relay_urls = relay_urls
        self._endpoint: Any = None
        self._accepting: asyncio.Task[None] | None = None
        self._serving: set[asyncio.Task[None]] = set()

    @property
    def endpoint_id(self) -> str | None:
        return str(self._endpoint.id()) if self._endpoint is not None else None

    async def start(self) -> None:
        # iroh completes its futures from its own threads and needs this loop named explicitly.
        iroh.iroh_ffi.uniffi_set_event_loop(asyncio.get_running_loop())
        options = iroh.EndpointOptions(preset=iroh.preset_n0(), alpns=[ALPN], secret_key=_secret_key(self._state_dir))
        if self._relay_urls:
            # Own relays replace n0's public ones; address lookup still publishes the home relay.
            options.relay_mode = iroh.RelayMode.custom_from_urls(list(self._relay_urls))
        self._endpoint = await iroh.Endpoint.bind(options)
        self._accepting = asyncio.create_task(self._accept(), name="iroh-accept")

    async def ticket(self) -> str:
        """Address the phone dials: endpoint id plus the relay and addresses known right now."""
        endpoint = self._endpoint
        if endpoint is None:
            raise LinkUnavailable("iroh_link_unavailable")
        # A ticket issued before the home relay is known would only work on the local network.
        with contextlib.suppress(TimeoutError):
            async with asyncio.timeout(5):
                await endpoint.online()
        return str(iroh.EndpointTicket.from_addr(endpoint.addr()))

    async def _accept(self) -> None:
        while True:
            incoming = await self._endpoint.accept_next()
            if incoming is None:
                return
            task = asyncio.create_task(self._serve(incoming), name="iroh-phone")
            self._serving.add(task)
            task.add_done_callback(self._serving.discard)

    async def _serve(self, incoming: Any) -> None:
        connection = None
        try:
            async with asyncio.timeout(HANDSHAKE_TIMEOUT_S):
                connection = await (await incoming.accept()).connect()
                link = IrohLink(connection, await connection.accept_bi())
                auth = json.loads(await link.recv())
            presented = auth.get("token") if isinstance(auth, dict) and auth.get("type") == "auth" else None
            if not isinstance(presented, str) or not secrets.compare_digest(presented.encode(), self._token.encode()):
                log.warning("Rejected an unauthorized iroh peer %s", link.peer)
                await link.close(4401, "unauthorized")
                return
            async with asyncio.timeout(HANDSHAKE_TIMEOUT_S):
                await link.open_audio()
            await self._hub.attach(link)
        except (LinkClosed, TimeoutError, ValueError, iroh.IrohError) as exc:  # ValueError: bad frame or JSON
            log.info("iroh peer handshake failed: %s", type(exc).__name__)
            if connection is not None:
                with contextlib.suppress(iroh.IrohError):
                    connection.close(4400, b"handshake failed")

    async def close(self) -> None:
        tasks = [task for task in (self._accepting, *self._serving) if task is not None]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        endpoint, self._endpoint = self._endpoint, None
        if endpoint is not None:
            with contextlib.suppress(iroh.IrohError):
                await endpoint.close()
