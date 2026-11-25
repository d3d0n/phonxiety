"""Per-call archive: a stereo recording of both sides and a JSON record of the call.

Files live in ``<state_dir>/calls``:

- ``<call_id>.wav``: 48 kHz s16le stereo, left = the other party (phone downlink),
  right = the agent as sent to the phone, aligned on one clock from audio start.
- ``<call_id>.json``: request, timings, outcomes, result fields, full transcript and
  the recording's properties. Written for every call once its result is final.
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
import time
import wave
from array import array
from pathlib import Path
from typing import Any, BinaryIO

from .codec import SAMPLE_RATE

log = logging.getLogger(__name__)
CHUNK_SAMPLES = SAMPLE_RATE  # one second per interleave step


class CallRecorder:
    """Writes each side to its own raw file at its position on the call clock, then interleaves.

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
        samples = len(pcm) // 2
        if self._remote_end is None:
            self._remote_end = max(0, self._now() - samples)
        if self._write(self._remote, self._remote_end, pcm):
            self._remote_end += samples

    def agent(self, pcm: bytes) -> None:
        if self.closed or self.error:
            return
        position = max(self._agent_end, self._now())
        if self._write(self._agent, position, pcm):
            self._agent_end = position + len(pcm) // 2

    def _write(self, stream: BinaryIO, position: int, pcm: bytes) -> bool:
        try:
            # Seeking past the end leaves a hole that reads back as silence.
            stream.seek(position * 2)
            stream.write(pcm)
            return True
        except OSError as exc:
            self.error = f"recording_write_failed: {exc}"
            log.warning("Recording stopped: %s", self.error)
            return False

    def close(self) -> dict[str, Any] | None:
        """Interleave into the WAV; the recording's properties, or None if nothing was recorded.

        The WAV is published only once complete, and the raw per-side files are removed only
        then: if writing it fails they stay next to it for recovery and the error propagates.
        """
        if self.closed:
            return None
        self.closed = True
        total = max(self._remote_end or 0, self._agent_end)
        temporary = self.path.with_name(self.path.name + ".part")
        try:
            if total:
                with wave.open(str(temporary), "wb") as out:
                    out.setnchannels(2)
                    out.setsampwidth(2)
                    out.setframerate(SAMPLE_RATE)
                    for stream in (self._remote, self._agent):
                        stream.seek(0)
                    for start in range(0, total, CHUNK_SAMPLES):
                        count = min(CHUNK_SAMPLES, total - start)
                        frames = array("h", bytes(count * 4))
                        frames[0::2] = _samples(self._remote, count)
                        frames[1::2] = _samples(self._agent, count)
                        out.writeframes(frames.tobytes())
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


def _samples(stream: BinaryIO, count: int) -> array:
    """`count` samples from the stream's position; past its end, silence."""
    data = stream.read(count * 2)
    return array("h", data + bytes(count * 2 - len(data)))


def write_call_record(directory: Path, record: dict[str, Any]) -> Path:
    """Atomically write `<call_id>.json` with owner-only access."""
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / f"{record['call_id']}.json"
    fd, temporary = tempfile.mkstemp(prefix=".call-", suffix=".json", dir=directory)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(record, stream, ensure_ascii=False, indent=2)
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise
    return path
