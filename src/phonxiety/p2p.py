"""Phone link over iroh: the phone dials the daemon's endpoint id.

iroh finds a direct path between the two endpoints (same network or hole
punched) and falls back to a relay, so neither side needs a VPN app or a
reachable address. The device protocol travels on one bidirectional stream:

    frame = kind (1 byte) | length (uint32 big-endian) | payload
    kind 1: UTF-8 JSON text    kind 2: binary (PX01 PCM, PX02 Opus, PXLT probe)

The first frame from the phone is ``{"type": "auth", "token": ...}``.
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

from .device import MAX_MESSAGE_BYTES, DeviceError, DeviceHub, LinkClosed

log = logging.getLogger(__name__)
ALPN = b"phonxiety/device/1"
KEY_NAME = "link.key"
TEXT, BINARY = 1, 2
HANDSHAKE_TIMEOUT_S = 10


def frame(message: str | bytes) -> bytes:
    kind, payload = (TEXT, message.encode("utf-8")) if isinstance(message, str) else (BINARY, bytes(message))
    if not 1 <= len(payload) <= MAX_MESSAGE_BYTES:
        raise DeviceError("Invalid link frame size")
    return bytes((kind,)) + len(payload).to_bytes(4, "big") + payload


def parse_header(header: bytes) -> tuple[int, int]:
    kind, length = header[0], int.from_bytes(header[1:5], "big")
    if kind not in (TEXT, BINARY) or not 1 <= length <= MAX_MESSAGE_BYTES:
        raise DeviceError("Invalid link frame")
    return kind, length


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
    """Device protocol messages on one iroh bidirectional stream."""

    def __init__(self, connection: Any, stream: Any) -> None:
        self._connection = connection
        self._send = stream.send()
        self._recv = stream.recv()
        self.peer = f"iroh:{connection.remote_id().fmt_short()}"
        # The full id is what a relay allowlist needs.
        log.info("iroh connection from endpoint %s", connection.remote_id())

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

    async def recv(self) -> str | bytes:
        try:
            kind, length = parse_header(await self._recv.read_exact(5))
            payload = await self._recv.read_exact(length)
        except iroh.IrohError as exc:
            raise LinkClosed() from exc
        return payload.decode("utf-8") if kind == TEXT else payload

    async def send(self, message: str | bytes) -> None:
        try:
            await self._send.write_all(frame(message))
        except iroh.IrohError as exc:
            raise LinkClosed() from exc

    async def close(self, code: int, reason: str) -> None:
        with contextlib.suppress(iroh.IrohError):
            self._connection.close(code, reason.encode("utf-8")[:120])


class IrohListener:
    def __init__(self, hub: DeviceHub, token: str, state_dir: Path, relay_urls: tuple[str, ...] = ()) -> None:
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
            raise DeviceError("iroh_link_unavailable")
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
            await self._hub.attach(link)
        except (LinkClosed, TimeoutError, ValueError, DeviceError, iroh.IrohError) as exc:
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
