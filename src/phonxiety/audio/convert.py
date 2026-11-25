"""Sample-rate conversion and downmix through libswresample (PyAV)."""

from __future__ import annotations

from av import AudioFrame, AudioResampler

from .format import SAMPLE_RATE, frame_bytes, mono_frame, whole_samples


class Resampler:
    """Mono s16le PCM from one rate to another, streaming; a dangling byte waits."""

    def __init__(self, source_rate: int, target_rate: int) -> None:
        self._source_rate = source_rate
        self._resampler = AudioResampler(format="s16", layout="mono", rate=target_rate)
        self._pending = b""

    def convert(self, pcm: bytes) -> bytes:
        whole, self._pending = whole_samples(self._pending, pcm)
        if not whole:
            return b""
        return b"".join(frame_bytes(out) for out in self._resampler.resample(mono_frame(whole, self._source_rate)))


class ToMono48k:
    """Any decoded frame (any layout, rate or sample format) as 48 kHz mono s16le.

    swresample's stereo-to-mono matrix is (L+R)/2, the level the phone expects. A resampler
    is bound to its input format, so a change of layout, rate or format starts a new one.
    """

    def __init__(self) -> None:
        self._resampler: AudioResampler | None = None
        self._input: tuple[str, str, int] | None = None

    def convert(self, frame: AudioFrame) -> bytes:
        source = (frame.layout.name, frame.format.name, frame.sample_rate)
        if self._resampler is None or source != self._input:
            self._resampler = AudioResampler(format="s16", layout="mono", rate=SAMPLE_RATE)
            self._input = source
        return b"".join(frame_bytes(out) for out in self._resampler.resample(frame))
