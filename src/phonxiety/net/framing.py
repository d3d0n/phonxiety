"""Message framing on a byte stream (the iroh link; WebSocket frames its own messages).

    frame = kind (1 byte) | length (uint32 big-endian) | payload
    kind 1: UTF-8 JSON text    kind 2: binary (PX01 PCM, PX02 Opus, PXLT probe)

The Android app frames identically (`net/Framing.kt`).
"""

from __future__ import annotations

from .link import MAX_MESSAGE_BYTES

TEXT, BINARY = 1, 2
HEADER_BYTES = 5


class FrameError(ValueError):
    """A frame outside the protocol: unknown kind or a size out of bounds."""


def frame(message: str | bytes) -> bytes:
    kind, payload = (TEXT, message.encode("utf-8")) if isinstance(message, str) else (BINARY, bytes(message))
    if not 1 <= len(payload) <= MAX_MESSAGE_BYTES:
        raise FrameError("Invalid link frame size")
    return bytes((kind,)) + len(payload).to_bytes(4, "big") + payload


def parse_header(header: bytes) -> tuple[int, int]:
    kind, length = header[0], int.from_bytes(header[1:HEADER_BYTES], "big")
    if kind not in (TEXT, BINARY) or not 1 <= length <= MAX_MESSAGE_BYTES:
        raise FrameError("Invalid link frame")
    return kind, length
