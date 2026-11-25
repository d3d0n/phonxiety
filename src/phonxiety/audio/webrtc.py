"""The aiortc source track that carries phone audio to the Codex WebRTC peer.

aiortc pulls frames from `recv()` on its own clock; this track paces 48 kHz mono PCM16
into it with continuous sample timestamps, and plays silence when nothing is queued.
"""

from __future__ import annotations

import asyncio
import threading
import time
from fractions import Fraction

from aiortc.mediastreams import AudioStreamTrack, MediaStreamError
from av import AudioFrame

from .format import BYTES_PER_SAMPLE, FRAME_BYTES, FRAME_SAMPLES, SAMPLE_RATE

MAX_INPUT_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * 2  # two seconds


class PcmSourceTrack(AudioStreamTrack):
    """Paced 48 kHz mono PCM16 source with continuous sample timestamps."""

    kind = "audio"

    def __init__(self, max_bytes: int = MAX_INPUT_BYTES) -> None:
        super().__init__()
        self._buffer = bytearray()
        self._buffer_lock = threading.Lock()
        self._max_bytes = max(FRAME_BYTES, int(max_bytes) // 2 * 2)
        self._timestamp: int | None = None
        self._start: float = 0.0
        self._dropped_bytes = 0

    @property
    def buffered_bytes(self) -> int:
        with self._buffer_lock:
            return len(self._buffer)

    @property
    def dropped_bytes(self) -> int:
        return self._dropped_bytes

    def push(self, pcm: bytes) -> None:
        if not isinstance(pcm, (bytes, bytearray, memoryview)):
            raise TypeError("PCM input must be bytes-like")
        data = bytes(pcm)
        if len(data) % BYTES_PER_SAMPLE:
            raise ValueError("PCM input must contain whole 16-bit samples")
        if not data:
            return
        with self._buffer_lock:
            if len(data) >= self._max_bytes:
                self._dropped_bytes += len(self._buffer) + len(data) - self._max_bytes
                self._buffer[:] = data[-self._max_bytes:]
            else:
                overflow = len(self._buffer) + len(data) - self._max_bytes
                if overflow > 0:
                    del self._buffer[:overflow]
                    self._dropped_bytes += overflow
                self._buffer.extend(data)

    def clear(self) -> None:
        with self._buffer_lock:
            self._buffer.clear()

    def _take(self, size: int) -> bytes | None:
        with self._buffer_lock:
            if not self._buffer:
                return None
            chunk = bytes(self._buffer[:size])
            del self._buffer[:size]
            return chunk.ljust(size, b"\x00")

    async def recv(self) -> AudioFrame:
        if self.readyState != "live":
            raise MediaStreamError
        if self._timestamp is None:
            self._start = time.monotonic()
            self._timestamp = 0
        else:
            self._timestamp += FRAME_SAMPLES
            deadline = self._start + (self._timestamp / SAMPLE_RATE)
            wait = deadline - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
        payload = self._take(FRAME_BYTES)
        if payload is None:
            payload = b"\x00" * FRAME_BYTES
        frame = AudioFrame(format="s16", layout="mono", samples=FRAME_SAMPLES)
        frame.planes[0].update(payload)
        frame.pts = self._timestamp
        frame.sample_rate = SAMPLE_RATE
        frame.time_base = Fraction(1, SAMPLE_RATE)
        return frame
