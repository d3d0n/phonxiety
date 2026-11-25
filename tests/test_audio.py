"""The audio package: formats, conversion, pacing, the WebRTC source, recording and sharing."""

from __future__ import annotations

import asyncio
import math
import struct
import tempfile
import time
import unittest
import wave
from pathlib import Path
from unittest import mock

import av
from av import AudioFrame

from phonxiety.audio.convert import Resampler, ToMono48k
from phonxiety.audio.format import FRAME_BYTES, FRAME_SAMPLES
from phonxiety.audio.opus import OpusDecoder, OpusEncoder
from phonxiety.audio.pacing import PacedOutput
from phonxiety.audio.recording import CallRecorder
from phonxiety.audio.share import make_shareable
from phonxiety.audio.webrtc import PcmSourceTrack

FRAME = b"\x10\x00" * 960


def _samples(pcm: bytes) -> list[int]:
    return list(struct.unpack(f"<{len(pcm) // 2}h", pcm))


class ConvertTests(unittest.TestCase):
    def test_stereo_is_downmixed_to_the_mean_of_both_sides(self) -> None:
        frame = AudioFrame(format="s16", layout="stereo", samples=4)
        frame.sample_rate = 48000
        frame.planes[0].update(struct.pack("<8h", 100, 300, -100, 100, 5, 6, -7, -9))
        # The mean of both sides; swresample rounds (5+6)/2 to 6 where integer division gave 5.
        self.assertEqual(_samples(ToMono48k().convert(frame)), [200, 0, 6, -8])

    def test_a_layout_change_mid_stream_is_followed(self) -> None:
        to_mono = ToMono48k()
        stereo = AudioFrame(format="s16", layout="stereo", samples=2)
        stereo.sample_rate = 48000
        stereo.planes[0].update(struct.pack("<4h", 10, 30, 10, 30))
        mono = AudioFrame(format="s16", layout="mono", samples=2)
        mono.sample_rate = 48000
        mono.planes[0].update(struct.pack("<2h", 7, 7))
        self.assertEqual(_samples(to_mono.convert(stereo)), [20, 20])
        self.assertEqual(_samples(to_mono.convert(mono)), [7, 7])

    def test_rates_convert_by_their_ratio(self) -> None:
        resampler = Resampler(24_000, 48_000)
        out = b"".join(resampler.convert(bytes(480 * 2)) for _ in range(50))  # one second at 24 kHz
        self.assertAlmostEqual(len(out) / 2 / 48_000, 1.0, delta=0.05)


class OpusTests(unittest.TestCase):
    def test_a_partial_frame_waits_for_the_rest(self) -> None:
        encoder, decoder = OpusEncoder(32_000), OpusDecoder()
        self.assertEqual(encoder.encode(FRAME[:1000]), [])
        packets = encoder.encode(FRAME[1000:] + FRAME)
        self.assertEqual(len(packets), 2)
        self.assertEqual([len(decoder.decode(packet)) for packet in packets], [FRAME_BYTES, FRAME_BYTES])

    def test_a_chunk_split_inside_a_sample_loses_nothing(self) -> None:
        encoder = OpusEncoder(32_000)
        self.assertEqual(encoder.encode(FRAME[:1001]), [])
        self.assertEqual(len(encoder.encode(FRAME[1001:])), 1)


class _Emitted:
    """Frames as they leave the pacer, with an event per frame so a test waits instead of sleeping."""

    def __init__(self) -> None:
        self.frames: list[tuple[float, bytes]] = []
        self.arrived = asyncio.Event()

    async def __call__(self, frame: bytes) -> None:
        self.frames.append((time.monotonic(), frame))
        self.arrived.set()

    async def until(self, count: int) -> None:
        async with asyncio.timeout(5):
            while len(self.frames) < count:
                self.arrived.clear()
                await self.arrived.wait()


class PacedOutputTests(unittest.IsolatedAsyncioTestCase):
    async def test_a_burst_plays_at_speech_speed_and_a_barge_in_drops_the_rest(self) -> None:
        emitted = _Emitted()
        drained = asyncio.Event()

        async def on_drained() -> None:
            drained.set()

        output = PacedOutput(emitted, on_drained=on_drained)
        output.start()
        output.feed(bytes(FRAME_BYTES * 50))  # one second of speech in one burst
        # Only the pacer's own wall clock moves here: wait for the frames it handed over.
        # The lead rides out jitter, so the first frames leave together; 14 frames prove pacing.
        await emitted.until(14)
        output.clear()
        output.drain_then_notify()
        async with asyncio.timeout(2):
            await drained.wait()  # the pacer saw the cleared buffer and stops there
        frames = emitted.frames
        await asyncio.sleep(0)  # let the pacer return from the notification
        self.assertFalse(output._task.done())  # the notification did not crash the pacer
        await output.close()
        # ~0.3 s of 20 ms frames plus the jitter lead, not the whole second.
        self.assertTrue(12 <= len(frames) <= 22, len(frames))
        self.assertGreater(frames[-1][0] - frames[0][0], 0.2)
        self.assertEqual({len(frame) for _, frame in frames}, {FRAME_BYTES})
        self.assertFalse(output.pending)

    async def test_a_chunk_split_inside_a_sample_keeps_the_byte(self) -> None:
        emitted = _Emitted()
        output = PacedOutput(emitted)
        output.start()
        output.feed(FRAME[:1001])
        output.feed(FRAME[1001:])
        await emitted.until(1)  # the pacer handed the whole frame over
        await output.close()
        self.assertEqual(b"".join(frame for _, frame in emitted.frames), FRAME)


class PcmSourceTrackTests(unittest.IsolatedAsyncioTestCase):
    async def test_silence_is_padded_and_timestamps_advance(self) -> None:
        track = PcmSourceTrack()
        first = await track.recv()
        second = await track.recv()
        self.assertEqual((first.samples, first.pts, second.pts), (FRAME_SAMPLES, 0, FRAME_SAMPLES))
        self.assertEqual(bytes(first.planes[0]), b"\x00" * FRAME_BYTES)

    async def test_a_short_tail_is_kept_and_padded(self) -> None:
        track = PcmSourceTrack()
        payload = bytes(range(256)) * 8
        track.push(payload)
        first = await track.recv()
        second = await track.recv()
        self.assertEqual(bytes(first.planes[0]), payload[:FRAME_BYTES])
        self.assertEqual(bytes(second.planes[0]), payload[FRAME_BYTES:].ljust(FRAME_BYTES, b"\x00"))
        self.assertEqual(track.buffered_bytes, 0)

    async def test_an_oversized_chunk_keeps_only_the_most_recent_audio(self) -> None:
        track = PcmSourceTrack(max_bytes=FRAME_BYTES)
        old_audio = b"\x01\x00" * FRAME_SAMPLES
        recent_audio = b"\x02\x00" * FRAME_SAMPLES
        track.push(old_audio + recent_audio)
        self.assertEqual((track.buffered_bytes, track.dropped_bytes), (FRAME_BYTES, FRAME_BYTES))
        frame = await track.recv()
        self.assertEqual(bytes(frame.planes[0]), recent_audio)


class CallRecorderTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_both_sides_land_in_their_own_channel(self) -> None:
        recorder = CallRecorder(self.root, "call")
        recorder.remote(b"\x10\x00" * 960)
        recorder.agent(b"\xe8\x03" * 960)
        recording = recorder.close()
        with wave.open(str(self.root / recording["file"])) as audio:
            self.assertEqual((audio.getnchannels(), audio.getframerate()), (2, 48_000))
            frames = _samples(audio.readframes(audio.getnframes()))
        self.assertEqual(frames[0::2].count(16), 960)
        self.assertEqual(frames[1::2].count(1000), 960)
        self.assertEqual(sorted(path.name for path in self.root.iterdir()), ["call.wav"])

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
        with mock.patch("phonxiety.audio.recording._export", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                recorder.close()
        self.assertEqual(sorted(path.name for path in self.root.iterdir()), ["call.agent.part", "call.remote.part"])
        self.assertEqual((self.root / "call.remote.part").read_bytes(), FRAME)


class ShareTests(unittest.TestCase):
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
            result = make_shareable(wav)
            with av.open(str(Path(directory) / result["file"])) as shared:
                channels = shared.streams.audio[0].channels
                duration = float(shared.duration) / av.time_base
        self.assertEqual(channels, 1)
        self.assertLess(duration, 3.0)  # the 2 s pause is gone

    def test_an_unreadable_recording_reports_why(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            wav = Path(directory) / "call.wav"
            wav.write_bytes(b"not audio")
            result = make_shareable(wav)
            self.assertIsNone(result["file"])
            self.assertIn("share_failed", result["error"])
            self.assertEqual([path.name for path in Path(directory).iterdir()], ["call.wav"])


if __name__ == "__main__":
    unittest.main()
