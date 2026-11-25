from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest import mock

from phonxiety.app_api import APP_API_VERSION, AppApi, redact
from phonxiety.controller import CallController
from phonxiety.device import MAX_API_RESULT_CHARS, DeviceHub
from phonxiety.models import CallRequest, CallState
from phonxiety.net.framing import HEADER_BYTES, TEXT, frame, parse_header
from phonxiety.net.iroh import IrohLink
from phonxiety.net.link import LinkClosed
from phonxiety.settings import Settings
from phonxiety.store import CallStore

TOKEN = "app-secret"


def request(key: str = "request-a") -> CallRequest:
    return CallRequest(request_id=key, destination="+12025550123", goal="Confirm a Friday appointment")


class _Device:
    """The controller's device contract plus the bits the app API reads."""

    def __init__(self) -> None:
        self.connected = True
        self.info = {"capabilities": {"call_control": True, "link_test": True}, "active_call": None}
        self.commands: list[tuple[str, str | None, dict[str, Any]]] = []
        self.on_event: Any = None
        self.on_audio: Any = None
        self.dialled = asyncio.Event()
        self.submitted_samples = 0
        self.link_test_result: dict[str, Any] = {
            "codec": "opus", "transport": "stream", "peer": "iroh:abcd", "route": "direct 10.0.0.2, 4 ms",
            "route_after": "direct 10.0.0.2, 5 ms", "seconds": 1.0, "frames_sent": 50, "frames_echoed": 50,
            "frame_ms": {"median": 30, "p95": 44, "max": 61}, "ping_ms": {"median": 4, "p95": 8, "max": 9},
            "send_stall_ms": 2, "frame_bytes": 48, "link_lost": False, "healthy": True,
        }

    def status(self) -> dict[str, Any]:
        return {"connected": self.connected}

    def counters(self) -> dict[str, int]:
        return {"frames_out": 10, "frames_in": 9, "dropped_samples": 0}

    async def wait_connected(self, timeout: float) -> bool:
        return self.connected

    async def link_test(
        self, seconds: float, codec: str | None = None, *, datagrams: bool = False, bitrate: int | None = None,
    ) -> dict[str, Any]:
        result = dict(self.link_test_result)
        result["codec"] = codec or result["codec"]
        result["bitrate"] = bitrate if result["codec"] == "opus" else None
        return result

    async def command(self, command: str, call_id: str | None = None, *, timeout: float = 10, **payload: Any) -> dict[str, Any]:
        self.commands.append((command, call_id, payload))
        if command == "dial":
            self.dialled.set()
        elif command == "hangup":
            await self.on_event({"type": "call_state", "call_id": call_id, "state": "ended", "disconnect_cause": {"code": 2}})
        return {"accepted": True}

    def begin_stream(self, call_id: str, epoch: int) -> dict[str, Any]:
        self.submitted_samples = 0
        return {"codec": "pcm"}

    def stop_stream(self) -> None:
        pass

    def send_audio(self, call_id: str, pcm: bytes) -> None:
        self.submitted_samples += len(pcm) // 2

    async def wait_played(self, call_id: str, target_samples: int, timeout: float = 3) -> bool:
        return True

    async def flush(self, call_id: str) -> None:
        pass

    async def close(self) -> None:
        self.connected = False


class _Voice:
    review_at_turn_end = False

    def __init__(self, callbacks: Any) -> None:
        self.callbacks = callbacks
        self.contexts: list[tuple[str, bool]] = []

    async def start(self) -> None:
        pass

    def push_audio(self, pcm: bytes) -> None:
        pass

    async def append_context(self, text: str, *, delegation_id: str | None = None, commentary: bool = False) -> None:
        self.contexts.append((text, commentary))

    async def close(self) -> None:
        pass


class _Tasks:
    async def run(self, brief: dict, transcript: list[dict], prompt: str, tools: dict) -> str:
        return "done"

    async def review(self, brief: dict, transcript: list[dict], quiet_s: float, tools: dict) -> str:
        return "ok"

    async def summarize(self, brief: dict, transcript: list[dict]) -> dict:
        return {"task_outcome": "incomplete", "summary": "Still waiting for confirmation", "fields": {"when": "unknown"}}


class _ApiCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.state = self.root / "state"
        self.state.mkdir()
        self.settings = Settings(state_dir=self.state, device_token="dev", host_token="host", app_token=TOKEN)
        self.store = CallStore(self.root / "calls.db")
        self.device = _Device()
        self.tasks = _Tasks()
        self.voices: list[_Voice] = []

        def factory(instructions: str, brief: str, callbacks: Any) -> _Voice:
            voice = _Voice(callbacks)
            self.voices.append(voice)
            return voice

        self.controller = CallController(
            self.store, self.device, factory, self.tasks,
            device_wait=0.3, link_wait=0.4, farewell_timeout=1, farewell_quiet=0.01, review_quiet=60,
            archive_dir=self.state / "calls",
        )
        await self.controller.start()
        self.api = AppApi(
            self.settings, self.controller,
            link_info=lambda: {
                "endpoint_id": "endpoint-1", "address_port": 18763,
                "certificate": "cc" * 32, "iroh_available": True,
            },
        )

    async def asyncTearDown(self) -> None:
        await self.controller.close()
        self.directory.cleanup()

    async def call(self, method: str, path: str, query: dict | None = None, body: dict | None = None,
                   token: str | None = TOKEN) -> tuple[int, dict[str, Any]]:
        message: dict[str, Any] = {"type": "api", "id": "req-1", "method": method, "path": path,
                                   "query": query or {}, "body": body or {}}
        if token is not None:
            message["app_token"] = token
        return await self.api.handle(message)

    async def active_call(self) -> str:
        call = await self.controller.place_call(request())
        await asyncio.wait_for(self.device.dialled.wait(), 1)
        await self.controller.on_device_event({"type": "call_state", "call_id": call["call_id"], "state": "active"})
        return call["call_id"]


class EnvelopeTests(_ApiCase):
    async def test_token_path_and_method_are_checked(self) -> None:
        status, body = await self.call("GET", "/app/v1/info", token="wrong")
        self.assertEqual((status, body["code"]), (401, "unauthorized"))
        status, body = await self.call("GET", "/app/v1/info", token=None)
        self.assertEqual(status, 401)
        status, body = await self.call("GET", "/app/v1/nothing")
        self.assertEqual((status, body["code"]), (404, "not_found"))
        status, body = await self.call("DELETE", "/app/v1/info")
        self.assertEqual((status, body["code"]), (405, "method_not_allowed"))
        status, body = await self.call("GET", "/app/v1/calls", query={"limit": "many"})
        self.assertEqual((status, body["code"]), (400, "invalid_argument"))

    async def test_a_handler_failure_becomes_a_short_error_not_an_exception(self) -> None:
        status, body = await self.call("POST", "/app/v1/calls/unknown-call/pause")
        self.assertEqual((status, body["code"]), (409, "call_not_active"))
        status, body = await self.call("GET", "/app/v1/calls/unknown-call")
        self.assertEqual((status, body["code"]), (404, "call_not_found"))


class InfoTests(_ApiCase):
    async def test_info_advertises_versions_controls_and_attachment_mode(self) -> None:
        status, body = await self.call("GET", "/app/v1/info")
        self.assertEqual(status, 200)
        self.assertEqual(body["app_api"], APP_API_VERSION)
        self.assertEqual(body["attachments"], "paged")  # the fake phone offers no bulk stream
        self.assertTrue(body["voice_controls"]["instruction"])
        self.assertTrue(body["voice_controls"]["pause"])
        self.assertFalse(body["voice_controls"]["loopback"])
        self.assertEqual(body["phone"]["device"], None)

    async def test_status_reports_the_link_the_listeners_know(self) -> None:
        status, body = await self.call("GET", "/app/v1/status")
        self.assertEqual(status, 200)
        self.assertEqual(body["daemon"], "ready")
        self.assertEqual(body["link"]["endpoint_id"], "endpoint-1")
        self.assertTrue(body["link"]["iroh_available"])


class LinkTestAndProbeTests(_ApiCase):
    async def test_link_test_is_persisted_and_summarised(self) -> None:
        status, body = await self.call("POST", "/app/v1/link-test", body={"seconds": 1})
        self.assertEqual(status, 200)
        self.assertEqual((body["p50"], body["p95"], body["max"]), (30, 44, 61))
        self.assertEqual(body["loss"], 0.0)
        self.assertIsNone(body["reorder"])
        self.assertEqual(body["verdict"], "good")
        self.assertEqual(body["frames"], {"sent": 50, "echoed": 50})
        self.assertEqual(len(list((self.state / "link-tests").glob("*.json"))), 1)
        status, body = await self.call("GET", "/app/v1/link-test")
        self.assertEqual(len(body["tests"]), 1)

    async def test_a_codec_trial_passes_its_bitrate_to_the_device(self) -> None:
        status, body = await self.call("POST", "/app/v1/link-test", body={"seconds": 1, "codec": "opus", "bitrate": 16_000})
        self.assertEqual(status, 200)
        self.assertEqual(body["bitrate"], 16_000)
        status, body = await self.call("POST", "/app/v1/link-test", body={"seconds": 1, "codec": "pcm", "bitrate": 16_000})
        self.assertEqual((status, body["code"]), (400, "invalid_argument"))
        status, body = await self.call("POST", "/app/v1/link-test", body={"seconds": 1, "codec": "opus", "bitrate": 999})
        self.assertEqual((status, body["code"]), (400, "invalid_argument"))

    async def test_a_lost_link_is_a_failed_verdict(self) -> None:
        self.device.link_test_result.update({"link_lost": True, "healthy": False, "frames_echoed": 10})
        status, body = await self.call("POST", "/app/v1/link-test", body={"seconds": 1})
        self.assertEqual((status, body["verdict"], body["loss"]), (200, "failed", 0.8))

    async def test_probe_reports_tailscale_unavailable_without_the_binary(self) -> None:
        with mock.patch("phonxiety.app_api.shutil.which", return_value=None):
            status, body = await self.call("POST", "/app/v1/probe", body={"candidates": ["iroh", "tailscale"]})
        self.assertEqual(status, 200)
        iroh, tailscale = body["candidates"]
        self.assertTrue(iroh["available"])
        self.assertTrue(iroh["reachable"])
        self.assertIsInstance(iroh["rtt_ms"], float)
        self.assertFalse(tailscale["available"])
        self.assertEqual(tailscale["reason"], "not_installed")

    async def test_unknown_candidate_is_reported_not_refused(self) -> None:
        status, body = await self.call("POST", "/app/v1/probe", body={"candidates": ["wireguard"]})
        self.assertEqual(status, 200)
        self.assertEqual(body["candidates"][0]["reason"], "unknown_candidate")


class CallsTests(_ApiCase):
    async def test_validate_reports_fields_without_dialing(self) -> None:
        status, body = await self.call("POST", "/app/v1/calls/validate", body={
            "destination": "+12025550123", "goal": "Book a table",
        })
        self.assertEqual((status, body["valid"]), (200, True))
        self.assertTrue(body["warnings"])
        status, body = await self.call("POST", "/app/v1/calls/validate", body={
            "destination": "12", "goal": "Book a table",
        })
        self.assertEqual((status, body["valid"]), (200, False))
        self.assertEqual(body["errors"][0]["location"], "destination")

    async def test_place_requires_a_confirmation_and_echoes_it(self) -> None:
        status, body = await self.call("POST", "/app/v1/calls", body={
            "destination": "+12025550123", "goal": "Confirm the appointment",
        })
        self.assertEqual((status, body["code"]), (400, "confirmation_required"))
        status, body = await self.call("POST", "/app/v1/calls", body={
            "destination": "+12025550123", "goal": "Confirm the appointment", "confirmation_id": "c-1",
        })
        self.assertEqual(status, 200)
        self.assertEqual(body["confirmation_id"], "c-1")
        self.assertEqual(body["request_id"], "app-c-1")
        self.assertEqual(body["state"], "preparing")
        # Same confirmation again is the same idempotent request, not a second call.
        status, again = await self.call("POST", "/app/v1/calls", body={
            "destination": "+12025550123", "goal": "Confirm the appointment", "confirmation_id": "c-1",
        })
        self.assertEqual((status, again["call_id"]), (200, body["call_id"]))

    async def test_the_hourly_cap_refuses_more_calls(self) -> None:
        self.api.hourly_call_cap = 1
        await self.call("POST", "/app/v1/calls", body={
            "destination": "+12025550123", "goal": "Confirm", "confirmation_id": "cap-1",
        })
        status, body = await self.call("POST", "/app/v1/calls", body={
            "destination": "+12025550123", "goal": "Confirm", "confirmation_id": "cap-2",
        })
        self.assertEqual((status, body["code"]), (429, "hourly_call_cap"))

    async def test_test_call_uses_a_canned_brief_and_never_retries(self) -> None:
        status, body = await self.call("POST", "/app/v1/test-call", body={"destination": "+12025550123", "max_duration_s": 120})
        self.assertEqual((status, body["code"]), (400, "invalid_argument"))
        status, body = await self.call("POST", "/app/v1/test-call", body={"destination": "+12025550123", "max_duration_s": 30})
        self.assertEqual((status, body["profile"]), (200, "test"))
        self.assertTrue(body["request_id"].startswith("test-"))
        stored = self.store.request(body["call_id"])
        self.assertEqual(stored.limits.max_duration_s, 30)
        self.assertIn("audio test call", stored.context)

    async def test_history_lists_live_and_archived_calls(self) -> None:
        call_id = await self.active_call()
        status, body = await self.call("GET", "/app/v1/calls")
        self.assertEqual(status, 200)
        self.assertEqual([row["call_id"] for row in body["calls"]], [call_id])
        self.assertTrue(body["calls"][0]["live"])
        status, detail = await self.call("GET", f"/app/v1/calls/{call_id}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["state"], "active")
        self.assertEqual(detail["request"]["destination"], "+12025550123")
        self.assertIsNotNone(detail["live"])

    async def test_transcript_pages_by_cursor(self) -> None:
        call_id = await self.active_call()
        self.store.append_transcript(call_id, "user", "one", True)
        self.store.append_transcript(call_id, "assistant", "two", True)
        status, body = await self.call("GET", f"/app/v1/calls/{call_id}/transcript", query={"limit": 1})
        self.assertEqual([turn["text"] for turn in body["transcript"]], ["one"])
        self.assertTrue(body["has_more"])
        status, body = await self.call(
            "GET", f"/app/v1/calls/{call_id}/transcript", query={"cursor": body["next_cursor"], "limit": 1},
        )
        self.assertEqual([turn["text"] for turn in body["transcript"]], ["two"])

    async def test_live_control_endpoints(self) -> None:
        call_id = await self.active_call()
        status, body = await self.call("POST", f"/app/v1/calls/{call_id}/instruction", body={"text": "Ask about Friday"})
        self.assertEqual((status, body["delivered"]), (200, True))
        self.assertEqual(self.voices[-1].contexts[-1][0], "Supervisor instruction: Ask about Friday")
        self.assertTrue(self.voices[-1].contexts[-1][1])  # commentary: never read out

        status, body = await self.call("POST", f"/app/v1/calls/{call_id}/pause")
        self.assertEqual((status, body["muted"]), (200, True))
        status, detail = await self.call("GET", f"/app/v1/calls/{call_id}")
        self.assertTrue(detail["live"]["muted"])
        status, body = await self.call("POST", f"/app/v1/calls/{call_id}/resume")
        self.assertFalse(body["muted"])

        status, body = await self.call("POST", f"/app/v1/calls/{call_id}/summarize")
        self.assertEqual((status, body["summary"]), (200, "Still waiting for confirmation"))

        status, body = await self.call("POST", f"/app/v1/calls/{call_id}/dtmf", body={"digits": "12#*"})
        self.assertEqual(status, 200)
        self.assertIn(("dtmf", call_id, {"action_generation": 0, "digits": "12#*"}), self.device.commands)
        status, body = await self.call("POST", f"/app/v1/calls/{call_id}/dtmf", body={"digits": "x1"})
        self.assertEqual(status, 400)

    async def test_marks_land_in_the_live_record_and_then_the_archive(self) -> None:
        call_id = await self.active_call()
        status, body = await self.call("POST", f"/app/v1/calls/{call_id}/marks", body={"mark": "agreed", "note": "Friday 3pm"})
        self.assertEqual(status, 200)
        self.assertEqual(body["marks"][0]["mark"], "agreed")
        status, body = await self.call("POST", f"/app/v1/calls/{call_id}/marks", body={"mark": "nonsense"})
        self.assertEqual(status, 400)
        await self.call("POST", f"/app/v1/calls/{call_id}/hangup")
        async with asyncio.timeout(5):
            while self.controller.live_call(call_id) is not None:
                await asyncio.sleep(0.02)
        record = json.loads((self.state / "calls" / f"{call_id}.json").read_text())
        self.assertEqual([mark["mark"] for mark in record["marks"]], ["agreed"])
        # A mark added after the call ended goes into the archived record itself.
        status, body = await self.call("POST", f"/app/v1/calls/{call_id}/marks", body={"mark": "follow-up"})
        self.assertEqual(status, 200)
        record = json.loads((self.state / "calls" / f"{call_id}.json").read_text())
        self.assertEqual([mark["mark"] for mark in record["marks"]], ["agreed", "follow-up"])

    async def test_events_long_poll_wakes_on_a_transcript_callback(self) -> None:
        call_id = await self.active_call()
        status, snapshot = await self.call("GET", f"/app/v1/calls/{call_id}/events")
        self.assertEqual(status, 200)
        self.assertEqual(snapshot["state"], "active")
        since = snapshot["revision"]
        poll = asyncio.create_task(
            self.call("GET", f"/app/v1/calls/{call_id}/events", query={"since": since, "wait_s": 5})
        )
        await asyncio.sleep(0.05)
        call = self.controller._calls[call_id]
        await self.controller._transcript(call, "user", "Friday at three", True)
        status, body = await asyncio.wait_for(poll, 3)
        self.assertEqual(status, 200)
        kinds = [event["type"] for event in body["events"]]
        self.assertIn("transcript", kinds)
        transcript = next(event for event in body["events"] if event["type"] == "transcript")
        self.assertEqual((transcript["role"], transcript["text"]), ("user", "Friday at three"))
        self.assertGreater(body["revision"], since)

    async def test_long_poll_returns_after_wait_s_with_nothing_new(self) -> None:
        call_id = await self.active_call()
        _, snapshot = await self.call("GET", f"/app/v1/calls/{call_id}/events")
        began = asyncio.get_running_loop().time()
        status, body = await self.call(
            "GET", f"/app/v1/calls/{call_id}/events", query={"since": snapshot["revision"], "wait_s": 0.3},
        )
        self.assertEqual((status, body["events"]), (200, []))
        self.assertLess(asyncio.get_running_loop().time() - began, 2.0)

    async def test_audio_pages_base64_from_the_archive(self) -> None:
        call_id = "11111111-2222-3333-4444-555555555555"
        archive = self.state / "calls"
        archive.mkdir(parents=True)
        (archive / f"{call_id}.wav").write_bytes(b"RIFFdata")
        (archive / f"{call_id}.m4a").write_bytes(b"M4Adata!")
        (archive / f"{call_id}.json").write_text(json.dumps({
            "call_id": call_id, "state": "ended", "transcript": [], "marks": [],
            "recording": {"file": f"{call_id}.wav", "compressed": {"file": f"{call_id}.m4a"}},
        }))
        status, body = await self.call("GET", f"/app/v1/calls/{call_id}/audio",
                                       query={"format": "m4a", "offset": 2, "limit": 3})
        self.assertEqual(status, 200)
        self.assertEqual(body["total"], 8)
        self.assertEqual(base64.b64decode(body["data"]), b"Ada")
        status, body = await self.call("GET", f"/app/v1/calls/{call_id}/audio", query={"format": "ogg"})
        self.assertEqual(status, 400)

    async def test_delete_needs_the_call_id_as_confirmation(self) -> None:
        call_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        archive = self.state / "calls"
        archive.mkdir(parents=True)
        (archive / f"{call_id}.json").write_text(json.dumps({"call_id": call_id, "state": "ended"}))
        status, body = await self.call("DELETE", f"/app/v1/calls/{call_id}", body={"confirm": "other"})
        self.assertEqual((status, body["code"]), (400, "confirmation_required"))
        status, body = await self.call("DELETE", f"/app/v1/calls/{call_id}", body={"confirm": call_id})
        self.assertEqual(status, 200)
        self.assertFalse((archive / f"{call_id}.json").exists())


class SettingsTests(_ApiCase):
    async def test_get_and_put_persist_and_ask_for_a_restart(self) -> None:
        status, body = await self.call("GET", "/app/v1/settings")
        self.assertEqual(status, 200)
        self.assertEqual(body["values"]["audio_codec"], self.settings.audio_codec)
        status, body = await self.call("PUT", "/app/v1/settings", body={"values": {"audio_codec": "pcm", "opus_bitrate": 24000}})
        self.assertEqual(status, 200)
        self.assertEqual(body["changed"], ["audio_codec", "opus_bitrate"])
        self.assertTrue(body["restart_required"])
        stored = json.loads((self.state / "settings.json").read_text())
        self.assertEqual(stored["values"]["audio_codec"], "pcm")
        with mock.patch.dict(os.environ, {"PHONXIETY_STATE_DIR": str(self.state)}):
            reloaded = Settings.from_env()
        self.assertEqual((reloaded.audio_codec, reloaded.opus_bitrate), ("pcm", 24000))
        status, body = await self.call("PUT", "/app/v1/settings", body={"values": {"audio_codec": "mp3"}})
        self.assertEqual((status, body["code"]), (400, "invalid_argument"))
        status, body = await self.call("PUT", "/app/v1/settings", body={"values": {"nope": 1}})
        self.assertEqual(status, 400)

    async def test_call_and_voice_keys_are_readable_validated_and_persisted(self) -> None:
        status, body = await self.call("GET", "/app/v1/settings")
        self.assertEqual(status, 200)
        for key in (
            "ring_timeout_s", "max_duration_s", "default_result_fields", "default_constraints",
            "language_hint", "codex_task_model", "gemini_model", "gemini_voice", "iroh_relays",
        ):
            self.assertIn(key, body["values"])
        self.assertEqual(body["choices"]["ring_timeout_s"], {"min": 5, "max": 180})

        status, body = await self.call("PUT", "/app/v1/settings", body={"values": {
            "ring_timeout_s": 45,
            "max_duration_s": 600,
            "default_result_fields": ["heard", "price"],
            "default_constraints": "Keep it under a minute.",
            "language_hint": "ru-RU",
            "gemini_model": "gemini-2.5-flash",
            "gemini_voice": "Kore",
        }})
        self.assertEqual(status, 200)
        self.assertEqual(body["values"]["ring_timeout_s"], 45)
        self.assertEqual(body["values"]["default_result_fields"], ["heard", "price"])
        self.assertTrue(body["restart_required"])
        self.assertEqual(sorted(body["restart_required_fields"]), ["gemini_model", "gemini_voice", "language_hint"])

        # A live-only change applies to the next call without a restart.
        status, body = await self.call("PUT", "/app/v1/settings", body={"values": {"max_duration_s": 300}})
        self.assertEqual(status, 200)
        self.assertFalse(body["restart_required"])

        stored = json.loads((self.state / "settings.json").read_text())["values"]
        self.assertEqual(stored["max_duration_s"], 300)
        self.assertEqual(stored["default_result_fields"], ["heard", "price"])
        with mock.patch.dict(os.environ, {"PHONXIETY_STATE_DIR": str(self.state)}):
            reloaded = Settings.from_env()
        self.assertEqual(
            (reloaded.ring_timeout_s, reloaded.max_duration_s, reloaded.language_hint),
            (45, 300, "ru-RU"),
        )
        self.assertEqual(reloaded.default_result_fields, ("heard", "price"))
        self.assertEqual(reloaded.default_constraints, "Keep it under a minute.")
        self.assertEqual((reloaded.gemini_model, reloaded.gemini_voice), ("gemini-2.5-flash", "Kore"))

    async def test_bad_control_values_are_refused_with_a_reason(self) -> None:
        for values in (
            {"ring_timeout_s": 1},
            {"ring_timeout_s": "soon"},
            {"max_duration_s": 5000},
            {"default_result_fields": ["x" * 65]},
            {"default_result_fields": [f"field{index}" for index in range(17)]},
            {"default_constraints": "x" * 2001},
            {"language_hint": "not a language"},
            {"codex_task_model": "bad model!"},
            {"gemini_voice": "bad voice!"},
        ):
            status, body = await self.call("PUT", "/app/v1/settings", body={"values": values})
            self.assertEqual(status, 400, values)
            self.assertEqual(body["code"], "invalid_argument")

    async def test_call_defaults_reach_a_validated_call(self) -> None:
        await self.call("PUT", "/app/v1/settings", body={"values": {
            "ring_timeout_s": 45,
            "max_duration_s": 600,
            "default_result_fields": ["heard"],
            "default_constraints": "One short question.",
        }})
        status, body = await self.call("POST", "/app/v1/calls/validate", body={
            "destination": "+12025550123", "goal": "Confirm the Friday appointment is still on.",
        })
        self.assertEqual(status, 200)
        request = body["request"]
        self.assertEqual(request["limits"], {"max_duration_s": 600, "ring_timeout_s": 45})
        self.assertEqual(request["result_fields"], ["heard"])
        self.assertEqual(request["constraints"], "One short question.")


class TestLabAndDoctorTests(_ApiCase):
    async def test_loopback_and_tone_are_gated_by_hello_capabilities(self) -> None:
        status, body = await self.call("POST", "/app/v1/loopback", body={"enabled": True})
        self.assertEqual((status, body["code"]), (409, "unsupported_by_phone"))
        self.device.info["control_caps"] = ["loopback_start", "loopback_stop", "tone_start"]
        status, body = await self.call("POST", "/app/v1/loopback", body={"enabled": True})
        self.assertEqual(status, 200)
        self.assertIn(("loopback_start", None, {}), self.device.commands)
        status, body = await self.call("POST", "/app/v1/tone", body={"hz": 440, "dbfs": -18})
        self.assertEqual((status, body["hz"]), (200, 440))
        self.assertIn("tone_start", [command[0] for command in self.device.commands])
        status, body = await self.call("POST", "/app/v1/tone", body={"hz": 9000})
        self.assertEqual(status, 400)

    async def test_doctor_reports_and_applies_only_safe_fixes(self) -> None:
        status, body = await self.call("GET", "/app/v1/doctor")
        self.assertEqual(status, 200)
        names = [check["name"] for check in body["checks"]]
        self.assertIn("phone", names)
        self.assertIn("disk", names)
        status, body = await self.call("POST", "/app/v1/doctor/fix", body={"fix": "restart_everything"})
        self.assertEqual((status, body["code"]), (400, "unknown_fix"))
        status, body = await self.call("POST", "/app/v1/doctor/fix", body={"fix": "state_dirs"})
        self.assertEqual(status, 200)
        self.assertTrue((self.state / "diagnostics").is_dir())
        self.assertFalse(body["restart_required"])


class DiagnosticsTests(_ApiCase):
    async def test_log_tail_is_redacted(self) -> None:
        secret = "sk-" + "A" * 48
        (self.state / "daemon.log").write_text(
            f"2026-11-26 INFO phone connected\n"
            f"2026-11-26 INFO app_token={secret} accepted\n"
            f"2026-11-26 INFO Authorization: Bearer {secret}\n"
            f"2026-11-26 INFO opaque {secret}\n"
        )
        status, body = await self.call("GET", "/app/v1/diagnostics")
        self.assertEqual(status, 200)
        text = "\n".join(body["log_tail"])
        self.assertNotIn(secret, text)
        self.assertIn("<redacted>", text)
        self.assertEqual(body["daemon"]["app_api"], APP_API_VERSION)

    async def test_bundle_is_created_paged_and_streamable(self) -> None:
        status, body = await self.call("POST", "/app/v1/diagnostics/bundle", body={})
        self.assertEqual(status, 200)
        bundle_id = body["bundle_id"]
        self.assertGreater(body["size"], 0)
        status, page = await self.call("GET", f"/app/v1/diagnostics/bundle/{bundle_id}", query={"limit": 4})
        self.assertEqual(status, 200)
        self.assertEqual(page["total"], body["size"])
        self.assertTrue(base64.b64decode(page["data"]).startswith(b"PK"))
        status, body = await self.call("GET", "/app/v1/diagnostics/bundle/missing-bundle")
        self.assertEqual((status, body["code"]), (404, "bundle_not_found"))
        # The same bytes travel on a phone-opened stream when the link can carry one.
        send, _ = _Send(), _Recv(b"")
        await self.api.serve_attachment(
            {"type": "attachment", "app_token": TOKEN, "path": f"/app/v1/diagnostics/bundle/{bundle_id}"},
            send, None,
        )
        self.assertTrue(send.finished)
        payload, end = _decode(send.writes)
        self.assertEqual(end["total"], body_size := len(payload))
        self.assertEqual(end["sha256"], hashlib.sha256(payload).hexdigest())
        self.assertTrue(payload.startswith(b"PK"))
        self.assertEqual(body_size, page["total"])

    async def test_attachment_rejects_a_wrong_token(self) -> None:
        send = _Send()
        await self.api.serve_attachment(
            {"type": "attachment", "app_token": "nope", "path": "/app/v1/calls/x/audio"}, send, None,
        )
        self.assertTrue(send.finished)
        [message] = [json.loads(payload) for kind, payload in _frames(send.writes) if kind == TEXT]
        self.assertEqual(message["code"], "unauthorized")


class UpdateArtifactTests(_ApiCase):
    def _publish(self, manifest: dict | None, files: dict[str, bytes] | None = None) -> None:
        updates = self.state / "updates"
        updates.mkdir(parents=True, exist_ok=True)
        if manifest is not None:
            (updates / "manifest.json").write_text(json.dumps(manifest))
        for name, data in (files or {}).items():
            (updates / name).write_bytes(data)

    async def test_the_manifest_artifact_is_served_paged_and_streamed(self) -> None:
        payload = b"apk-bytes" * 100
        self._publish(
            {"apk": {"file": "app.apk"}, "module": {"file": "module.zip"}},
            {"app.apk": payload, "module.zip": b"zip-bytes"},
        )
        status, body = await self.call(
            "GET", "/app/v1/update/artifact", query={"name": "apk", "limit": 64}
        )
        self.assertEqual(status, 200)
        self.assertEqual(body["total"], len(payload))
        self.assertEqual(base64.b64decode(body["data"]), payload[:64])

        status, body = await self.call("GET", "/app/v1/update/artifact", query={"name": "module"})
        self.assertEqual((status, base64.b64decode(body["data"])), (200, b"zip-bytes"))

        send = _Send()
        await self.api.serve_attachment(
            {"app_token": TOKEN, "path": "/app/v1/update/artifact", "query": {"name": "apk"}},
            send,
            None,
        )
        await asyncio.wait_for(send.done.wait(), 2)
        streamed, end = _decode(send.writes)
        self.assertEqual(streamed, payload)
        self.assertEqual(end["total"], len(payload))

    async def test_a_channel_entry_is_where_the_file_is_named(self) -> None:
        self._publish(
            {"channels": {"stable": {"apk": {"file": "app.apk"}}}},
            {"app.apk": b"channel-apk"},
        )
        status, body = await self.call("GET", "/app/v1/update/artifact", query={"name": "apk"})
        self.assertEqual((status, base64.b64decode(body["data"])), (200, b"channel-apk"))

    async def test_a_manifest_file_name_cannot_escape_the_updates_directory(self) -> None:
        (self.state / "secret.txt").write_text("secret")
        for name in ("../secret.txt", "sub/app.apk", "..", "app/../secret.txt"):
            self._publish({"apk": {"file": name}})
            status, body = await self.call("GET", "/app/v1/update/artifact", query={"name": "apk"})
            self.assertEqual((status, body["code"]), (400, "invalid_argument"), name)
            self.assertNotIn("secret", json.dumps(body))

    async def test_a_query_can_only_name_an_artifact_not_a_path(self) -> None:
        self._publish({"apk": {"file": "app.apk"}}, {"app.apk": b"x"})
        for name in ("../manifest.json", "apk/../manifest.json", "keys"):
            status, body = await self.call("GET", "/app/v1/update/artifact", query={"name": name})
            self.assertEqual((status, body["code"]), (400, "invalid_argument"), name)

    async def test_a_missing_artifact_or_manifest_is_artifact_unavailable(self) -> None:
        status, body = await self.call("GET", "/app/v1/update/artifact", query={"name": "apk"})
        self.assertEqual((status, body["code"]), (404, "artifact_unavailable"))

        self._publish({"apk": {"file": "app.apk"}})
        status, body = await self.call("GET", "/app/v1/update/artifact", query={"name": "apk"})
        self.assertEqual((status, body["code"]), (404, "artifact_unavailable"))

        self._publish({"module": {"file": "module.zip"}})
        status, body = await self.call("GET", "/app/v1/update/artifact", query={"name": "apk"})
        self.assertEqual((status, body["code"]), (404, "artifact_unavailable"))


class UpdateAndPairTests(_ApiCase):
    async def test_manifest_channels_and_compat(self) -> None:
        status, body = await self.call("GET", "/app/v1/update/manifest")
        self.assertEqual((status, body["code"]), (404, "manifest_unavailable"))
        (self.state / "updates").mkdir()
        (self.state / "updates" / "manifest.json").write_text(json.dumps({
            "version": "0.2.0",
            "channels": {"stable": {"version": "0.2.0"}, "beta": {"version": "0.3.0-rc1"}},
        }))
        status, body = await self.call("GET", "/app/v1/update/manifest", query={"channel": "beta"})
        self.assertEqual((status, body["entry"]["version"]), (200, "0.3.0-rc1"))
        status, body = await self.call("GET", "/app/v1/update/manifest", query={"channel": "nightly"})
        self.assertEqual((status, body["code"]), (404, "unknown_channel"))
        status, body = await self.call("GET", "/app/v1/compat", query={"app_api": 99, "protocol": 3})
        self.assertEqual((status, body["compatible"]), (200, False))
        status, body = await self.call("GET", "/app/v1/compat", query={"app_api": 1, "protocol": 2})
        self.assertTrue(body["compatible"])

    async def test_rotation_replaces_the_app_token_immediately(self) -> None:
        (self.state / "tokens.json").write_text(json.dumps({"host_token": "h", "device_token": "d", "app_token": TOKEN}))
        status, body = await self.call("POST", "/app/v1/pair/rotate", body={})
        self.assertEqual(status, 200)
        new = body["app_token"]
        self.assertNotEqual(new, TOKEN)
        self.assertFalse(body["restart_required"])
        status, _ = await self.call("GET", "/app/v1/info", token=TOKEN)
        self.assertEqual(status, 401)
        status, _ = await self.call("GET", "/app/v1/info", token=new)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads((self.state / "tokens.json").read_text())["app_token"], new)
        status, body = await self.call("POST", "/app/v1/pair/rotate", body={"device_token": True}, token=new)
        self.assertTrue(body["restart_required"])
        self.assertIsInstance(body["device_token"], str)


class _Send:
    def __init__(self) -> None:
        self.writes: list[bytes] = []
        self.finished = False
        self.done = asyncio.Event()

    async def write_all(self, data: bytes) -> None:
        self.writes.append(data)

    async def finish(self) -> None:
        self.finished = True
        self.done.set()


class _Recv:
    def __init__(self, data: bytes) -> None:
        self._data = data
        self._position = 0

    async def read_exact(self, count: int) -> bytes:
        chunk = self._data[self._position : self._position + count]
        if len(chunk) != count:
            raise LinkClosed()
        self._position += count
        return chunk


def _frames(writes: list[bytes]) -> list[tuple[int, bytes]]:
    out: list[tuple[int, bytes]] = []
    for write in writes:
        position = 0
        while position < len(write):
            kind, length = parse_header(write[position : position + HEADER_BYTES])
            out.append((kind, write[position + HEADER_BYTES : position + HEADER_BYTES + length]))
            position += HEADER_BYTES + length
    return out


def _decode(writes: list[bytes]) -> tuple[bytes, dict[str, Any]]:
    payload = b""
    end: dict[str, Any] = {}
    for kind, data in _frames(writes):
        if kind == TEXT:
            end = json.loads(data)
        else:
            payload += data
    return payload, end


class _FakeLink:
    """A DeviceLink whose messages come from a test, so hello and api share one stream."""

    def __init__(self) -> None:
        self.peer = "iroh:test"
        self.sent: list[Any] = []
        self._inbox: asyncio.Queue[Any] = asyncio.Queue()
        self.closed: tuple[int, str] | None = None
        self.finished = asyncio.Event()

    def route(self) -> str:
        return "direct 127.0.0.1, 2 ms"

    def paths(self) -> list[str]:
        return []

    async def recv(self) -> Any:
        item = await self._inbox.get()
        if isinstance(item, BaseException):
            raise item
        return item

    async def send(self, message: Any) -> None:
        self.sent.append(message)

    async def send_datagram(self, data: bytes) -> bool:
        return False

    async def close(self, code: int, reason: str) -> None:
        self.closed = (code, reason)
        self.finished.set()
        self._inbox.put_nowait(LinkClosed())

    def feed(self, message: Any) -> None:
        self._inbox.put_nowait(message)


class HubTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.hub = DeviceHub()
        self.link = _FakeLink()
        self.serving = asyncio.create_task(self.hub.attach(self.link))

    async def asyncTearDown(self) -> None:
        self.serving.cancel()
        await asyncio.gather(self.serving, return_exceptions=True)

    async def _hello(self, **extra: Any) -> None:
        hello = {"type": "hello", "version": 3, "device": "pixel", "boot_id": "b1", "capabilities": {}, **extra}
        self.link.feed(json.dumps(hello))
        async with asyncio.timeout(2):
            while not self.hub.connected:
                await asyncio.sleep(0.01)

    async def _results(self, count: int) -> list[dict[str, Any]]:
        async with asyncio.timeout(3):
            while len([item for item in self.link.sent if isinstance(item, str) and '"api_result"' in item]) < count:
                await asyncio.sleep(0.01)
        return [json.loads(item) for item in self.link.sent if isinstance(item, str) and '"api_result"' in item]

    async def test_hello_v3_fields_are_kept_and_v2_still_connects(self) -> None:
        await self._hello(app_api=1, preferred_codec="opus", max_bitrate=32000, control_caps=["tone_start"])
        self.assertEqual(self.hub.info["app_api"], 1)
        self.assertEqual(self.hub.info["preferred_codec"], "opus")
        self.assertEqual(self.hub.info["control_caps"], ["tone_start"])
        self.assertEqual(self.hub.info["version"], 3)

    async def test_the_hello_preference_picks_the_next_call_codec(self) -> None:
        self.hub.audio_codec = "pcm"
        self.hub.opus_bitrate = 48_000
        await self._hello(
            preferred_codec="opus", max_bitrate=24_000,
            capabilities={"audio_codecs": ["pcm", "opus"]},
        )
        self.assertEqual(self.hub.stream_codec(), "opus")
        # The phone's cap wins over the daemon's configured bitrate for the next call.
        self.assertEqual(self.hub.begin_stream("c1", 1), {"codec": "opus", "bitrate": 24_000})

    async def test_an_explicit_pcm_preference_wins_over_the_daemon_codec(self) -> None:
        self.hub.audio_codec = "opus"
        await self._hello(preferred_codec="pcm", capabilities={"audio_codecs": ["pcm", "opus"]})
        self.assertEqual(self.hub.stream_codec(), "pcm")
        self.assertEqual(self.hub.begin_stream("c1", 1), {"codec": "pcm"})

    async def test_auto_keeps_the_daemons_configured_codec(self) -> None:
        self.hub.audio_codec = "opus"
        await self._hello(preferred_codec="auto", capabilities={"audio_codecs": ["pcm", "opus"]})
        self.assertEqual(self.hub.stream_codec(), "opus")

    async def test_a_phone_without_opus_falls_back_to_pcm(self) -> None:
        self.hub.audio_codec = "opus"
        await self._hello(preferred_codec="opus", capabilities={"audio_codecs": ["pcm"]})
        self.assertEqual(self.hub.stream_codec(), "pcm")

    async def test_set_prefs_changes_the_next_call_without_a_reconnect(self) -> None:
        self.hub.audio_codec = "opus"
        self.hub.opus_bitrate = 64_000
        await self._hello(
            preferred_codec="pcm", max_bitrate=32_000,
            capabilities={"audio_codecs": ["pcm", "opus"]},
        )
        self.assertEqual(self.hub.stream_codec(), "pcm")
        self.link.feed(json.dumps({"type": "set_prefs", "preferred_codec": "opus", "max_bitrate": 16_000}))
        async with asyncio.timeout(2):
            while self.hub.info.get("max_bitrate") != 16_000:
                await asyncio.sleep(0.01)
        self.assertEqual(self.hub.begin_stream("c1", 1), {"codec": "opus", "bitrate": 16_000})

    async def test_a_nonsense_preference_is_ignored_not_applied(self) -> None:
        await self._hello(preferred_codec="mp3", max_bitrate=10, capabilities={"audio_codecs": ["pcm", "opus"]})
        self.assertIsNone(self.hub.info.get("preferred_codec"))
        self.assertIsNone(self.hub.info.get("max_bitrate"))
        self.link.feed(json.dumps({"type": "set_prefs", "preferred_codec": "opus", "max_bitrate": 900_000}))
        async with asyncio.timeout(2):
            while self.hub.info.get("preferred_codec") != "opus":
                await asyncio.sleep(0.01)
        self.assertEqual(self.hub.info.get("max_bitrate"), None)
        self.assertEqual(self.hub.begin_stream("c1", 1), {"codec": "opus", "bitrate": 32_000})

    async def test_an_unknown_protocol_version_is_refused(self) -> None:
        self.link.feed(json.dumps({"type": "hello", "version": 1, "device": "pixel", "boot_id": "b1", "capabilities": {}}))
        async with asyncio.timeout(2):
            await self.link.finished.wait()
        self.assertEqual(self.link.closed, (1002, "Incompatible device handshake"))

    async def test_api_requests_are_answered_off_the_read_loop(self) -> None:
        """A slow request must not hold a fast one behind it, nor the read loop."""

        async def handler(message: dict[str, Any]) -> tuple[int, dict[str, Any]]:
            if message["path"].endswith("/slow"):
                await asyncio.sleep(0.3)
                return 200, {"slow": True}
            return 200, {"path": message["path"]}

        self.hub.on_api = handler
        await self._hello()
        self.link.feed(json.dumps({"type": "api", "id": "1", "method": "GET", "path": "/app/v1/slow"}))
        await asyncio.sleep(0.02)
        self.link.feed(json.dumps({"type": "api", "id": "2", "method": "GET", "path": "/app/v1/fast"}))
        self.link.feed(json.dumps({"type": "call_state", "call_id": "c1", "state": "dialing"}))
        results = await self._results(2)
        self.assertEqual([(item["id"], item["status"]) for item in results], [("2", 200), ("1", 200)])
        self.assertEqual(results[0]["result"], {"path": "/app/v1/fast"})
        # The control loop kept running while the slow handler waited.
        self.assertEqual(self.hub.info["active_call"], {"call_id": "c1", "state": "dialing"})

    async def test_without_a_handler_an_api_request_is_refused_not_ignored(self) -> None:
        await self._hello()
        self.link.feed(json.dumps({"type": "api", "id": "9", "method": "GET", "path": "/app/v1/info"}))
        [result] = await self._results(1)
        self.assertEqual((result["status"], result["error"]["code"]), (503, "api_unavailable"))

    async def test_an_oversized_result_is_replaced_by_an_error(self) -> None:
        async def handler(message: dict[str, Any]) -> tuple[int, dict[str, Any]]:
            return 200, {"blob": "x" * (MAX_API_RESULT_CHARS + 10)}

        self.hub.on_api = handler
        await self._hello()
        self.link.feed(json.dumps({"type": "api", "id": "big", "method": "GET", "path": "/app/v1/info"}))
        [result] = await self._results(1)
        self.assertEqual((result["status"], result["error"]["code"]), (500, "result_too_large"))

    async def test_a_phone_opened_stream_reaches_the_attachment_handler(self) -> None:
        async def handler(header: dict[str, Any], send: Any, recv: Any) -> None:
            await send.write_all(frame(b"chunk"))
            await send.write_all(frame(json.dumps({"type": "end", "total": 5, "sha256": "x"})))

        self.hub.on_attachment = handler
        send = _Send()
        await self.hub.serve_attachment({"type": "attachment", "path": "/app/v1/x"}, send, None)
        await asyncio.wait_for(send.done.wait(), 2)
        payload, end = _decode(send.writes)
        self.assertEqual((payload, end["total"]), (b"chunk", 5))

    async def test_a_failing_attachment_leaves_one_error_frame(self) -> None:
        await self._hello()
        send = _Send()
        self.hub.on_attachment = None
        await self.hub.serve_attachment({"type": "attachment", "path": "/app/v1/x"}, send, None)
        await asyncio.wait_for(send.done.wait(), 2)
        [message] = [json.loads(data) for kind, data in _frames(send.writes)]
        self.assertEqual(message["type"], "error")


class _RemoteId:
    def fmt_short(self) -> str:
        return "remote1"


class _BiStream:
    def __init__(self, send: _Send, recv: _Recv) -> None:
        self._send = send
        self._recv = recv

    def send(self) -> _Send:
        return self._send

    def recv(self) -> _Recv:
        return self._recv


class _Connection:
    def __init__(self, streams: list[_BiStream]) -> None:
        self._streams = list(streams)

    def remote_id(self) -> _RemoteId:
        return _RemoteId()

    async def accept_bi(self) -> _BiStream:
        if self._streams:
            return self._streams.pop(0)
        await asyncio.Event().wait()
        raise AssertionError("unreachable")


class IrohAttachmentTests(unittest.IsolatedAsyncioTestCase):
    async def test_first_frame_attachment_is_dispatched_and_streamed(self) -> None:
        directories = tempfile.TemporaryDirectory()
        self.addCleanup(directories.cleanup)
        state = Path(directories.name)
        settings = Settings(state_dir=state, device_token="d", host_token="h", app_token=TOKEN)
        store = CallStore(state / "calls.db")
        self.addCleanup(store.close)
        device = _Device()
        controller = CallController(store, device, lambda *args: _Voice(args[2]), _Tasks(), archive_dir=state / "calls")
        await controller.start()
        self.addAsyncCleanup(controller.close)
        api = AppApi(settings, controller)
        archive = state / "calls"
        archive.mkdir()
        call_id = "99999999-1111-2222-3333-444444444444"
        (archive / f"{call_id}.m4a").write_bytes(b"M4A-payload")
        (archive / f"{call_id}.json").write_text(json.dumps({
            "call_id": call_id, "state": "ended", "transcript": [], "marks": [],
            "recording": {"file": None, "compressed": {"file": f"{call_id}.m4a"}},
        }))
        header = {"type": "attachment", "id": "a1", "app_token": TOKEN,
                  "path": f"/app/v1/calls/{call_id}/audio", "query": {"format": "m4a"}}
        send = _Send()
        stream = _BiStream(send, _Recv(frame(json.dumps(header))))
        control_send, control_recv = _Send(), _Recv(b"")
        link = IrohLink(_Connection([stream]), type("Control", (), {"send": lambda self: control_send,
                                                                  "recv": lambda self: control_recv})(),
                        attachment_handler=api.serve_attachment)
        reader = asyncio.create_task(link._read_attachments())
        self.addCleanup(reader.cancel)
        await asyncio.wait_for(send.done.wait(), 2)
        payload, end = _decode(send.writes)
        self.assertEqual(payload, b"M4A-payload")
        self.assertEqual(end["sha256"], hashlib.sha256(b"M4A-payload").hexdigest())
        self.assertTrue(link.supports_attachments)


class RedactionTests(unittest.TestCase):
    def test_tokens_and_bearers_never_survive(self) -> None:
        text = (
            'app_token=abc123secret\n'
            'Authorization: Bearer zzz\neyJ-longopaquevalue0123456789012345678901234567890\n'
            "ordinary log line kept\n"
        )
        redacted = redact(text)
        for secret in ("abc123secret", "zzz", "eyJ-longopaquevalue"):
            self.assertNotIn(secret, redacted)
        self.assertIn("ordinary log line kept", redacted)


if __name__ == "__main__":
    unittest.main()
