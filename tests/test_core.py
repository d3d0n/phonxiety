from __future__ import annotations

import asyncio
import contextlib
import json
import tempfile
import time
import unittest
import wave
from array import array
from pathlib import Path
from typing import Any
from unittest import mock

from pydantic import ValidationError

from phonxiety import controller as controller_module
from phonxiety.controller import (
    CLOSE_CANCEL_BUDGET_S,
    CLOSE_TASK_BUDGET_S,
    CallController,
)
from phonxiety.device import DeviceError
from phonxiety.models import (
    CallLimits,
    CallRequest,
    CallState,
    TestAudioOptions as ProbeOptions,
    TestToneOptions as ProbeTone,
)
from phonxiety.store import CallStore


# close() spends at most CLOSE_TASK_BUDGET_S + CLOSE_CANCEL_BUDGET_S on background work before
# it closes the device and the store; every fixture deadline below must be longer than that.
TEARDOWN_LIMIT_S = 30.0


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
        self.dtmf_entered = asyncio.Event()
        self.arrived = asyncio.Event()
        self.arrived_waiting = asyncio.Event()
        self.probes: list[bool] = []
        self.probes_started = asyncio.Event()

    def status(self) -> dict[str, Any]:
        return {"connected": self.connected}

    async def wait_connected(self, timeout: float) -> bool:
        self.arrived_waiting.set()
        try:
            async with asyncio.timeout(timeout):
                await self.arrived.wait()
        except TimeoutError:
            return False
        self.connected = True
        return True

    async def link_test(self, seconds: float) -> dict[str, Any]:
        self.probes_started.set()
        healthy = self.probes.pop(0) if self.probes else False
        return {"healthy": healthy, "route": "direct test", "frames_sent": 50, "frames_echoed": 50 if healthy else 2}

    async def command(self, command: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
        self.commands.append((command, call_id, payload))
        if command == "dial":
            self.dialled.set()
        elif command == "dtmf" and self.dtmf_gate is not None:
            self.dtmf_entered.set()
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
    review_at_turn_end = False

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
    controller_options: dict[str, Any] = {}

    async def asyncSetUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.store = CallStore(Path(self.directory.name) / "calls.db")
        self.device = _Device()
        self.tasks = _Tasks()
        self.start_gate: asyncio.Event | None = None
        self.voices: list[_Voice] = []
        self.briefs: list[str] = []
        self.voice_created = asyncio.Event()
        self.awake_held = self.awake_released = 0

        def factory(instructions: str, brief: str, callbacks: Any) -> _Voice:
            voice = _Voice(callbacks, self.start_gate)
            self.voices.append(voice)
            self.briefs.append(brief)
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
            **self.controller_options,
        )
        await self.controller.start()

    async def asyncTearDown(self) -> None:
        # Release every gate: an assertion that failed before a test opened its gate must not
        # leave a task parked inside close().
        self.device.allow_drain.set()
        if self.start_gate is not None:
            self.start_gate.set()
        if self.device.dtmf_gate is not None:
            self.device.dtmf_gate.set()
        if self.tasks.summary_gate is not None:
            self.tasks.summary_gate.set()
        async with asyncio.timeout(TEARDOWN_LIMIT_S):
            await self.controller.close()
        self.directory.cleanup()

    async def _settle(self, turns: int = 50) -> None:
        """Let tasks the daemon just started reach their next wait; no wall-clock timing."""
        for _ in range(turns):
            await asyncio.sleep(0)

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
        await asyncio.wait_for(self.device.arrived_waiting.wait(), 1)  # the call is waiting for the phone
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

    async def test_end_call_request_hangs_up_after_the_goodbye_without_the_task_agent(self) -> None:
        call_id, voice = await self._active()
        asked: list[str] = []

        async def run(prompt: str, tools: dict) -> str:
            asked.append(prompt)
            return "WAIT"

        self.tasks.run_handler = run
        await voice.callbacks.audio(b"\xe8\x03" * 960)  # the goodbye
        await voice.callbacks.turn_done("assistant")
        await voice.callbacks.delegation("d1", "END_CALL")
        await asyncio.wait_for(self.device.drain_requested.wait(), 1)
        self.assertEqual(self.device.drain_target, 960)
        await voice.callbacks.delegation("d2", "END_CALL")  # repeated while the goodbye still plays
        self.assertEqual(self.store.state(call_id), CallState.ENDING)
        self.device.allow_drain.set()
        await self.controller.wait_call_result(call_id, wait_s=2)
        self.assertTrue(self.device.disconnect_requested.is_set())
        self.assertEqual(asked, [])

    async def test_keypress_request_is_pressed_once_without_the_task_agent(self) -> None:
        call_id, voice = await self._active()
        asked: list[str] = []

        async def run(prompt: str, tools: dict) -> str:
            asked.append(prompt)
            return "WAIT"

        self.tasks.run_handler = run
        self.device.dtmf_gate = asyncio.Event()
        await voice.callbacks.delegation("k1", "Press 1 2 3 for the menu")
        pressing = self.controller._calls[call_id].work_task
        await asyncio.wait_for(self.device.dtmf_entered.wait(), 1)  # the first press is in flight
        await voice.callbacks.delegation("k2", "DTMF 123")  # the same request heard twice
        self.assertFalse(pressing.done())  # not cancelled by the repeat
        self.device.dtmf_gate.set()
        await pressing
        sent = [payload["digits"] for command, _, payload in self.device.commands if command == "dtmf"]
        self.assertEqual((sent, asked), (["123"], []))
        self.assertEqual(sorted(delegation for _, delegation in voice.contexts if delegation), ["k1", "k2"])
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=2)

    async def test_a_menu_is_recognised_and_recorded(self) -> None:
        call_id, voice = await self._active()
        await voice.callbacks.transcript("user", "Для выбора русского языка нажмите 2", True)
        self.assertTrue(any(text.startswith("FAR_END: the other side just offered keys") for text, _ in voice.contexts))
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=2)
        record = json.loads((Path(self.directory.name) / "archive" / f"{call_id}.json").read_text())
        self.assertEqual(record["far_end"], "ivr")

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
        self.assertIn("compressed", record["recording"])

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

    async def _goodbye(self, voice: _Voice, review: Any) -> None:
        """The agent says goodbye and finishes its turn; `review` counts and ends the call."""
        self.tasks.review_handler = review
        self.device.allow_drain.set()
        await voice.callbacks.transcript("assistant", "Спасибо, до свидания.", True)
        await voice.callbacks.turn_done("assistant")

    async def test_a_voice_that_cannot_hang_up_is_hung_up_right_after_its_goodbye(self) -> None:
        call_id, voice = await self._active()
        voice.review_at_turn_end = True
        reviews = 0

        async def review(transcript: list[dict], tools: dict) -> str:
            nonlocal reviews
            reviews += 1
            await tools["finish_call"]({
                "outcome": "succeeded", "summary": "Said goodbye", "fields": {}, "farewell_pending": False,
            })
            return ""

        await self._goodbye(voice, review)
        # The turn-end review finishes the call itself; the 30 s quiet review never runs.
        result = await self.controller.wait_call_result(call_id, wait_s=2)
        self.assertEqual((result["state"], result["task_outcome"]), ("ended", "succeeded"))
        self.assertEqual(reviews, 1)
        self.assertTrue(self.device.disconnect_requested.is_set())

    async def test_a_voice_that_hangs_up_itself_gets_no_review_at_turn_end(self) -> None:
        call_id, voice = await self._active()
        reviews = 0

        async def review(transcript: list[dict], tools: dict) -> str:
            nonlocal reviews
            reviews += 1
            return "WAIT"

        await self._goodbye(voice, review)
        await self._settle()  # a review the daemon dispatched would have started by now
        self.assertEqual(reviews, 0)
        self.assertFalse(self.controller._calls[call_id].review_now)
        self.assertFalse(self.device.disconnect_requested.is_set())
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=1)

    async def test_speech_right_after_the_turn_end_cancels_the_pending_review(self) -> None:
        call_id, voice = await self._active()
        voice.review_at_turn_end = True
        reviews = 0

        async def review(transcript: list[dict], tools: dict) -> str:
            nonlocal reviews
            reviews += 1
            return "WAIT"

        self.tasks.review_handler = review
        await voice.callbacks.transcript("assistant", "Спасибо, до свидания.", True)
        await voice.callbacks.turn_done("assistant")
        # The other party starts a new question before the supervisor picked the review up.
        await voice.callbacks.transcript("user", "А подождите, ещё", False)
        await self._settle()  # the queued review would have started by now if it survived
        self.assertEqual(reviews, 0)
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=1)


class TestCallProbeTests(_ControllerCase):
    async def _answer(self, options: TestAudioOptions) -> str:
        call = await self.controller.place_call(request(), test_audio=options)
        await asyncio.wait_for(self.device.dialled.wait(), 1)
        await self.controller.on_device_event({"type": "call_state", "call_id": call["call_id"], "state": "active"})
        return call["call_id"]

    async def test_agent_finish_waits_until_the_probes_have_run(self) -> None:
        with mock.patch.object(controller_module, "LOOPBACK_PROBE_S", 2.0):
            call_id = await self._answer(ProbeOptions(loopback=True))
        voice = self.voices[-1]
        self.assertIn("loopback_start", [command for command, _, _ in self.device.commands])
        self.device.allow_drain.set()
        # The agent's callback returns at once, but the call stays up while the probe runs.
        await asyncio.wait_for(voice.callbacks.delegation("d1", "END_CALL"), 1.0)
        self.assertEqual(self.store.state(call_id), CallState.ACTIVE)
        self.assertFalse(self.device.disconnect_requested.is_set())
        began = time.monotonic()
        result = await self.controller.wait_call_result(call_id, wait_s=5)
        self.assertGreaterEqual(time.monotonic() - began, 1.8)
        self.assertEqual(result["state"], "ended")
        self.assertTrue(self.device.disconnect_requested.is_set())
        self.assertEqual([command for command, _, _ in self.device.commands].count("loopback_stop"), 1)

    async def test_the_app_can_still_end_a_call_during_the_hold(self) -> None:
        call_id = await self._answer(ProbeOptions(loopback=True, tone=ProbeTone(seconds=10)))
        names = [command for command, _, _ in self.device.commands]
        self.assertIn("loopback_start", names)
        self.assertIn("tone_start", names)
        began = time.monotonic()
        await self.controller.hangup_call(call_id)
        result = await self.controller.wait_call_result(call_id, wait_s=5)
        self.assertEqual(result["state"], "ended")
        # A hang-up is not held: the 10 s probe window does not delay the ending.
        self.assertLess(time.monotonic() - began, 5)
        names = [command for command, _, _ in self.device.commands]
        self.assertEqual(names.count("loopback_stop"), 1)
        self.assertEqual(names.count("tone_stop"), 1)

    async def test_cancel_actions_during_a_probe_does_not_stop_the_tone(self) -> None:
        call_id = await self._answer(ProbeOptions(tone=ProbeTone(seconds=1)))
        call = self.controller._calls[call_id]
        self.assertTrue(call.tone_on)
        self.assertIsNotNone(call.tone_stop_task)

        async def review(transcript: list[dict], tools: dict) -> str:
            return "WAIT"

        self.tasks.review_handler = review
        before = len(self.device.commands)
        await self.controller._run_review(call, call.generation, 0.2)
        sent = [command for command, _, _ in self.device.commands[before:]]
        # Routine supervision cancelled the phone's action audio ...
        self.assertIn("cancel_actions", sent)
        # ... while the Lab tone keeps running until its own scheduled stop.
        self.assertNotIn("tone_stop", sent)
        self.assertTrue(call.tone_on)

        await asyncio.sleep(1.1)
        sent = [command for command, _, _ in self.device.commands[before:]]
        self.assertEqual(sent.count("tone_stop"), 1)
        self.assertFalse(call.tone_on)
        await self.controller.hangup_call(call_id)
        await self.controller.wait_call_result(call_id, wait_s=2)

    async def test_speech_withdraws_a_held_finish_and_a_later_review_finishes(self) -> None:
        with mock.patch.object(controller_module, "LOOPBACK_PROBE_S", 2.0):
            call_id = await self._answer(ProbeOptions(loopback=True))
        voice = self.voices[-1]
        self.device.allow_drain.set()
        call = self.controller._calls[call_id]
        deadline = call.probe_hold_until
        self.assertIsNotNone(deadline)

        first = asyncio.Event()
        finishes = 0

        async def review(transcript: list[dict], tools: dict) -> str:
            nonlocal finishes
            finishes += 1
            await tools["finish_call"]({
                "outcome": "succeeded", "summary": "Heard both ways", "fields": {}, "farewell_pending": False,
            })
            if finishes == 1:
                first.set()
            return ""

        self.tasks.review_handler = review
        voice.review_at_turn_end = True
        await voice.callbacks.transcript("assistant", "Hello? Can you hear me?", True)
        await voice.callbacks.turn_done("assistant")
        await asyncio.wait_for(first.wait(), 1)
        self.assertTrue(call.finish_armed)
        self.assertIsNotNone(call.finish_hold_task)
        self.assertEqual(self.store.state(call_id), CallState.ACTIVE)

        # The far end answers while the probes still run: the held finish is withdrawn.
        await voice.callbacks.transcript("user", "Yes, I hear you.", True)
        await self._settle()
        self.assertFalse(call.finish_armed)
        self.assertTrue(call.finish_hold_task.cancelled())
        self.assertEqual(call.probe_hold_until, deadline)

        # A later review asks again; the finish still waits for the original deadline.
        await voice.callbacks.transcript("assistant", "Thank you, goodbye.", True)
        await voice.callbacks.turn_done("assistant")
        result = await self.controller.wait_call_result(call_id, wait_s=5)
        self.assertEqual(result["state"], "ended")
        self.assertEqual(finishes, 2)
        self.assertTrue(self.device.disconnect_requested.is_set())

    async def test_the_hold_does_not_block_voice_events(self) -> None:
        with mock.patch.object(controller_module, "LOOPBACK_PROBE_S", 5.0):
            call_id = await self._answer(ProbeOptions(loopback=True))
        voice = self.voices[-1]
        self.device.allow_drain.set()
        call = self.controller._calls[call_id]
        # The delegation callback is awaited inside the voice reader; it must return at once.
        await asyncio.wait_for(voice.callbacks.delegation("d1", "END_CALL"), 1.0)
        self.assertTrue(call.finish_armed)
        self.assertIsNotNone(call.finish_hold_task)
        self.assertEqual(self.store.state(call_id), CallState.ACTIVE)
        # A transcript arriving during the hold is still consumed and recorded.
        await asyncio.wait_for(voice.callbacks.transcript("user", "Still here", True), 1.0)
        self.assertIn("Still here", [segment["text"] for segment in self.store.transcript(call_id)])
        self.assertEqual(self.store.state(call_id), CallState.ACTIVE)
        await self.controller.hangup_call(call_id)
        result = await self.controller.wait_call_result(call_id, wait_s=2)
        self.assertEqual(result["state"], "ended")

    async def test_a_held_finish_without_farewell_gets_no_spoken_answer(self) -> None:
        with mock.patch.object(controller_module, "LOOPBACK_PROBE_S", 2.0):
            call_id = await self._answer(ProbeOptions(loopback=True))
        voice = self.voices[-1]
        self.device.allow_drain.set()
        call = self.controller._calls[call_id]
        delegated = asyncio.Event()

        async def run(prompt: str, tools: dict) -> str:
            await tools["finish_call"]({
                "outcome": "succeeded", "summary": "Line confirmed",
                "fields": {}, "farewell_pending": False,
            })
            delegated.set()
            return "Done. Say nothing more."

        self.tasks.run_handler = run
        await voice.callbacks.delegation("d1", "finish")
        await asyncio.wait_for(delegated.wait(), 1)
        # Let the delegation task reach its answer.
        for _ in range(5):
            await asyncio.sleep(0)
        self.assertTrue(call.held_without_farewell)
        self.assertFalse(
            any(text.startswith("FINAL_RESULT") for text, _ in voice.contexts),
            "a finish without a farewell must not hand the voice anything to say",
        )
        # The hold survives and ends the call once the probe window has passed.
        self.assertEqual(self.store.state(call_id), CallState.ACTIVE)
        result = await self.controller.wait_call_result(call_id, wait_s=5)
        self.assertEqual(result["state"], "ended")
        self.assertTrue(self.device.disconnect_requested.is_set())


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


class LanguageHintTests(_ControllerCase):
    async def test_the_language_hint_reaches_the_voice_brief(self) -> None:
        self.controller._language_hint = "ru-RU"
        call = await self.controller.place_call(request())
        await asyncio.wait_for(self.device.dialled.wait(), 1)
        self.assertIn("Speak to the other party in ru-RU", self.briefs[-1])
        await self.controller.hangup_call(call["call_id"])
        await self.controller.wait_call_result(call["call_id"], wait_s=1)


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
        await asyncio.wait_for(self.device.probes_started.wait(), 1)  # the readiness probe is in flight
        await self.controller.on_device_event({"type": "device_disconnected"})
        await asyncio.wait_for(self.device.dialled.wait(), 2)
        await self.controller.hangup_call(call["call_id"])
        await self.controller.wait_call_result(call["call_id"], wait_s=1)


class DialRefusalCase(_ControllerCase):
    """Shared harness: the phone refuses dials before placing them."""

    def _refuse_dials(self, times: int, *, link_drops: bool = True) -> None:
        """The phone refuses the next `times` dials with `link_down`, placing nothing."""
        base = self.device.command
        left = {"count": times}

        async def command(name: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
            if name == "dial" and left["count"] > 0:
                left["count"] -= 1
                self.device.commands.append((name, call_id, payload))
                if link_drops:
                    self.device.connected = False
                raise DeviceError("link_down")
            return await base(name, call_id, **payload)

        self.device.command = command

    def _dial_attempts(self) -> int:
        return sum(1 for command, _, _ in self.device.commands if command == "dial")

    @staticmethod
    def _ring_in(seconds: float) -> CallRequest:
        """A ring deadline below the model's minimum; the controller rings out the limit it is given."""
        limits = CallLimits.model_construct(ring_timeout_s=seconds, max_duration_s=600)
        return request().model_copy(update={"limits": limits})


class DialRefusalTests(DialRefusalCase):
    """A phone that refuses a dial before placing it must not cost the user the call."""

    controller_options = {"dial_retry_wait": 2.0}

    async def test_a_refused_dial_waits_for_the_link_and_rings_once(self) -> None:
        self._refuse_dials(1)
        call = await self.controller.place_call(request())
        await asyncio.wait_for(self.device.arrived_waiting.wait(), 1)  # the daemon is waiting for the phone
        self.assertEqual(self._dial_attempts(), 1)  # not retried before the phone is back
        self.assertNotIn(self.store.state(call["call_id"]), (CallState.FAILED, CallState.UNKNOWN, CallState.CANCELLED))

        self.device.arrived.set()
        await asyncio.wait_for(self.device.dialled.wait(), 2)  # the retry was accepted
        self.assertEqual(self._dial_attempts(), 2)
        self.assertEqual(len(self.voices), 1)  # the caller's single call, not a second one
        await self.controller.on_device_event({"type": "call_state", "call_id": call["call_id"], "state": "active"})
        await self.controller.hangup_call(call["call_id"])
        result = await self.controller.wait_call_result(call["call_id"], wait_s=1)
        self.assertEqual((result["state"], result["call_outcome"]), ("ended", "answered"))

    async def test_a_dial_refused_twice_ends_with_an_explicit_error(self) -> None:
        self._refuse_dials(2, link_drops=False)
        call = await self.controller.place_call(request())
        result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
        self.assertEqual(
            (result["state"], result["call_outcome"], result["error"], result["result_status"]),
            ("failed", "not_started", "link_down", "ready"),
        )
        self.assertEqual(self._dial_attempts(), 2)

    async def test_a_dial_lost_to_the_link_is_never_rung_twice(self) -> None:
        # The link dropped after the command went out: the phone may already be ringing,
        # so the daemon reports the outcome as unknown and does not dial again.
        base = self.device.command

        async def command(name: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
            if name == "dial":
                self.device.commands.append((name, call_id, payload))
                self.device.connected = False
                raise DeviceError("device_disconnected")
            return await base(name, call_id, **payload)

        self.device.command = command
        call = await self.controller.place_call(request())
        result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
        self.assertEqual((result["state"], result["call_outcome"]), ("unknown", "unknown"))
        self.assertEqual(self._dial_attempts(), 1)

    async def test_a_retry_that_times_out_stays_unknown_and_is_reconciled(self) -> None:
        # The retry went out and its answer never came back: the phone may be ringing
        # someone, so the call is unknown and reconciled, never failed with "not started".
        base = self.device.command
        dials = {"count": 0}
        reconciled = asyncio.Event()

        async def command(name: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
            if name == "dial":
                dials["count"] += 1
                self.device.commands.append((name, call_id, payload))
                if dials["count"] == 1:
                    raise DeviceError("link_down")  # refused, placing nothing; the link stays up
                raise DeviceError("device_command_timeout: dial")
            if name == "hangup" and not reconciled.is_set():
                self.device.commands.append((name, call_id, payload))
                reconciled.set()  # the phone cannot confirm yet: the outcome stays unknown
                raise DeviceError("link_down")
            return await base(name, call_id, **payload)

        self.device.command = command
        call = await self.controller.place_call(request())
        result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
        self.assertEqual(
            (result["state"], result["call_outcome"], result["error"]),
            ("unknown", "unknown", "device_command_timeout: dial"),
        )
        await asyncio.wait_for(reconciled.wait(), 2)  # the daemon asked the phone to drop the line
        self.assertEqual(dials["count"], 2)

    async def test_a_hangup_during_the_reconnect_wait_prevents_the_redial(self) -> None:
        self._refuse_dials(1)
        call = await self.controller.place_call(request())
        await asyncio.wait_for(self.device.arrived_waiting.wait(), 1)  # the daemon waits for the link
        await self.controller.hangup_call(call["call_id"])
        self.device.arrived.set()  # the phone comes back after the user cancelled
        result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
        self.assertEqual(self._dial_attempts(), 1)  # the cancelled call was never dialed again
        self.assertEqual(result["state"], "ended")
        self.assertNotEqual(result["call_outcome"], "answered")


class DialRingDeadlineTests(DialRefusalCase):
    """The ring deadline of a refused dial must not end the call while the retry is pending."""

    controller_options = {"dial_retry_wait": 5.0}
    ring_seconds = 1

    async def test_the_ring_deadline_does_not_end_a_recovering_call(self) -> None:
        # Both ring deadlines and the reconnect wait are separate gates: no scheduling
        # order depends on a wall-clock sleep.
        original_sleep = asyncio.sleep
        first_release, fresh_release = asyncio.Event(), asyncio.Event()
        first_entered, fresh_entered = asyncio.Event(), asyncio.Event()
        ring_tasks: list[asyncio.Task[Any]] = []

        async def gated_sleep(delay: float, *args: Any, **kwargs: Any) -> Any:
            if delay != self.ring_seconds:
                return await original_sleep(delay, *args, **kwargs)
            task = asyncio.current_task()
            assert task is not None
            ring_tasks.append(task)
            if len(ring_tasks) == 1:
                first_entered.set()
                await first_release.wait()
            else:
                fresh_entered.set()
                await fresh_release.wait()

        base = self.device.command
        refused = {"done": False}

        async def command(name: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
            if name == "dial" and not refused["done"]:
                refused["done"] = True
                await first_entered.wait()  # the deadline is pending when the phone refuses
                self.device.commands.append((name, call_id, payload))
                self.device.connected = False
                raise DeviceError("link_down")
            return await base(name, call_id, **payload)

        with mock.patch.object(controller_module.asyncio, "sleep", gated_sleep):
            self.device.command = command
            call = await self.controller.place_call(self._ring_in(self.ring_seconds))
            await asyncio.wait_for(self.device.arrived_waiting.wait(), 1)  # waiting for the link
            await asyncio.wait_for(first_entered.wait(), 1)  # the refused dial's deadline is pending

            first_release.set()  # release the deadline the refusal cancelled
            with contextlib.suppress(asyncio.CancelledError):
                await ring_tasks[0]
            self.assertTrue(ring_tasks[0].cancelled())
            self.assertEqual(
                (self.store.state(call["call_id"]), self._dial_attempts()), (CallState.DIALING, 1)
            )  # the old deadline ended nothing and the reconnect wait did not advance

            self.device.arrived.set()  # the phone comes back; the retry is sent
            await asyncio.wait_for(self.device.dialled.wait(), 2)
            await asyncio.wait_for(fresh_entered.wait(), 1)  # the retry carries its own deadline
            self.assertEqual(self._dial_attempts(), 2)

            fresh_release.set()  # the unanswered retry rings out
            result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
            self.assertEqual((result["state"], result["call_outcome"]), ("ended", "no_answer"))


class DialHangupInFlightTests(DialRefusalCase):
    """A hangup must not wait for a retry dial the phone has not answered yet."""

    controller_options = {"dial_retry_wait": 2.0}

    async def test_a_hangup_while_the_retry_is_in_flight_returns_and_ends_the_call(self) -> None:
        self._refuse_dials(1)
        call = await self.controller.place_call(request())
        await asyncio.wait_for(self.device.arrived_waiting.wait(), 1)

        base = self.device.command
        dial_gate = asyncio.Event()
        dial_entered = asyncio.Event()

        async def command(name: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
            if name == "dial":
                dial_entered.set()
                await dial_gate.wait()
            return await base(name, call_id, **payload)

        self.device.command = command
        self.device.arrived.set()
        try:
            await asyncio.wait_for(dial_entered.wait(), 2)  # the retry is out and unanswered
            async with asyncio.timeout(5):  # the hangup does not wait for the dial's acknowledgement
                await self.controller.hangup_call(call["call_id"])
            result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
            self.assertEqual(result["state"], "ended")
        finally:
            dial_gate.set()  # let an in-flight retry unwind even when the assertion failed


class DialHangupRaceTests(DialRefusalCase):
    """The phone can refuse a hangup before it has accepted the retry dial."""

    controller_options = {"dial_retry_wait": 2.0}

    async def test_a_not_owned_hangup_does_not_leave_the_retried_call_up(self) -> None:
        self._refuse_dials(1)
        base = self.device.command
        dial_gate = asyncio.Event()
        dial_entered = asyncio.Event()
        refused = asyncio.Event()
        hangups = {"count": 0}

        async def command(name: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
            if name == "dial":
                dial_entered.set()
                await dial_gate.wait()
            elif name == "hangup":
                hangups["count"] += 1
                if not dial_gate.is_set():
                    self.device.commands.append((name, call_id, payload))
                    refused.set()
                    raise DeviceError("not_owned")  # the phone has not taken the retry dial yet
            return await base(name, call_id, **payload)

        call = await self.controller.place_call(request())
        await asyncio.wait_for(self.device.arrived_waiting.wait(), 1)
        # Only the retry is gated: the first dial is the refused one.
        self.device.command = command
        self.device.arrived.set()
        try:
            await asyncio.wait_for(dial_entered.wait(), 2)  # the retry is in flight
            async with asyncio.timeout(5):  # the hangup does not wait for the dial's acknowledgement
                await self.controller.hangup_call(call["call_id"])
            await asyncio.wait_for(refused.wait(), 2)  # the phone refused before the retry was accepted
            dial_gate.set()  # the phone takes the retry after refusing the hangup

            result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
            self.assertEqual(result["state"], "ended")
            self.assertGreaterEqual(hangups["count"], 2)  # the line the retry opened was dropped too
        finally:
            dial_gate.set()


class DialSendBoundaryTests(DialRefusalCase):
    """A hangup recorded behind the send lock keeps the cancelled retry off the link entirely."""

    class _GateLink:
        """A fake link that can park one control write inside the hub's `_send_lock`."""

        peer = "iroh:test"

        def __init__(self, hub: Any) -> None:
            self.hub = hub
            self.messages: list[Any] = []
            self.gate_next = False
            self.holding = asyncio.Event()
            self.release = asyncio.Event()

        def route(self) -> str:
            return "direct 10.0.0.2, 3 ms"

        def paths(self) -> list[str]:
            return []

        async def send(self, message: str | bytes) -> None:
            try:
                parsed: Any = json.loads(message)
            except ValueError:
                parsed = message
            self.messages.append(parsed)
            if self.gate_next:
                self.gate_next = False
                self.holding.set()
                await self.release.wait()
            if isinstance(parsed, dict):
                pending = self.hub._pending.get(parsed.get("id"))
                if pending is not None and not pending.done():
                    pending.set_result({"accepted": True})

        async def close(self, code: int, reason: str) -> None:
            pass

    async def test_a_control_write_holding_the_lock_keeps_the_cancelled_retry_off_the_link(self) -> None:
        from phonxiety.device import DeviceHub

        hub = DeviceHub()
        link = self._GateLink(hub)
        hub._link = link
        hub._ready.set()

        base = self.device.command
        dials = {"count": 0}
        retry_ready, retry_go, retry_done = asyncio.Event(), asyncio.Event(), asyncio.Event()
        hangup_entered, hangup_go = asyncio.Event(), asyncio.Event()

        async def command(name: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
            if name == "dial":
                dials["count"] += 1
                self.device.commands.append((name, call_id, payload))
                if dials["count"] == 1:
                    raise DeviceError("link_down")  # refused before placing anything
                retry_ready.set()
                await retry_go.wait()
                should_send = payload.pop("should_send", None)
                try:
                    if should_send is None:
                        return await hub.command(name, call_id, **payload)
                    return await hub.command(name, call_id, should_send=should_send, **payload)
                finally:
                    retry_done.set()
            if name == "hangup":
                hangup_entered.set()
                await hangup_go.wait()
            return await base(name, call_id, **payload)

        self.device.command = command
        call = await self.controller.place_call(request())
        try:
            await asyncio.wait_for(retry_ready.wait(), 2)  # the retry is reserved and about to submit

            link.gate_next = True
            holding = asyncio.create_task(hub.send_control('{"type": "hold"}'))
            await asyncio.wait_for(link.holding.wait(), 2)  # another control write owns the send lock

            retry_go.set()
            await self._settle()  # the retry is queued on the lock, nothing sent yet
            await self.controller.hangup_call(call["call_id"])  # records the cancellation
            await asyncio.wait_for(hangup_entered.wait(), 2)  # the ending task is still unanswered

            link.release.set()  # the control write releases the lock the retry is waiting for
            await asyncio.wait_for(holding, 2)
            await asyncio.wait_for(retry_done.wait(), 2)  # the retry has passed the send boundary
            self.assertEqual(
                [message["command"] for message in link.messages if isinstance(message, dict) and "command" in message],
                [],
            )

            hangup_go.set()  # the phone finally answers the hangup
            result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
            self.assertEqual(result["state"], "ended")
        finally:
            link.release.set()
            hangup_go.set()


class DialCancelledRetryOutcomeTests(DialRefusalCase):
    """A cancelled retry keeps its proven outcome for every later ending task."""

    controller_options = {"dial_retry_wait": 2.0}

    async def test_a_second_hangup_after_a_proven_refusal_ends_the_call_locally(self) -> None:
        base = self.device.command
        dials = {"count": 0}
        hangups = {"count": 0}
        retry_entered, retry_go = asyncio.Event(), asyncio.Event()
        retry_raising = asyncio.Event()
        first_hangup_entered, first_hangup_go = asyncio.Event(), asyncio.Event()

        async def command(name: str, call_id: str | None = None, **payload: Any) -> dict[str, Any]:
            if name == "dial":
                dials["count"] += 1
                self.device.commands.append((name, call_id, payload))
                if dials["count"] == 1:
                    raise DeviceError("link_down")  # refused, nothing placed
                retry_entered.set()
                await retry_go.wait()
                retry_raising.set()
                raise DeviceError("link_down")  # the retry is refused too; nothing placed
            if name == "hangup":
                hangups["count"] += 1
                if hangups["count"] == 1:
                    first_hangup_entered.set()
                    await first_hangup_go.wait()
                self.device.commands.append((name, call_id, payload))
                raise DeviceError("not_owned")  # the phone has no line to drop
            return await base(name, call_id, **payload)

        self.device.command = command
        call = await self.controller.place_call(request())
        try:
            await asyncio.wait_for(retry_entered.wait(), 2)  # the retry is out and unanswered
            await self.controller.hangup_call(call["call_id"])  # cancels the reservation
            await asyncio.wait_for(first_hangup_entered.wait(), 2)  # the first ending is unanswered

            retry_go.set()  # the retry resolves as a proven refusal
            await asyncio.wait_for(retry_raising.wait(), 2)  # its outcome is recorded
            await self.controller.hangup_call(call["call_id"], "second_request")  # replaces the ending task

            result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
            self.assertEqual((result["state"], result["call_outcome"]), ("ended", "disconnected"))
            self.assertNotEqual(result["call_outcome"], "unknown")
            self.assertEqual(dials["count"], 2)
            self.assertEqual(hangups["count"], 2)
        finally:
            first_hangup_go.set()


class DialRetryBoundTests(DialRefusalCase):
    controller_options = {"dial_retry_wait": 0.2}

    async def test_a_phone_that_never_comes_back_ends_the_call_explicitly(self) -> None:
        self._refuse_dials(1)  # the link dropped and the phone never returns
        call = await self.controller.place_call(request())
        result = await self.controller.wait_call_result(call["call_id"], wait_s=2)
        self.assertEqual(
            (result["state"], result["call_outcome"], result["error"]),
            ("failed", "not_started", "device_not_connected"),
        )
        self.assertEqual(self._dial_attempts(), 1)


class ShutdownBudgetTests(unittest.TestCase):
    def test_the_fixture_deadline_outlives_the_close_budget(self) -> None:
        # A teardown shorter than close()'s own budget would report the fixture, not the stuck task.
        self.assertGreater(TEARDOWN_LIMIT_S, CLOSE_TASK_BUDGET_S + CLOSE_CANCEL_BUDGET_S)


class CloseTests(unittest.IsolatedAsyncioTestCase):
    """Shutdown with work that does not unwind on its own, as after an assertion left a gate shut."""

    async def asyncSetUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.store = CallStore(Path(self.directory.name) / "calls.db")
        self.device = _Device()
        self.tasks = _Tasks()
        self.start_gate = asyncio.Event()
        self.voice_created = asyncio.Event()
        self.voices: list[_Voice] = []

        def factory(instructions: str, brief: str, callbacks: Any) -> _Voice:
            voice = _Voice(callbacks, self.start_gate)
            self.voices.append(voice)
            self.voice_created.set()
            return voice

        self.controller = CallController(
            self.store, self.device, factory, self.tasks, device_wait=0.3, link_wait=0.4,
        )
        await self.controller.start()

    async def asyncTearDown(self) -> None:
        # Every gate is left shut on purpose: close() has to end anyway.
        async with asyncio.timeout(TEARDOWN_LIMIT_S):
            await self.controller.close()
        self.store.close()
        self.directory.cleanup()

    async def test_close_ends_a_call_parked_inside_voice_start(self) -> None:
        await self.controller.place_call(request())
        await asyncio.wait_for(self.voice_created.wait(), 1)
        self.assertTrue(self.voices)
        async with asyncio.timeout(TEARDOWN_LIMIT_S):
            await self.controller.close()
        self.assertFalse(self.device.connected)
        self.assertEqual(self.controller._tasks, set())

    async def test_close_names_a_task_that_outlives_cancellation_and_still_cleans_up(self) -> None:
        async def stubborn() -> None:
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                await asyncio.sleep(30)  # ignores the cancellation, as a blocked thread-backed call does

        task = asyncio.create_task(stubborn(), name="stubborn-test")
        self.controller._tasks.add(task)
        with mock.patch.object(controller_module, "CLOSE_TASK_BUDGET_S", 0.05), \
                mock.patch.object(controller_module, "CLOSE_CANCEL_BUDGET_S", 0.05), \
                mock.patch.object(controller_module.log, "warning") as warning, \
                mock.patch.object(self.store, "close") as store_close:
            async with asyncio.timeout(TEARDOWN_LIMIT_S):
                await self.controller.close()
        try:
            self.assertTrue(any("stubborn-test" in str(call) for call in warning.call_args_list), warning.call_args_list)
            self.assertFalse(task.done())
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        # Cleanup ran even though the task never stopped.
        store_close.assert_called_once_with()
        self.assertFalse(self.device.connected)


if __name__ == "__main__":
    unittest.main()
