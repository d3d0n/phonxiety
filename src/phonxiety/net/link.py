"""What every phone link provides to the device hub, and the limits all of them enforce."""

from __future__ import annotations

from typing import Protocol

MAX_CONTROL_CHARS = 65536
MAX_MESSAGE_BYTES = 2 * MAX_CONTROL_CHARS


class LinkClosed(Exception):
    """The phone connection ended."""


class LinkUnavailable(RuntimeError):
    """A transport cannot serve the phone right now, e.g. its endpoint is not bound."""


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

    async def send(self, message: str | bytes) -> None:
        """Text goes on the control path, bytes on the audio path; each keeps its order."""
        ...

    async def send_datagram(self, data: bytes) -> bool:
        """Send unreliably and unordered when the transport can; False when it cannot."""
        ...

    async def close(self, code: int, reason: str) -> None: ...


class LinkSink(Protocol):
    """Who serves an authenticated link until it ends: the device hub."""

    async def attach(self, link: DeviceLink) -> None: ...
