"""Call audio formats on the phone link.

`pcm`: 48 kHz mono s16le, ~768 kbit/s per direction.
`opus`: the same audio as one 20 ms Opus packet per frame, ~24-64 kbit/s.
"""

from __future__ import annotations

import av

SAMPLE_RATE = 48_000
FRAME_SAMPLES = 960  # 20 ms
FRAME_BYTES = FRAME_SAMPLES * 2
CODECS = ("pcm", "opus")
DEFAULT_OPUS_BITRATE = 32_000
MIN_OPUS_BITRATE = 6_000
MAX_OPUS_BITRATE = 128_000


class OpusEncoder:
    """PCM in any 20 ms multiple, one Opus packet per 20 ms out."""

    def __init__(self, bitrate: int) -> None:
        self._context = av.CodecContext.create("libopus", "w")
        self._context.sample_rate = SAMPLE_RATE
        self._context.layout = "mono"
        self._context.format = "s16"
        self._context.bit_rate = bitrate
        self._context.options = {"application": "voip", "frame_duration": "20"}
        self._pts = 0
        self._pending = b""

    def encode(self, pcm: bytes) -> list[bytes]:
        """Encode whole frames; a partial tail waits for the next call."""
        data = self._pending + pcm
        whole = len(data) - len(data) % FRAME_BYTES
        self._pending = data[whole:]
        packets: list[bytes] = []
        for offset in range(0, whole, FRAME_BYTES):
            frame = av.AudioFrame(format="s16", layout="mono", samples=FRAME_SAMPLES)
            frame.planes[0].update(data[offset:offset + FRAME_BYTES])
            frame.sample_rate = SAMPLE_RATE
            frame.pts = self._pts
            self._pts += FRAME_SAMPLES
            packets.extend(bytes(packet) for packet in self._context.encode(frame))
        return packets


class OpusDecoder:
    """One Opus packet in, its 48 kHz mono s16le PCM out."""

    def __init__(self) -> None:
        self._context = av.CodecContext.create("libopus", "r")
        self._context.sample_rate = SAMPLE_RATE
        self._context.layout = "mono"

    def decode(self, packet: bytes) -> bytes:
        pcm = bytearray()
        for frame in self._context.decode(av.Packet(packet)):
            if frame.format.name != "s16" or frame.layout.nb_channels != 1:
                raise ValueError(f"unexpected Opus output {frame.format.name}/{frame.layout.name}")
            pcm += bytes(frame.planes[0])[: frame.samples * 2]
        return bytes(pcm)
