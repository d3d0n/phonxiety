"""Paced speech output for websocket voice backends (Gemini Live, Deepgram Voice Agent).

Those APIs deliver speech in bursts, much faster than it plays. The phone link and the
phone's ring buffer hold well under a second, so output is paced here: 20 ms frames of
48 kHz mono PCM leave on the wall clock, and a barge-in drops what has not played yet.
Frames come out of an `av.AudioFifo`; the pacing loop is ours because Pipecat and LiveKit
pace only inside their own transports (see docs/audio-research.md).
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable

import av

from .format import FRAME_SAMPLES, SAMPLE_RATE, frame_bytes, mono_frame, whole_samples

log = logging.getLogger(__name__)
LEAD_S = 0.06  # keep this much audio ahead of the playhead to ride out network jitter


class PacedOutput:
    """Hands speech to the phone at playback speed, one 20 ms frame at a time."""

    def __init__(
        self,
        emit: Callable[[bytes], Awaitable[None]],
        on_drained: Callable[[], Awaitable[None]] | None = None,
    ) -> None:
        self._emit = emit
        self._on_drained = on_drained
        self._fifo = av.AudioFifo()
        self._pending = b""
        self._ready = asyncio.Event()
        self._task: asyncio.Task[None] | None = None
        self._notify_drain = False

    def start(self) -> None:
        self._task = asyncio.create_task(self._run(), name="voice-paced-output")

    def feed(self, pcm: bytes) -> None:
        whole, self._pending = whole_samples(self._pending, pcm)
        if whole:
            self._fifo.write(mono_frame(whole))
            self._ready.set()

    def clear(self) -> None:
        """Barge-in: drop everything not yet handed to the phone."""
        self._fifo = av.AudioFifo()
        self._pending = b""
        self._notify_drain = False

    def drain_then_notify(self) -> None:
        """Call `on_drained` once everything fed so far has been emitted."""
        self._notify_drain = True
        self._ready.set()

    @property
    def pending(self) -> bool:
        return self._fifo.samples > 0

    async def _run(self) -> None:
        playhead: float | None = None
        while True:
            if not self._fifo.samples:
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
            frame = self._fifo.read(min(FRAME_SAMPLES, self._fifo.samples))
            playhead += frame.samples / SAMPLE_RATE
            try:
                await self._emit(frame_bytes(frame))
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - sink failure must not stop the voice
                log.warning("voice audio callback failed: %s", type(exc).__name__)

    async def close(self) -> None:
        task, self._task = self._task, None
        if task is not None:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        self.clear()
