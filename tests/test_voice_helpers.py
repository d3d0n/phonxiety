"""Transcript and signaling helpers of the Codex voice session."""

from __future__ import annotations

from phonxiety.voice import (
    _TranscriptAssembler,
    chunk_utf8,
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

