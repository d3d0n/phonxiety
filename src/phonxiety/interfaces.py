"""Audio and model events shared by the controller and voice backend."""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, Protocol

ToolHandler = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]

# Line actions the daemon performs itself, without asking the task agent. A voice backend
# reports them as delegations with these exact requests; backends that let us declare
# functions (Gemini, Deepgram) expose them as tools, Codex sends the text itself.
END_CALL_REQUEST = "END_CALL"
DTMF_REQUEST = "DTMF"  # followed by a space and the digits, e.g. "DTMF 2"
DTMF_DIGITS = "0123456789*#ABCD"

# Voice-model functions: name, description, parameters (name -> description).
CALL_FUNCTIONS: tuple[tuple[str, str, dict[str, str]], ...] = (
    (
        "send_dtmf",
        "Press keypad keys on the live call, e.g. to choose a menu option. Use only the digits "
        "the menu or the other party asked for. Say nothing about it to an automated menu.",
        {"digits": "Keys to press in order: 0-9, *, #, A-D."},
    ),
    (
        "end_call",
        "Hang up. Call it right after your short goodbye, once nothing more is expected from "
        "either side.",
        {},
    ),
    (
        "client_delegation",
        "Ask the backend that follows this call for anything else: a lookup, a decision, a "
        "multi-step keypad entry. Give a complete plain-language request.",
        {"request": "What the backend should do and why."},
    ),
)


def function_request(name: str, arguments: dict[str, Any]) -> str | None:
    """The delegation request a voice-model function call stands for, or None if invalid."""
    if name == "end_call":
        return END_CALL_REQUEST
    if name == "send_dtmf":
        digits = str(arguments.get("digits", "")).replace(" ", "").upper()
        if 0 < len(digits) <= 32 and all(char in DTMF_DIGITS for char in digits):
            return f"{DTMF_REQUEST} {digits}"
        return None
    if name == "client_delegation":
        request = arguments.get("request")
        return request.strip() if isinstance(request, str) and request.strip() else None
    return None


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
