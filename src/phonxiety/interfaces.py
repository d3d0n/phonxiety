"""Audio and model events shared by the controller and voice backend."""

import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, Protocol

ToolHandler = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]

# Line actions the daemon performs itself, without asking the task agent. Gemini and
# Deepgram call them as tools (`send_dtmf`, `end_call`), which arrive as delegations with
# these exact requests. Codex can only delegate in plain language, so short plain requests
# such as "Press 2 for Russian" or "Please end the call" are recognised too (line_action).
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


_KEYPRESS = re.compile(
    r"^\s*(?:dtmf|(?:please\s+)?press(?:\s+the)?(?:\s+(?:key|digit|button)s?)?|нажми(?:те)?|нажать|bos(?:ing)?)"
    r"\s+(?P<digits>[0-9*#A-D](?:[\s,]*[0-9*#A-D])*)(?P<rest>.*)$",
    re.IGNORECASE,
)
_HANG_UP = re.compile(
    r"^\s*(?:end_call|(?:please\s+)?(?:end|finish)\s+(?:the|this)\s+call|(?:please\s+)?hang\s+up"
    r"|(?:пожалуйста,?\s+)?(?:заверши(?:те)?|положи(?:те)?)\s+(?:звонок|трубку))(?P<rest>.*)$",
    re.IGNORECASE,
)
# Anything that makes a command conditional or one step of several sends it to the task agent.
_QUALIFIED = re.compile(
    r"[?0-9]|\b(?:if|only|unless|when|after|before|then|and|but|wait|если|только|когда|после|потом"
    r"|затем|и|но|подожд\w*|agar|keyin)\b",
    re.IGNORECASE,
)


def line_action(request: str) -> tuple[str, str] | None:
    """("dtmf", digits) or ("end_call", "") for a request the daemon executes itself, else None.

    Only a complete, unqualified command qualifies ("Press 2 for Russian", "End the call.");
    a condition, a question or a second step sends the request to the task agent instead.
    """
    match = _HANG_UP.match(request)
    if match and not _QUALIFIED.search(match["rest"]):
        return ("end_call", "")
    match = _KEYPRESS.match(request)
    if match and not _QUALIFIED.search(match["rest"]):
        digits = re.sub(r"[\s,]", "", match["digits"]).upper()
        if len(digits) <= 32:
            return ("dtmf", digits)
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
