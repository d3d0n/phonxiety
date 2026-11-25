"""A local TCP port on the Mac that reaches a service on the phone through the iroh link.

`adb connect 127.0.0.1:<port>` then talks to the phone's adbd without a cable or a reachable
phone address: each TCP connection becomes one extra bidirectional stream on the already
authenticated device connection, introduced by a `{"type": "tunnel", "service": ...}` frame.
The phone answers only on that link (which the pairing token admits) and keeps its adbd
port closed to everything but loopback; adb's own key authorisation still applies.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from typing import Any, Protocol

from .framing import frame

log = logging.getLogger(__name__)
CHUNK_BYTES = 64 * 1024


class TunnelError(RuntimeError):
    """No phone link that can carry tunnels right now."""


class Tunnels(Protocol):
    """Opens a raw byte stream to a phone service: (send stream, receive stream)."""

    async def open_tunnel(self, service: str) -> tuple[Any, Any]: ...


async def open_stream(connection: Any, service: str) -> tuple[Any, Any]:
    """Open a bidirectional stream on an iroh connection and introduce it as a tunnel."""
    stream = await connection.open_bi()
    send, recv = stream.send(), stream.recv()
    await send.write_all(frame(json.dumps({"type": "tunnel", "service": service})))
    return send, recv


class TunnelListener:
    """Accepts local TCP connections and pipes each one through its own tunnel stream."""

    def __init__(self, tunnels: Tunnels, service: str) -> None:
        self._tunnels = tunnels
        self._service = service
        self._server: asyncio.Server | None = None
        self._pipes: set[asyncio.Task[None]] = set()

    @property
    def port(self) -> int | None:
        server = self._server
        return server.sockets[0].getsockname()[1] if server is not None and server.sockets else None

    async def start(self, host: str, port: int) -> None:
        self._server = await asyncio.start_server(self._accept, host, port)

    async def _accept(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.current_task()
        if task is not None:
            self._pipes.add(task)
        try:
            try:
                send, recv = await self._tunnels.open_tunnel(self._service)
            except Exception as exc:  # noqa: BLE001 - refuse this client, keep listening
                log.info("%s tunnel unavailable: %s", self._service, exc)
                return
            await _pipe(reader, writer, send, recv)
        finally:
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()
            if task is not None:
                self._pipes.discard(task)

    async def close(self) -> None:
        server, self._server = self._server, None
        if server is not None:
            server.close()
        for task in tuple(self._pipes):
            task.cancel()
        await asyncio.gather(*self._pipes, return_exceptions=True)
        if server is not None:
            await server.wait_closed()


async def _pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, send: Any, recv: Any) -> None:
    async def outbound() -> None:
        while data := await reader.read(CHUNK_BYTES):
            await send.write_all(data)
        with contextlib.suppress(Exception):
            await send.finish()

    async def inbound() -> None:
        try:
            while data := await recv.read(CHUNK_BYTES):
                writer.write(data)
                await writer.drain()
        except Exception:  # noqa: BLE001 - the phone side ended or reset the stream
            return

    tasks = [asyncio.create_task(outbound()), asyncio.create_task(inbound())]
    try:
        # Either direction ending (or failing) ends the tunnel: adb has no half-close use.
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
