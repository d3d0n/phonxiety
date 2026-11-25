"""The shareable copy of a call: both sides in one channel, long pauses cut, even loudness.

One FFmpeg filter graph inside PyAV (pan, silenceremove, loudnorm) feeding the AAC encoder
and an .m4a muxer: no external ffmpeg binary, no subprocess.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

import av

log = logging.getLogger(__name__)
SHARE_RATE = 24_000
SHARE_BITRATE = 48_000
# Mono mix of both sides, pauses over 0.5 s cut, EBU R128 loudness: one voice level throughout.
_FILTERS: tuple[tuple[str, str], ...] = (
    ("pan", "mono|c0=0.5*c0+0.5*c1"),
    ("silenceremove", "start_periods=1:start_threshold=-45dB:stop_periods=-1:stop_duration=0.5:stop_threshold=-45dB"),
    ("loudnorm", "I=-16:TP=-1.5:LRA=11"),
    ("aresample", str(SHARE_RATE)),
    ("aformat", f"sample_fmts=fltp:channel_layouts=mono:sample_rates={SHARE_RATE}"),
)


def make_shareable(wav: Path) -> dict[str, Any]:
    """Write `<call_id>.m4a` next to the WAV; its properties, or why it could not be made."""
    target = wav.with_suffix(".m4a")
    temporary = wav.with_name(f".{wav.stem}.part.m4a")
    try:
        _encode(wav, temporary)
        os.chmod(temporary, 0o600)
        os.replace(temporary, target)
    except (OSError, av.FFmpegError, ValueError) as exc:
        temporary.unlink(missing_ok=True)
        log.warning("Shareable copy of %s failed: %s", wav.name, exc)
        return {"file": None, "error": f"share_failed: {exc}"}
    return {"file": target.name, "format": "m4a aac mono, pauses removed", "bytes": target.stat().st_size}


def _encode(wav: Path, target: Path) -> None:
    with av.open(str(wav)) as source, av.open(str(target), "w", format="ipod") as output:
        audio = source.streams.audio[0]
        graph = av.filter.Graph()
        nodes = [graph.add_abuffer(template=audio)]
        nodes += [graph.add(name, arguments) for name, arguments in _FILTERS]
        nodes.append(graph.add("abuffersink"))
        for upstream, downstream in zip(nodes, nodes[1:]):
            upstream.link_to(downstream)
        graph.configure()
        stream = output.add_stream("aac", rate=SHARE_RATE, layout="mono")
        stream.bit_rate = SHARE_BITRATE

        def drain() -> None:
            while True:
                try:
                    frame = nodes[-1].pull()
                except (BlockingIOError, av.error.EOFError):
                    return
                frame.pts = None  # the encoder numbers the trimmed timeline itself
                output.mux(stream.encode(frame))

        for frame in source.decode(audio):
            nodes[0].push(frame)
            drain()
        nodes[0].push(None)
        drain()
        output.mux(stream.encode(None))
