from __future__ import annotations

import tempfile
import unittest
import wave
from pathlib import Path
from unittest import mock

from phonxiety.recording import CallRecorder

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


if __name__ == "__main__":
    unittest.main()
