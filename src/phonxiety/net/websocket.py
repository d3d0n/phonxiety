"""TLS WebSocket listener for phones that reach the daemon by address.

The phone pins the daemon's self-signed certificate (`tls.py`) and authenticates with
`Authorization: Bearer <device token>`; websockets frames each message itself.
"""

from __future__ import annotations

import http
import secrets
import ssl

from websockets.asyncio.server import Server, ServerConnection, serve
from websockets.exceptions import ConnectionClosed
from websockets.http11 import Request, Response

from .link import MAX_MESSAGE_BYTES, LinkClosed, LinkSink

DEVICE_PATH = "/device"


class _WebSocketLink:
    # One TCP stream carries control and audio: bulk data uses the paged base64 form.
    supports_attachments = False

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

    async def send_datagram(self, data: bytes) -> bool:
        return False  # one TCP stream: nothing travels unreliably

    async def close(self, code: int, reason: str) -> None:
        await self._connection.close(code=code, reason=reason)


class WebSocketListener:
    """TLS WebSocket endpoint for phones that reach the daemon by address."""

    def __init__(self, hub: LinkSink, token: str) -> None:
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
