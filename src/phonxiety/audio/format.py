"""The one audio format the daemon works in: 48 kHz mono s16le in 20 ms frames."""

from __future__ import annotations

from av import AudioFrame

SAMPLE_RATE = 48_000
BYTES_PER_SAMPLE = 2
FRAME_SAMPLES = 960  # 20 ms
FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE


def mono_frame(pcm: bytes, sample_rate: int = SAMPLE_RATE) -> AudioFrame:
    """A PyAV frame holding mono s16le `pcm` (whole samples only)."""
    frame = AudioFrame(format="s16", layout="mono", samples=len(pcm) // BYTES_PER_SAMPLE)
    frame.planes[0].update(pcm)
    frame.sample_rate = sample_rate
    return frame


def frame_bytes(frame: AudioFrame) -> bytes:
    """The valid samples of a packed s16 frame, without plane padding."""
    return bytes(frame.planes[0])[: frame.samples * len(frame.layout.channels) * BYTES_PER_SAMPLE]


def whole_samples(pending: bytes, pcm: bytes) -> tuple[bytes, bytes]:
    """`pending + pcm` split into whole samples and a dangling byte kept for the next chunk."""
    data = pending + pcm
    usable = len(data) - len(data) % BYTES_PER_SAMPLE
    return data[:usable], data[usable:]
