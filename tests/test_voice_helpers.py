"""Pure audio/transcript/signaling helpers of the voice session."""

from __future__ import annotations

import asyncio

from av import AudioFrame

from phonxiety.voice import (
    BYTES_PER_FRAME,
    SAMPLES_PER_FRAME,
    PcmSourceTrack,
    _TranscriptAssembler,
    chunk_utf8,
    downmix_frame,
    parse_live_call_id,
)


def test_chunk_utf8_limits_encoded_bytes() -> None:
    text = "Привет, мир! " * 80
    chunks = chunk_utf8(text)
    assert "".join(chunks) == text
    assert all(len(chunk.encode("utf-8")) <= 500 for chunk in chunks)


def test_chunk_utf8_handles_single_wide_character() -> None:
    chunks = chunk_utf8("😀" * 200)
    assert "".join(chunks) == "😀" * 200
    assert all(len(chunk.encode("utf-8")) <= 500 for chunk in chunks)


def test_parse_live_call_id() -> None:
    assert parse_live_call_id("https://chatgpt.com/backend-api/codex/live/rtc_abc-1?x=1") == "rtc_abc-1"
    assert parse_live_call_id("/v1/live/rtc_abc?foo=bar") == "rtc_abc"
    assert parse_live_call_id(None) is None
    assert parse_live_call_id("https://example.com/no-id") is None


def test_transcript_assembler_cumulative_and_final_without_duplicate() -> None:
    assembler = _TranscriptAssembler()
    assert assembler.added("assistant", "При") == ("assistant", "При", False)
    assert assembler.added("assistant", "Привет") == (
        "assistant",
        "Привет",
        False,
    )
    # A duplicate delta of already-seen text is ignored.
    assert assembler.added("assistant", "вет") is None
    assert assembler.finished("assistant", "Привет") == (
        "assistant",
        "Привет",
        True,
    )
    # The same final text is not re-emitted.
    assert assembler.finished("assistant", "Привет") is None
    # A new turn starts after the final.
    assert assembler.added("assistant", "Дальше") == (
        "assistant",
        "Дальше",
        False,
    )


def test_transcript_assembler_delta_mode() -> None:
    assembler = _TranscriptAssembler()
    assert assembler.added("user", "one ") == ("user", "one ", False)
    assert assembler.added("user", "two") == ("user", "one two", False)
    assert assembler.finished("user", "one two") == ("user", "one two", True)


def test_identical_words_in_consecutive_turns_remain_distinct() -> None:
    assembler = _TranscriptAssembler()
    assembler.added("user", "Да")
    assert assembler.finished("user", "Да") == ("user", "Да", True)
    assert assembler.added("user", "Да") == ("user", "Да", False)
    assert assembler.finished("user", "Да") == ("user", "Да", True)


def test_final_transcript_can_shorten_an_incorrect_partial() -> None:
    assembler = _TranscriptAssembler()
    assembler.added("user", "Friday at four or five")
    assert assembler.finished("user", "Friday at four") == ("user", "Friday at four", True)


def test_downmix_stereo_frame_to_mono_pcm() -> None:
    frame = AudioFrame(format="s16", layout="stereo", samples=4)
    frame.sample_rate = 48000
    frame.pts = 0
    samples = [100, 300, -100, 100, 5, 6, -7, -9]
    frame.planes[0].update(
        b"".join(int(value).to_bytes(2, "little", signed=True) for value in samples)
    )
    pcm = downmix_frame(frame)
    values = [
        int.from_bytes(pcm[index : index + 2], "little", signed=True)
        for index in range(0, len(pcm), 2)
    ]
    assert values == [200, 0, 5, -8]


def test_pcm_source_pads_silence_and_advances_pts() -> None:
    async def run() -> None:
        track = PcmSourceTrack()
        first = await track.recv()
        second = await track.recv()
        assert first.samples == SAMPLES_PER_FRAME
        assert first.pts == 0
        assert second.pts == SAMPLES_PER_FRAME
        assert bytes(first.planes[0]) == b"\x00" * BYTES_PER_FRAME

    asyncio.run(run())


def test_pcm_source_preserves_a_short_tail_and_pads_its_frame() -> None:
    async def run() -> None:
        track = PcmSourceTrack()
        payload = bytes(range(256)) * 8
        track.push(payload)
        first = await track.recv()
        second = await track.recv()
        assert bytes(first.planes[0]) == payload[:BYTES_PER_FRAME]
        assert bytes(second.planes[0]) == payload[BYTES_PER_FRAME:].ljust(BYTES_PER_FRAME, b"\x00")
        assert track.buffered_bytes == 0

    asyncio.run(run())


def test_single_oversized_audio_chunk_keeps_only_bounded_recent_audio() -> None:
    async def run() -> None:
        track = PcmSourceTrack(max_bytes=BYTES_PER_FRAME)
        old_audio = b"\x01\x00" * SAMPLES_PER_FRAME
        recent_audio = b"\x02\x00" * SAMPLES_PER_FRAME
        track.push(old_audio + recent_audio)
        assert track.buffered_bytes == BYTES_PER_FRAME
        assert track.dropped_bytes == BYTES_PER_FRAME
        frame = await track.recv()
        assert bytes(frame.planes[0]) == recent_audio

    asyncio.run(run())
