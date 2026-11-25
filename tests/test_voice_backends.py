from __future__ import annotations

import asyncio
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from typing import Any
from unittest import mock

from phonxiety import far_end
from phonxiety.deepgram import DeepgramOptions, DeepgramVoiceSession
from phonxiety.gemini import GeminiOptions, GeminiVoiceSession
from phonxiety.interfaces import VoiceCallbacks, function_request, line_action
from phonxiety.realtime import FRAME_BYTES, PacedOutput, Resampler
from phonxiety.runtime import voice_factory
from phonxiety.settings import Settings


class _Socket:
    def __init__(self) -> None:
        self.sent: list[Any] = []

    async def send(self, message: Any) -> None:
        self.sent.append(json.loads(message) if isinstance(message, str) else message)


class _Events:
    def __init__(self) -> None:
        self.log: list[tuple[str, ...]] = []

    def callbacks(self) -> VoiceCallbacks:
        async def audio(pcm: bytes) -> None:
            self.log.append(("audio", str(len(pcm))))

        async def transcript(role: str, text: str, final: bool) -> None:
            self.log.append(("transcript", role, text, str(final)))

        async def delegation(item_id: str, request: str) -> None:
            self.log.append(("delegation", item_id, request))

        async def turn_done(role: str) -> None:
            self.log.append(("turn_done", role))

        async def error(message: str) -> None:
            self.log.append(("error", message))

        return VoiceCallbacks(audio, transcript, delegation, turn_done, error)


class PacedOutputTests(unittest.IsolatedAsyncioTestCase):
    async def test_a_burst_plays_at_speech_speed_and_a_barge_in_drops_the_rest(self) -> None:
        emitted: list[float] = []

        async def emit(frame: bytes) -> None:
            emitted.append(time.monotonic())

        output = PacedOutput(emit)
        output.start()
        output.feed(bytes(FRAME_BYTES * 50))  # one second of speech in one burst
        await asyncio.sleep(0.3)
        output.clear()
        await asyncio.sleep(0.2)
        await output.close()
        # ~0.3 s of 20 ms frames plus the jitter lead, not the whole second.
        self.assertTrue(12 <= len(emitted) <= 22, len(emitted))
        self.assertGreater(emitted[-1] - emitted[0], 0.2)


class ResamplerTests(unittest.TestCase):
    def test_rates_convert_by_their_ratio(self) -> None:
        resampler = Resampler(24_000, 48_000)
        out = b"".join(resampler.convert(bytes(480 * 2)) for _ in range(50))  # one second at 24 kHz
        self.assertAlmostEqual(len(out) / 2 / 48_000, 1.0, delta=0.05)


class DeepgramEventTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.events = _Events()
        self.session = DeepgramVoiceSession("Be brief.", self.events.callbacks(), DeepgramOptions(api_key="test"))
        self.socket = _Socket()
        self.session._ws = self.socket  # type: ignore[assignment]

    async def test_injected_context_is_spoken_to_but_kept_out_of_the_transcript(self) -> None:
        await self.session.append_context("CALL_CONNECTED. Greet the other party now.")
        self.assertEqual(self.socket.sent[-1], {"type": "InjectUserMessage", "content": "CALL_CONNECTED. Greet the other party now."})
        await self.session._handle({"type": "ConversationText", "role": "user", "content": "CALL_CONNECTED. Greet the other party now."})
        await self.session._handle({"type": "ConversationText", "role": "user", "content": "Hello?"})
        self.assertEqual(self.events.log, [("transcript", "user", "Hello?", "True"), ("turn_done", "user")])

    async def test_a_delegation_is_answered_through_its_function_call(self) -> None:
        await self.session._handle({"type": "FunctionCallRequest", "functions": [{
            "id": "fc_1", "name": "client_delegation", "arguments": json.dumps({"request": "Press 1"}), "client_side": True,
        }]})
        self.assertEqual(self.events.log, [("delegation", "fc_1", "Press 1")])
        await self.session.append_context("FINAL_RESULT: pressed 1", delegation_id="fc_1")
        self.assertEqual(self.socket.sent[-1], {
            "type": "FunctionCallResponse", "id": "fc_1", "name": "client_delegation", "content": "FINAL_RESULT: pressed 1",
        })


class LineActionTests(unittest.TestCase):
    def test_complete_commands_are_actions_and_qualified_ones_are_not(self) -> None:
        cases = {
            "Press 2 for Russian": ("dtmf", "2"),
            "Нажмите 1, 2, 3": ("dtmf", "123"),
            "DTMF 2": ("dtmf", "2"),
            "Please end the call.": ("end_call", ""),
            "Заверши звонок, разговор окончен": ("end_call", ""),
            "End the call only if the customer agrees": None,
            "Press 2 and then enter my contract number": None,
            "Press 2 if the menu asks": None,
            "Should I hang up?": None,
            "Ask about the price": None,
        }
        for request, expected in cases.items():
            self.assertEqual(line_action(request), expected, request)


class CallFunctionTests(unittest.IsolatedAsyncioTestCase):
    def test_line_actions_map_to_exact_requests_and_bad_digits_are_refused(self) -> None:
        self.assertEqual(function_request("send_dtmf", {"digits": "1 2#"}), "DTMF 12#")
        self.assertEqual(function_request("end_call", {}), "END_CALL")
        self.assertEqual(function_request("client_delegation", {"request": " ask "}), "ask")
        for bad in ({"digits": ""}, {"digits": "12x"}, {"digits": "1" * 33}):
            self.assertIsNone(function_request("send_dtmf", bad))
        self.assertIsNone(function_request("transfer", {}))

    async def test_gemini_tool_calls_become_requests_and_answers_keep_their_function_name(self) -> None:
        events = _Events()
        session = GeminiVoiceSession("Be brief.", events.callbacks(), GeminiOptions(api_key="t"))
        socket = _Socket()
        session._ws = socket  # type: ignore[assignment]
        await session._handle({"toolCall": {"functionCalls": [
            {"id": "a", "name": "send_dtmf", "args": {"digits": "2"}},
            {"id": "b", "name": "end_call", "args": {}},
            {"id": "c", "name": "send_dtmf", "args": {"digits": "x"}},
        ]}})
        self.assertEqual([e for e in events.log if e[0] == "delegation"], [("delegation", "a", "DTMF 2"), ("delegation", "b", "END_CALL")])
        self.assertEqual(socket.sent[-1]["toolResponse"]["functionResponses"][0]["id"], "c")  # refused at once
        await session.append_context("Pressed 2.", delegation_id="a", commentary=True)
        answer = socket.sent[-1]["toolResponse"]["functionResponses"][0]
        self.assertEqual((answer["name"], answer["response"]["scheduling"]), ("send_dtmf", "SILENT"))


class FarEndTests(unittest.TestCase):
    def test_menu_and_mailbox_wording_is_recognised_and_people_are_not(self) -> None:
        self.assertEqual(far_end.classify("Для выбора русского языка нажмите два"), far_end.IVR)
        self.assertEqual(far_end.classify("O'zbek tilini tanlash uchun 1 raqamini bosing"), far_end.IVR)
        self.assertEqual(far_end.classify("Оставьте сообщение после сигнала"), far_end.VOICEMAIL)
        for person in ("Алло, слушаю", "Hilton Tashkent City, reservations, how can I help?", "Нажми один два три."):
            self.assertIsNone(far_end.classify(person), person)


class VoiceFactoryTests(unittest.TestCase):
    def test_gemini_without_model_overrides_uses_the_default_model_and_voice(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "gemini-api-key").write_text("test-key\n")
            settings = Settings(state_dir=Path(directory), device_token="d", host_token="h", voice_backend="gemini")
            environment = {k: v for k, v in os.environ.items()
                           if k not in ("GEMINI_API_KEY", "PHONXIETY_GEMINI_MODEL", "PHONXIETY_GEMINI_VOICE")}
            with mock.patch.dict(os.environ, environment, clear=True):
                session = voice_factory(settings, None)("Be brief.", "Brief.", _Events().callbacks())
        setup = json.loads(json.dumps(session._setup()))["setup"]
        self.assertEqual(setup["model"], "models/gemini-3.8-live")
        self.assertEqual(setup["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"], "Puck")
        self.assertEqual(session._options.api_key, "test-key")


class _ClosedSocket:
    """A socket the server closed normally: iteration just ends."""

    close_reason = "session timeout"

    def __aiter__(self) -> Any:
        return self

    async def __anext__(self) -> Any:
        raise StopAsyncIteration

    async def close(self) -> None:
        pass


class CleanCloseTests(unittest.IsolatedAsyncioTestCase):
    async def test_a_normal_server_close_is_reported_as_a_voice_error(self) -> None:
        for session_type, options in ((DeepgramVoiceSession, DeepgramOptions(api_key="t")), (GeminiVoiceSession, GeminiOptions(api_key="t"))):
            events = _Events()
            session = session_type("Be brief.", events.callbacks(), options)
            session._ws = _ClosedSocket()  # type: ignore[assignment]
            await session._read()
            self.assertTrue(any(entry[0] == "error" and "session timeout" in entry[1] for entry in events.log), session_type)


if __name__ == "__main__":
    unittest.main()
