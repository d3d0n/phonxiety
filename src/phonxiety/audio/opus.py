"""Call audio formats on the phone link and the Opus codec for them.

`pcm`: 48 kHz mono s16le, ~768 kbit/s per direction.
`opus`: the same audio as one 20 ms Opus packet per frame, ~24-64 kbit/s.
"""

from __future__ import annotations

import av

from .format import FRAME_SAMPLES, SAMPLE_RATE, frame_bytes, mono_frame, whole_samples

CODECS = ("pcm", "opus")
DEFAULT_OPUS_BITRATE = 32_000
MIN_OPUS_BITRATE = 6_000
MAX_OPUS_BITRATE = 128_000


class OpusEncoder:
    """PCM in any amount, one Opus packet per whole 20 ms out; a partial tail waits."""

    def __init__(self, bitrate: int) -> None:
        self._context = av.CodecContext.create("libopus", "w")
        self._context.sample_rate = SAMPLE_RATE
        self._context.layout = "mono"
        self._context.format = "s16"
        self._context.bit_rate = bitrate
        self._context.options = {"application": "voip", "frame_duration": "20"}
        self._fifo = av.AudioFifo()
        self._pending = b""
        self._pts = 0

    def encode(self, pcm: bytes) -> list[bytes]:
        whole, self._pending = whole_samples(self._pending, pcm)
        if whole:
            self._fifo.write(mono_frame(whole))
        packets: list[bytes] = []
        while (frame := self._fifo.read(FRAME_SAMPLES)) is not None:
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
            pcm += frame_bytes(frame)
        return bytes(pcm)
