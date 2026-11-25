from __future__ import annotations

import math
import shutil
import struct
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path
from unittest import mock

from phonxiety.recording import CallRecorder, compress

FRAME = b"\x10\x00" * 960


class CallRecorderTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_a_write_failure_stops_recording_without_raising(self) -> None:
        recorder = CallRecorder(self.root, "call")
        recorder.remote(FRAME)
        with mock.patch.object(recorder._agent, "write", side_effect=OSError("disk full")):
            recorder.agent(FRAME)
        recorder.remote(FRAME)  # stopped: no further writes, no exception
        recording = recorder.close()
        self.assertIn("disk full", recording["error"])
        with wave.open(str(self.root / "call.wav")) as audio:
            self.assertEqual(audio.getnframes(), 960)

    def test_a_failed_export_keeps_both_sides_for_recovery(self) -> None:
        recorder = CallRecorder(self.root, "call")
        recorder.remote(FRAME)
        recorder.agent(FRAME)
        with mock.patch("phonxiety.recording.wave.open", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                recorder.close()
        self.assertEqual(sorted(path.name for path in self.root.iterdir()), ["call.agent.part", "call.remote.part"])
        self.assertEqual((self.root / "call.remote.part").read_bytes(), FRAME)


class CompressTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "needs ffmpeg")
    def test_the_shareable_copy_is_mono_and_drops_the_long_pause(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            wav = Path(directory) / "call.wav"
            tone = [int(8000 * math.sin(2 * math.pi * 300 * n / 48_000)) for n in range(48_000)]
            samples = tone + [0] * 96_000 + tone  # 1 s speech, 2 s silence, 1 s speech
            with wave.open(str(wav), "wb") as out:
                out.setnchannels(2)
                out.setsampwidth(2)
                out.setframerate(48_000)
                out.writeframes(b"".join(struct.pack("<hh", s, 0) for s in samples))
            result = compress(wav)
            probe = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "stream=channels:format=duration", "-of", "csv=p=0",
                 str(Path(directory) / result["file"])],
                capture_output=True, text=True, check=True,
            ).stdout.split()
        self.assertEqual(int(probe[0]), 1)
        self.assertLess(float(probe[1]), 3.0)  # the 2 s pause is gone

    def test_without_ffmpeg_the_call_keeps_only_the_wav(self) -> None:
        with mock.patch("phonxiety.recording.shutil.which", return_value=None):
            self.assertEqual(compress(Path("call.wav")), {"file": None, "error": "ffmpeg_not_found"})


if __name__ == "__main__":
    unittest.main()
