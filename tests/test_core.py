from __future__ import annotations

import asyncio
import contextlib
import json
import tempfile
import unittest
import wave
from array import array
from pathlib import Path
from typing import Any
from unittest import mock

from pydantic import ValidationError

from phonxiety.controller import CallController
from phonxiety.models import CallRequest, CallState
from phonxiety.store import CallStore


def request(key: str = "request-a") -> CallRequest:
    return CallRequest(request_id=key, destination="+12025550123", goal="Confirm a Friday appointment")


class DestinationTests(unittest.TestCase):
    def test_service_short_numbers_are_allowed_and_emergency_numbers_refused(self) -> None:
        for allowed in ("1099", "1009", "+998712001515"):
            self.assertEqual(CallRequest(request_id="r", destination=allowed, goal="g").destination, allowed)
        for refused in ("112", "103", "1050", "911", "12", "*100#", "1234567"):
            with self.assertRaises(ValidationError, msg=refused):
                CallRequest(request_id="r", destination=refused, goal="g")


class StoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.store = CallStore(Path(self.directory.name) / "calls.db")

    def tearDown(self) -> None:
        self.store.close()
        self.directory.cleanup()

    def test_retry_precedes_busy_and_rejects_changed_request(self) -> None:
        call_id, created = self.store.reserve(request())
        self.assertTrue(created)
        self.assertEqual(self.store.reserve(request()), (call_id, False))
        with self.assertRaisesRegex(RuntimeError, "line_busy"):
            self.store.reserve(request("another-request"))
        with self.assertRaisesRegex(ValueError, "request_conflict"):
            self.store.reserve(request().model_copy(update={"goal": "Cancel the appointment"}))

    def test_cancel_before_dial_excludes_later_submission(self) -> None:
        call_id, _ = self.store.reserve(request())
        self.assertTrue(self.store.cancel_preparing(call_id))
        self.assertFalse(self.store.submit_dial(call_id))
        snapshot = self.store.snapshot(call_id)
        self.assertEqual((snapshot["state"], snapshot["call_outcome"]), ("cancelled", "not_started"))

    def test_partial_revision_does_not_advance_final_cursor(self) -> None:
        call_id, _ = self.store.reserve(request())
        self.store.append_transcript(call_id, "user", "Friday at three", False)
        partial = self.store.snapshot(call_id)
        self.assertEqual(partial["next_cursor"], 0)
        self.assertEqual(partial["transcript"], [])
        self.assertEqual(partial["current_partials"][0]["text"], "Friday at three")
        self.store.append_transcript(call_id, "user", "Friday at four", True)
        final = self.store.snapshot(call_id, partial["next_cursor"])
        self.assertEqual([segment["text"] for segment in final["transcript"]], ["Friday at four"])
        self.assertEqual(final["current_partials"], [])
        self.assertEqual(self.store.snapshot(call_id, final["next_cursor"])["transcript"], [])
        self.assertEqual(self.store.snapshot(call_id, 0)["transcript"], final["transcript"])

    def test_unknown_phone_outcome_is_separate_from_result_readiness(self) -> None:
        call_id, _ = self.store.reserve(request())
        self.assertIsNone(self.store.snapshot(call_id)["call_outcome"])
        self.store.submit_dial(call_id)
        self.store.terminate(call_id, CallState.UNKNOWN, "unknown", error="lost_ack")
        interim = self.store.snapshot(call_id)
        self.assertEqual((interim["state"], interim["result_status"]), ("unknown", "finalizing"))
        self.assertIsNone(interim["summary"])
        self.store.finish_result(call_id, task_outcome="unknown", summary=None, fields={}, transcript_complete=False)
        final = self.store.snapshot(call_id)
        self.assertEqual((final["call_outcome"], final["task_outcome"], final["result_status"]), ("unknown", "unknown", "ready"))
        self.assertFalse(final["transcript_complete"])


class _Device:
    def __init__(self) -> None:
        self.connected = True
        self.info = {"capabilities": {"call_control": True}, "active_call": None}
        self.commands: list[tuple[str, str | None, dict[str, Any]]] = []
        self.on_event: Any = None
        self.on_audio: Any = None
        self.dialled = asyncio.Event()
        self.disconnect_requested = asyncio.Event()
        self.drain_requested = asyncio.Event()
        self.allow_drain = asyncio.Event()
        self.submitted_samples = 0
        self.played_samples = 0
        self.audio_call: str | None = None
        self.drain_target = 0
        self.dtmf_gate: asyncio.Event | None = None
        self.arrived = asyncio.Event()
        self.probes: list[bool] = []

    def status(self) -> dict[str, Any]:
        return {"connected": self.connected}

    async def wait_connected(self, timeout: float) -> bool:
        try:
            async with asyncio.timeout(timeout):
                await self.arrived.wait()
        except TimeoutError:
            return False
        self.connected = True
        return True

    async def link_test(self, seconds: float) -> dict[str, Any]:
        healthy = self.probes.pop(0) if self.probes else False
        return {"healthy": healthy, "route": "direct test", "frames_sent": 50, "frames_echoed": 50 if healthy else 2}

    async def command(self, command: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
        self.commands.append((command, call_id, payload))
        if command == "dial":
            self.dialled.set()
        elif command == "dtmf" and self.dtmf_gate is not None:
            await self.dtmf_gate.wait()
        elif command == "hangup":
            self.disconnect_requested.set()
            await self.on_event({"type": "call_state", "call_id": call_id, "state": "ended", "disconnect_cause": {"code": 2}})
        return {"accepted": True}

    def begin_stream(self, call_id: str, epoch: int) -> dict[str, Any]:
        self.audio_call = call_id
        self.submitted_samples = 0
        return {"codec": "pcm"}

    def stop_stream(self) -> None:
        self.audio_call = None

    def send_audio(self, call_id: str, pcm: bytes) -> None:
        if call_id != self.audio_call:
            raise AssertionError("Audio crossed call ownership")
        self.submitted_samples += len(pcm) // 2

    async def wait_played(self, call_id: str, target_samples: int, timeout: float = 3) -> bool:
        self.drain_target = target_samples
        self.drain_requested.set()
        await self.allow_drain.wait()
        self.played_samples = target_samples
        return True

    async def flush(self, call_id: str) -> None:
        self.commands.append(("flush", call_id, {}))

    async def close(self) -> None:
        self.connected = False


class _Voice:
    def __init__(self, callbacks: Any, start_gate: asyncio.Event | None) -> None:
        self.callbacks = callbacks
        self.start_gate = start_gate
        self.start_entered = asyncio.Event()
        self.closed = False
        self.final_context = asyncio.Event()
        self.complete_context = asyncio.Event()
        self.contexts: list[tuple[str, str | None]] = []

    async def start(self) -> None:
        self.start_entered.set()
        if self.start_gate:
            await self.start_gate.wait()

    def push_audio(self, pcm: bytes) -> None:
        pass

    async def append_context(self, text: str, *, delegation_id: str | None = None, commentary: bool = False) -> None:
        self.contexts.append((text, delegation_id))
        if text.startswith("FINAL_RESULT"):
            self.final_context.set()
        if text.startswith("CALL_COMPLETE"):
            self.complete_context.set()

    async def close(self) -> None:
        self.closed = True


class _Tasks:
    def __init__(self) -> None:
        self.run_handler: Any = None
        self.review_handler: Any = None
        self.summary_entered = asyncio.Event()
        self.summary_gate: asyncio.Event | None = None

    async def run(self, brief: dict, transcript: list[dict], prompt: str, tools: dict) -> str:
        return await self.run_handler(prompt, tools)

    async def review(self, brief: dict, transcript: list[dict], quiet_s: float, tools: dict) -> str:
        return await self.review_handler(transcript, tools)

    async def summarize(self, brief: dict, transcript: list[dict]) -> dict:
        self.summary_entered.set()
        if self.summary_gate:
            await self.summary_gate.wait()
        return {"task_outcome": "incomplete", "summary": "Conversation ended before confirmation", "fields": {}}


class _ControllerCase(unittest.IsolatedAsyncioTestCase):
    review_quiet = 30.0

    async def asyncSetUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.store = CallStore(Path(self.directory.name) / "calls.db")
        self.device = _Device()
        self.tasks = _Tasks()
        self.start_gate: asyncio.Event | None = None
        self.voices: list[_Voice] = []
        self.voice_created = asyncio.Event()
        self.awake_held = self.awake_released = 0

        def factory(instructions: str, brief: str, callbacks: Any) -> _Voice:
            voice = _Voice(callbacks, self.start_gate)
            self.voices.append(voice)
            self.voice_created.set()
            return voice

        def keep_awake() -> Any:
            self.awake_held += 1

            def release() -> None:
                self.awake_released += 1

            return release

        self.controller = CallController(
            self.store, self.device, factory, self.tasks, keep_awake=keep_awake, device_wait=0.3, link_wait=0.4,
            farewell_timeout=2, farewell_quiet=0.01, review_quiet=self.review_quiet,
            archive_dir=Path(self.directory.name) / "archive",
        )
        await self.controller.start()

    async def asyncTearDown(self) -> None:
        self.device.allow_drain.set()
        if self.tasks.summary_gate:
            self.tasks.summary_gate.set()
        await self.controller.close()
        self.directory.cleanup()

    async def _active(self) -> tuple[str, _Voice]:
        call = await self.controller.place_call(request())
        await asyncio.wait_for(self.device.dialled.wait(), 1)
        await self.controller.on_device_event({"type": "call_state", "call_id": call["call_id"], "state": "active"})
        return call["call_id"], self.voices[-1]


class ControllerTests(_ControllerCase):
    async def test_cancel_preparing_does_not_dial_after_voice_finishes_starting(self) -> None:
        self.start_gate = asyncio.Event()
        response = await self.controller.place_call(request())
        await asyncio.wait_for(self.voice_created.wait(), 1)
        await asyncio.wait_for(self.voices[0].start_entered.wait(), 1)
        await self.controller.hangup_call(response["call_id"])
        self.start_gate.set()
        result = await self.controller.wait_call_result(response["call_id"], wait_s=1)
        self.assertEqual((result["state"], result["call_outcome"], result["result_status"]), ("cancelled", "not_started", "ready"))
        self.assertFalse(any(command == "dial" for command, _, _ in self.device.commands))
        self.assertTrue(self.voices[0].closed)
        self.assertEqual((self.awake_held, self.awake_released), (1, 1))

    async def test_computer_is_held_awake_until_the_result_is_ready(self) -> None:
        call_id, _ = await self._active()
        self.assertEqual((self.awake_held, self.awake_released), (1, 0))
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=1)
        self.assertEqual((self.awake_held, self.awake_released), (1, 1))

    async def test_call_waits_for_a_phone_that_is_reconnecting(self) -> None:
        self.device.connected = False
        call = await self.controller.place_call(request())
        await asyncio.sleep(0.05)
        self.assertFalse(self.device.dialled.is_set())
        self.device.arrived.set()
        await asyncio.wait_for(self.device.dialled.wait(), 1)
        await self.controller.hangup_call(call["call_id"])
        await self.controller.wait_call_result(call["call_id"], wait_s=1)

    async def test_call_is_not_started_when_no_phone_arrives(self) -> None:
        self.device.connected = False
        call = await self.controller.place_call(request())
        result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
        self.assertEqual(
            (result["state"], result["call_outcome"], result["error"]),
            ("failed", "not_started", "device_not_connected"),
        )
        self.assertFalse(self.device.dialled.is_set())

    async def test_cancel_waiter_preserves_active_call_and_other_waiters(self) -> None:
        call_id, voice = await self._active()
        waiting = asyncio.Event()

        async def first_waiter() -> dict:
            waiting.set()
            return await self.controller.wait_call_result(call_id, wait_s=10)

        first = asyncio.create_task(first_waiter())
        second = asyncio.create_task(self.controller.wait_call_result(call_id, wait_s=2))
        await waiting.wait()
        first.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await first
        self.assertEqual(self.store.state(call_id), CallState.ACTIVE)
        self.assertFalse(voice.closed)
        await self.controller.hangup_call(call_id)
        result = await second
        self.assertEqual((result["state"], result["result_status"]), ("ended", "ready"))

    async def test_superseded_delegation_cannot_send_old_dtmf(self) -> None:
        call_id, voice = await self._active()
        first_ready, second_ready = asyncio.Event(), asyncio.Event()
        handlers: dict[str, dict] = {}
        hold = asyncio.Event()

        async def run(prompt: str, tools: dict) -> str:
            handlers[prompt] = tools
            (first_ready if prompt == "first" else second_ready).set()
            await hold.wait()
            return "done"

        self.tasks.run_handler = run
        await voice.callbacks.delegation("d1", "first")
        await asyncio.wait_for(first_ready.wait(), 1)
        await voice.callbacks.delegation("d2", "second")
        await asyncio.wait_for(second_ready.wait(), 1)
        with self.assertRaises(asyncio.CancelledError):
            await handlers["first"]["send_dtmf"]({"digits": "1"})
        await handlers["second"]["send_dtmf"]({"digits": "2"})
        self.assertEqual([payload["digits"] for command, _, payload in self.device.commands if command == "dtmf"], ["2"])
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=1)

    async def test_finish_waits_for_new_speech_then_playback_watermark(self) -> None:
        call_id, voice = await self._active()

        async def run(prompt: str, tools: dict) -> str:
            await tools["finish_call"]({
                "outcome": "succeeded", "summary": "Friday at four confirmed",
                "fields": {"time": "16:00"}, "farewell_pending": True,
            })
            return "The appointment is confirmed. Say goodbye."

        self.tasks.run_handler = run
        await voice.callbacks.delegation("d1", "finish")
        await asyncio.wait_for(voice.final_context.wait(), 1)
        self.assertFalse(self.device.disconnect_requested.is_set())
        self.assertEqual(self.store.state(call_id), CallState.ENDING)
        await voice.callbacks.audio(b"\xe8\x03" * 960)
        await voice.callbacks.turn_done("assistant")
        await asyncio.wait_for(self.device.drain_requested.wait(), 1)
        self.assertFalse(self.device.disconnect_requested.is_set())
        self.assertEqual(self.device.drain_target, 960)
        self.device.allow_drain.set()
        result = await self.controller.wait_call_result(call_id, wait_s=1)
        self.assertTrue(self.device.disconnect_requested.is_set())
        self.assertEqual(result["task_outcome"], "succeeded")

    async def test_audio_format_failure_hangs_up_the_answered_call(self) -> None:
        def broken(call_id: str, epoch: int) -> dict:
            raise RuntimeError("opus encoder unavailable")

        self.device.begin_stream = broken
        call_id, _ = await self._active()
        result = await self.controller.wait_call_result(call_id, wait_s=2)
        self.assertEqual(result["state"], "ended")
        self.assertIn("opus encoder unavailable", result["error"])
        self.assertTrue(self.device.disconnect_requested.is_set())

    async def test_finished_call_is_archived_with_both_sides_and_transcript(self) -> None:
        call_id, voice = await self._active()
        remote, agent = b"\x10\x00" * 960, b"\xe8\x03" * 960
        for _ in range(3):
            await self.controller.on_device_audio(call_id, remote)
        await voice.callbacks.audio(agent)
        await voice.callbacks.transcript("user", "Hello?", True)
        await voice.callbacks.transcript("assistant", "Hi, this is a test.", True)
        await voice.callbacks.transcript("user", "Bye-b", False)
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=2)

        archive = Path(self.directory.name) / "archive"
        record = json.loads((archive / f"{call_id}.json").read_text())
        self.assertEqual([(item["role"], item["text"]) for item in record["transcript"]],
                         [("user", "Hello?"), ("assistant", "Hi, this is a test.")])
        self.assertEqual([(item["role"], item["text"]) for item in record["current_partials"]], [("user", "Bye-b")])
        self.assertFalse(record["transcript_complete"])
        self.assertEqual((record["state"], record["request"]["destination"], record["audio_codec"]),
                         ("ended", request().destination, "pcm"))
        self.assertEqual(record["recording"]["channels"], ["remote", "agent"])

        with wave.open(str(archive / record["recording"]["file"])) as recording:
            self.assertEqual((recording.getnchannels(), recording.getframerate()), (2, 48_000))
            frames = array("h", recording.readframes(recording.getnframes()))
        left, right = frames[0::2], frames[1::2]
        self.assertEqual(left.count(16), 3 * 960)
        self.assertEqual(right.count(1000), 960)
        self.assertFalse(list(archive.glob("*.part")))

    async def test_an_unavailable_recorder_does_not_drop_the_answered_call(self) -> None:
        with mock.patch("phonxiety.controller.CallRecorder", side_effect=OSError("read-only file system")):
            call_id, _ = await self._active()
        self.assertEqual(self.store.state(call_id), CallState.ACTIVE)
        self.assertFalse(self.device.disconnect_requested.is_set())
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=2)
        record = json.loads((Path(self.directory.name) / "archive" / f"{call_id}.json").read_text())
        self.assertIn("read-only file system", record["recording"]["error"])

    async def test_phone_end_does_not_report_summary_ready_early(self) -> None:
        call_id, voice = await self._active()
        await voice.callbacks.transcript("user", "Please call back later", True)
        self.tasks.summary_gate = asyncio.Event()
        await self.controller.on_device_event({"type": "call_state", "call_id": call_id, "state": "ended", "disconnect_cause": {"code": 3}})
        await asyncio.wait_for(self.tasks.summary_entered.wait(), 1)
        interim = await self.controller.wait_call_result(call_id, wait_s=0)
        self.assertEqual((interim["state"], interim["result_status"]), ("ended", "finalizing"))
        self.assertIsNone(interim["summary"])
        self.tasks.summary_gate.set()
        final = await self.controller.wait_call_result(call_id, wait_s=1)
        self.assertEqual(final["result_status"], "ready")
        self.assertEqual(final["task_outcome"], "incomplete")


class QuietLineTests(_ControllerCase):
    review_quiet = 0.05

    async def test_quiet_line_is_disconnected_without_a_delegation(self) -> None:
        call_id, voice = await self._active()

        async def review(transcript: list[dict], tools: dict) -> str:
            await tools["finish_call"]({
                "outcome": "succeeded", "summary": "Word repeated",
                "fields": {"word": "pizza"}, "farewell_pending": False,
            })
            return ""

        self.tasks.review_handler = review
        await voice.callbacks.transcript("assistant", "Pizza. Thank you, goodbye!", True)
        await voice.callbacks.turn_done("assistant")
        self.device.allow_drain.set()
        result = await self.controller.wait_call_result(call_id, wait_s=2)
        self.assertTrue(self.device.disconnect_requested.is_set())
        self.assertEqual(
            (result["state"], result["task_outcome"], result["fields"]), ("ended", "succeeded", {"word": "pizza"})
        )
        self.assertFalse(voice.complete_context.is_set())

    async def test_pending_farewell_is_spoken_before_disconnect(self) -> None:
        call_id, voice = await self._active()

        async def review(transcript: list[dict], tools: dict) -> str:
            await tools["finish_call"]({
                "outcome": "succeeded", "summary": "Word repeated", "fields": {}, "farewell_pending": True,
            })
            return ""

        self.tasks.review_handler = review
        await voice.callbacks.transcript("assistant", "Pizza. Thank you!", True)
        await voice.callbacks.turn_done("assistant")
        await asyncio.wait_for(voice.complete_context.wait(), 1)
        self.assertFalse(self.device.disconnect_requested.is_set())
        await voice.callbacks.audio(b"\xe8\x03" * 960)
        await voice.callbacks.turn_done("assistant")
        self.device.allow_drain.set()
        result = await self.controller.wait_call_result(call_id, wait_s=2)
        self.assertTrue(self.device.disconnect_requested.is_set())
        self.assertEqual(result["task_outcome"], "succeeded")

    async def test_speech_supersedes_a_running_review(self) -> None:
        call_id, voice = await self._active()
        entered, hold = asyncio.Event(), asyncio.Event()
        stale: dict = {}

        async def review(transcript: list[dict], tools: dict) -> str:
            if not stale:
                stale.update(tools)
            entered.set()
            await hold.wait()
            return "WAIT"

        self.tasks.review_handler = review
        await voice.callbacks.transcript("assistant", "What time suits you?", True)
        await voice.callbacks.turn_done("assistant")
        await asyncio.wait_for(entered.wait(), 1)
        await voice.callbacks.transcript("user", "Friday", False)
        with self.assertRaises(asyncio.CancelledError):
            await stale["finish_call"]({
                "outcome": "incomplete", "summary": "No answer", "fields": {}, "farewell_pending": False,
            })
        self.assertEqual(self.store.state(call_id), CallState.ACTIVE)
        self.assertFalse(self.device.disconnect_requested.is_set())
        hold.set()
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=1)

    async def test_later_review_sees_keys_already_pressed(self) -> None:
        call_id, voice = await self._active()
        histories: list[list[tuple[str, str]]] = []
        second = asyncio.Event()

        async def review(transcript: list[dict], tools: dict) -> str:
            histories.append([(entry["role"], entry["text"]) for entry in transcript])
            if len(histories) == 1:
                await tools["send_dtmf"]({"digits": "1"})
            else:
                second.set()
            return "WAIT"

        self.tasks.review_handler = review
        await voice.callbacks.transcript("user", "For bookings press one.", True)
        await voice.callbacks.turn_done("user")
        await asyncio.wait_for(second.wait(), 2)
        self.assertEqual(histories[0], [("user", "For bookings press one.")])
        self.assertEqual(histories[1][-1], ("phone", "keypad digits sent: 1"))
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=1)

    async def test_speech_does_not_abort_keys_being_pressed(self) -> None:
        call_id, voice = await self._active()
        self.device.dtmf_gate = asyncio.Event()
        pressing, pressed = asyncio.Event(), asyncio.Event()

        async def review(transcript: list[dict], tools: dict) -> str:
            if not pressing.is_set():
                pressing.set()
                await tools["send_dtmf"]({"digits": "12"})
                pressed.set()
            return "WAIT"

        self.tasks.review_handler = review
        await voice.callbacks.transcript("user", "Enter your two-digit code.", True)
        await voice.callbacks.turn_done("user")
        await asyncio.wait_for(pressing.wait(), 1)
        await voice.callbacks.transcript("user", "Thank you for", False)
        self.device.dtmf_gate.set()
        await asyncio.wait_for(pressed.wait(), 1)
        self.assertEqual([payload["digits"] for command, _, payload in self.device.commands if command == "dtmf"], ["12"])
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=1)


class LinkReadinessTests(_ControllerCase):
    async def asyncSetUp(self) -> None:
        await super().asyncSetUp()
        self.device.info["capabilities"]["link_test"] = True

    async def test_dial_waits_until_the_link_carries_audio(self) -> None:
        self.device.probes = [False, True]
        call = await self.controller.place_call(request())
        await asyncio.wait_for(self.device.dialled.wait(), 2)
        self.assertEqual(self.device.probes, [])
        await self.controller.hangup_call(call["call_id"])
        await self.controller.wait_call_result(call["call_id"], wait_s=1)

    async def test_nobody_is_rung_over_a_link_that_never_carries_audio(self) -> None:
        call = await self.controller.place_call(request())
        result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
        self.assertEqual(
            (result["state"], result["call_outcome"], result["error"]),
            ("failed", "not_started", "phone_link_not_ready"),
        )
        self.assertFalse(self.device.dialled.is_set())

    async def test_a_link_drop_while_waiting_does_not_fail_the_call(self) -> None:
        self.device.probes = [False, True]
        call = await self.controller.place_call(request())
        await asyncio.sleep(0.05)
        await self.controller.on_device_event({"type": "device_disconnected"})
        await asyncio.wait_for(self.device.dialled.wait(), 2)
        await self.controller.hangup_call(call["call_id"])
        await self.controller.wait_call_result(call["call_id"], wait_s=1)


if __name__ == "__main__":
    unittest.main()
