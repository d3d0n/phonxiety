"""Stereo recording of a call: the other party on the left, the agent on the right.

Each side is written to its own raw file at its position on the call clock; at the end both
are turned into one planar stereo frame stream, packed by libswresample and muxed as a
`pcm_s16le` WAV by PyAV. No library knows our call clock, so placing the samples stays ours.
"""

from __future__ import annotations

import logging
import os
import time
from pathlib import Path
from typing import Any, BinaryIO

import av
from av import AudioFrame, AudioResampler

from .format import BYTES_PER_SAMPLE, SAMPLE_RATE

log = logging.getLogger(__name__)
CHUNK_SAMPLES = SAMPLE_RATE  # one second per export step


class CallRecorder:
    """Writes each side to its own raw file at its position on the call clock, then exports.

    The phone delivers the downlink as a continuous stream, so it is written back to back
    from where its first frame landed. Agent speech arrives in bursts, faster than it plays,
    so each chunk goes at the later of "now" and the end of the previous chunk: that is when
    the phone plays it.
    """

    def __init__(self, directory: Path, call_id: str) -> None:
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = directory / f"{call_id}.wav"
        self._remote = open(directory / f"{call_id}.remote.part", "w+b")
        self._agent = open(directory / f"{call_id}.agent.part", "w+b")
        self._started = time.monotonic()
        self._remote_end: int | None = None
        self._agent_end = 0
        self.closed = False
        # First failure while writing; the recorder then stops so the call itself goes on.
        self.error: str | None = None

    def _now(self) -> int:
        return int((time.monotonic() - self._started) * SAMPLE_RATE)

    def remote(self, pcm: bytes) -> None:
        if self.closed or self.error:
            return
        samples = len(pcm) // BYTES_PER_SAMPLE
        if self._remote_end is None:
            self._remote_end = max(0, self._now() - samples)
        if self._write(self._remote, self._remote_end, pcm):
            self._remote_end += samples

    def agent(self, pcm: bytes) -> None:
        if self.closed or self.error:
            return
        position = max(self._agent_end, self._now())
        if self._write(self._agent, position, pcm):
            self._agent_end = position + len(pcm) // BYTES_PER_SAMPLE

    def _write(self, stream: BinaryIO, position: int, pcm: bytes) -> bool:
        try:
            # Seeking past the end leaves a hole that reads back as silence.
            stream.seek(position * BYTES_PER_SAMPLE)
            stream.write(pcm)
            return True
        except OSError as exc:
            self.error = f"recording_write_failed: {exc}"
            log.warning("Recording stopped: %s", self.error)
            return False

    def close(self) -> dict[str, Any] | None:
        """Export the WAV; the recording's properties, or None if nothing was recorded.

        The WAV is published only once complete, and the raw per-side files are removed only
        then: if writing it fails they stay next to it for recovery and the error propagates.
        """
        if self.closed:
            return None
        self.closed = True
        total = max(self._remote_end or 0, self._agent_end)
        temporary = self.path.with_name(f".{self.path.stem}.part.wav")
        try:
            if total:
                _export(self._remote, self._agent, total, temporary)
                os.chmod(temporary, 0o600)
                os.replace(temporary, self.path)
        except BaseException:
            temporary.unlink(missing_ok=True)
            for stream in (self._remote, self._agent):
                stream.close()
            raise
        for stream in (self._remote, self._agent):
            stream.close()
            Path(stream.name).unlink(missing_ok=True)
        if not total:
            return None
        return {
            "file": self.path.name,
            "format": "wav s16le stereo",
            "sample_rate": SAMPLE_RATE,
            "channels": ["remote", "agent"],
            "duration_s": round(total / SAMPLE_RATE, 2),
            "error": self.error,
        }


def _export(remote: BinaryIO, agent: BinaryIO, total: int, path: Path) -> None:
    """Both sides as one stereo WAV: planar frames in, swresample packs, PyAV muxes."""
    pack = AudioResampler(format="s16", layout="stereo", rate=SAMPLE_RATE)
    with av.open(str(path), "w", format="wav") as container:
        stream = container.add_stream("pcm_s16le", rate=SAMPLE_RATE, layout="stereo")
        for side in (remote, agent):
            side.seek(0)
        for start in range(0, total, CHUNK_SAMPLES):
            count = min(CHUNK_SAMPLES, total - start)
            frame = AudioFrame(format="s16p", layout="stereo", samples=count)
            frame.planes[0].update(_samples(remote, count))
            frame.planes[1].update(_samples(agent, count))
            frame.sample_rate = SAMPLE_RATE
            for packed in pack.resample(frame):
                container.mux(stream.encode(packed))
        container.mux(stream.encode(None))


def _samples(stream: BinaryIO, count: int) -> bytes:
    """`count` samples from the stream's position; past its end, silence."""
    data = stream.read(count * BYTES_PER_SAMPLE)
    return data + bytes(count * BYTES_PER_SAMPLE - len(data))
