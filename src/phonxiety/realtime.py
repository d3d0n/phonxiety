"""Shared plumbing for websocket voice backends (Gemini Live, Deepgram Voice Agent).

Those APIs deliver speech in bursts, much faster than it plays. The phone link and the
phone's ring buffer hold well under a second, so output is paced here: 20 ms frames of
48 kHz mono PCM leave on the wall clock, and a barge-in drops what has not played yet.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable

from av import AudioFrame, AudioResampler

log = logging.getLogger(__name__)

SAMPLE_RATE = 48_000
FRAME_SAMPLES = 960
FRAME_BYTES = FRAME_SAMPLES * 2
LEAD_S = 0.06  # keep this much audio ahead of the playhead to ride out network jitter


class Resampler:
    """Mono s16le PCM from one rate to another, streaming."""

    def __init__(self, source_rate: int, target_rate: int) -> None:
        self._source_rate = source_rate
        self._resampler = AudioResampler(format="s16", layout="mono", rate=target_rate)
        self._pending = b""

    def convert(self, pcm: bytes) -> bytes:
        data = self._pending + pcm
        usable = len(data) - len(data) % 2
        self._pending = data[usable:]
        if not usable:
            return b""
        frame = AudioFrame(format="s16", layout="mono", samples=usable // 2)
        frame.planes[0].update(data[:usable])
        frame.sample_rate = self._source_rate
        out = bytearray()
        for converted in self._resampler.resample(frame):
            out += bytes(converted.planes[0])[: converted.samples * 2]
        return bytes(out)


class PacedOutput:
    """Hands speech to the phone at playback speed, one 20 ms frame at a time."""

    def __init__(
        self,
        emit: Callable[[bytes], Awaitable[None]],
        on_drained: Callable[[], Awaitable[None]] | None = None,
    ) -> None:
        self._emit = emit
        self._on_drained = on_drained
        self._buffer = bytearray()
        self._ready = asyncio.Event()
        self._task: asyncio.Task[None] | None = None
        self._notify_drain = False

    def start(self) -> None:
        self._task = asyncio.create_task(self._run(), name="voice-paced-output")

    def feed(self, pcm: bytes) -> None:
        self._buffer += pcm
        self._ready.set()

    def clear(self) -> None:
        """Barge-in: drop everything not yet handed to the phone."""
        self._buffer.clear()
        self._notify_drain = False

    def drain_then_notify(self) -> None:
        """Call `on_drained` once everything fed so far has been emitted."""
        self._notify_drain = True
        self._ready.set()

    @property
    def pending(self) -> bool:
        return bool(self._buffer)

    async def _run(self) -> None:
        playhead: float | None = None
        while True:
            if not self._buffer:
                playhead = None
                if self._notify_drain:
                    self._notify_drain = False
                    if self._on_drained is not None:
                        await self._on_drained()
                self._ready.clear()
                await self._ready.wait()
                continue
            now = time.monotonic()
            if playhead is None or playhead < now:
                playhead = now
            if playhead - now > LEAD_S:
                await asyncio.sleep(playhead - now - LEAD_S)
                continue
            frame = bytes(self._buffer[:FRAME_BYTES])
            del self._buffer[:FRAME_BYTES]
            playhead += len(frame) / 2 / SAMPLE_RATE
            try:
                await self._emit(frame)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - sink failure must not stop the voice
                log.warning("voice audio callback failed: %s", type(exc).__name__)

    async def close(self) -> None:
        task, self._task = self._task, None
        if task is not None:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        self._buffer.clear()
