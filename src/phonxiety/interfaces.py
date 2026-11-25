"""Audio and model events shared by the controller and voice backend."""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, Protocol

ToolHandler = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]


@dataclass(slots=True)
class VoiceCallbacks:
    audio: Callable[[bytes], Awaitable[None]]
    transcript: Callable[[str, str, bool], Awaitable[None]]
    delegation: Callable[[str, str], Awaitable[None]]
    turn_done: Callable[[str], Awaitable[None]]
    error: Callable[[str], Awaitable[None]]


class VoiceSession(Protocol):
    async def start(self) -> None: ...

    def push_audio(self, pcm: bytes) -> None: ...

    async def append_context(
        self, text: str, *, delegation_id: str | None = None, commentary: bool = False
    ) -> None: ...

    async def close(self) -> None: ...
