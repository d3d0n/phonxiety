from __future__ import annotations

import asyncio
import json
import time
import unittest
from typing import Any

from phonxiety.deepgram import DeepgramOptions, DeepgramVoiceSession
from phonxiety.interfaces import VoiceCallbacks
from phonxiety.realtime import FRAME_BYTES, PacedOutput, Resampler


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


if __name__ == "__main__":
    unittest.main()
