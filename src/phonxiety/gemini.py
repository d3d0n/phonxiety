"""Gemini Live backend (BidiGenerateContent over websocket).

Gemini takes 16 kHz and returns 24 kHz mono PCM, so phone audio is resampled both ways and
the reply is paced to playback speed. Mapping to the controller:

- the call functions (`send_dtmf`, `end_call`, `client_delegation`, see interfaces.py) are
  reported as delegations; the answer is the tool response;
- context is a user turn in clientContent; commentary context does not complete the turn,
  so the model takes it in without answering;
- input/output transcription arrives in pieces: they are reported as partials and become
  one final segment per turn; `interrupted` is a barge-in.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import re
from dataclasses import dataclass
from typing import Any

from websockets.asyncio.client import ClientConnection, connect
from websockets.exceptions import ConnectionClosed

from .interfaces import CALL_FUNCTIONS, VoiceCallbacks, function_request
from .realtime import SAMPLE_RATE, PacedOutput, Resampler

log = logging.getLogger(__name__)
# The key goes in a header, not the URL, so no connection error can echo it.
LIVE_URL = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
)
INPUT_RATE = 16_000
OUTPUT_RATE = 24_000
CONNECT_TIMEOUT_S = 15.0
DEFAULT_MODEL = "gemini-3.8-live"
DEFAULT_VOICE = "Puck"
_MARKERS = re.compile(r"<[^<>]*>|\{[^{}]*\}")


@dataclass(frozen=True, slots=True)
class GeminiOptions:
    api_key: str
    model: str = DEFAULT_MODEL
    voice: str = DEFAULT_VOICE


class GeminiVoiceSession:
    def __init__(
        self, instructions: str, callbacks: VoiceCallbacks, options: GeminiOptions, *, initial_context: str = ""
    ) -> None:
        self._instructions = f"{instructions}\n\n{initial_context}".strip()
        self._callbacks = callbacks
        self._options = options
        self._ws: ClientConnection | None = None
        self._output = PacedOutput(callbacks.audio, self._assistant_done)
        self._to_model = Resampler(SAMPLE_RATE, INPUT_RATE)
        self._from_model = Resampler(OUTPUT_RATE, SAMPLE_RATE)
        self._uplink: asyncio.Queue[bytes] = asyncio.Queue(maxsize=100)
        self._tasks: list[asyncio.Task[Any]] = []
        self._send_lock = asyncio.Lock()
        self._heard = ""  # the other party's words in the current turn
        self._said = ""  # the model's words in the current turn
        self._pending_calls: dict[str, str] = {}  # function call id -> function name
        self._closed = False

    async def start(self) -> None:
        async with asyncio.timeout(CONNECT_TIMEOUT_S):
            self._ws = await connect(LIVE_URL, additional_headers={"x-goog-api-key": self._options.api_key}, max_size=None)
            await self._ws.send(json.dumps(self._setup()))
            while True:
                event = json.loads(await self._ws.recv())
                if "setupComplete" in event:
                    break
                if "error" in event:
                    raise RuntimeError(f"Gemini rejected the setup: {event['error']}")
        self._output.start()
        self._tasks = [
            asyncio.create_task(self._read(), name="gemini-read"),
            asyncio.create_task(self._write_audio(), name="gemini-uplink"),
        ]
        log.info("Gemini voice session ready (%s, voice %s)", self._options.model, self._options.voice)

    def _setup(self) -> dict[str, Any]:
        return {
            "setup": {
                "model": f"models/{self._options.model}",
                "generationConfig": {
                    "responseModalities": ["AUDIO"],
                    "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": self._options.voice}}},
                },
                "systemInstruction": {"parts": [{"text": self._instructions}]},
                "tools": [{"functionDeclarations": [
                    {
                        "name": name,
                        # The model keeps the line alive while an action runs; the answer is
                        # spoken once the model finishes its current sentence (WHEN_IDLE).
                        "behavior": "NON_BLOCKING",
                        "description": description,
                        "parameters": {
                            "type": "OBJECT",
                            "properties": {key: {"type": "STRING", "description": text} for key, text in parameters.items()},
                            "required": list(parameters),
                        },
                    }
                    for name, description, parameters in CALL_FUNCTIONS
                ]}],
                "inputAudioTranscription": {},
                "outputAudioTranscription": {},
            }
        }

    def push_audio(self, pcm: bytes) -> None:
        """Queue phone downlink audio; never blocks. A stalled socket drops the oldest audio."""
        if self._closed:
            return
        if self._uplink.full():
            self._uplink.get_nowait()
        self._uplink.put_nowait(pcm)

    async def _write_audio(self) -> None:
        while not self._closed:
            pcm = self._to_model.convert(await self._uplink.get())
            if pcm:
                await self._send({"realtimeInput": {"audio": {
                    "data": base64.b64encode(pcm).decode(), "mimeType": f"audio/pcm;rate={INPUT_RATE}",
                }}})

    async def append_context(
        self, text: str, *, delegation_id: str | None = None, commentary: bool = False
    ) -> None:
        if delegation_id is not None:
            # Commentary answers (e.g. a keypress done) are silent: the model just learns them.
            await self._send({"toolResponse": {"functionResponses": [{
                "id": delegation_id, "name": self._pending_calls.pop(delegation_id, "client_delegation"),
                "response": {"result": text, "scheduling": "SILENT" if commentary else "WHEN_IDLE"},
            }]}})
            return
        await self._send({"clientContent": {
            "turns": [{"role": "user", "parts": [{"text": text}]}],
            "turnComplete": not commentary,
        }})

    async def _send(self, message: dict[str, Any]) -> None:
        ws = self._ws
        if ws is None or self._closed:
            return
        try:
            async with self._send_lock:
                await ws.send(json.dumps(message))
        except ConnectionClosed:
            await self._fail("Gemini voice connection closed")

    async def _read(self) -> None:
        ws = self._ws
        assert ws is not None
        try:
            async for message in ws:
                await self._handle(json.loads(message))
            # A normal close (1000/1001) ends the loop without an exception: the voice is gone all the same.
            if not self._closed:
                reason = ws.close_reason or "no reason given"
                await self._fail(f"Gemini closed the voice session: {reason}")
        except ConnectionClosed as exc:
            if not self._closed:
                reason = exc.rcvd.reason if exc.rcvd else str(exc)
                await self._fail(f"Gemini voice connection closed: {reason}")
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - reader boundary
            if not self._closed:
                await self._fail(f"Gemini voice failed: {type(exc).__name__}: {exc}")

    async def _handle(self, event: dict[str, Any]) -> None:
        content = event.get("serverContent")
        if isinstance(content, dict):
            if content.get("interrupted"):
                self._output.clear()
                await self._flush_said()
            heard = (content.get("inputTranscription") or {}).get("text")
            if heard:
                self._heard += heard
                await self._callbacks.transcript("user", self._heard.strip(), False)
            parts = (content.get("modelTurn") or {}).get("parts") or []
            for part in parts:
                data = (part.get("inlineData") or {}).get("data")
                if data:
                    await self._flush_heard()
                    self._output.feed(self._from_model.convert(base64.b64decode(data)))
            said = (content.get("outputTranscription") or {}).get("text")
            if said:
                await self._flush_heard()
                self._said += said
                partial = _MARKERS.sub("", self._said).strip()
                if partial:
                    await self._callbacks.transcript("assistant", partial, False)
            if content.get("turnComplete"):
                await self._flush_heard()
                await self._flush_said()
                self._output.drain_then_notify()
        call = event.get("toolCall")
        if isinstance(call, dict):
            await self._flush_heard()
            for function in call.get("functionCalls", []):
                call_id, name = str(function.get("id")), str(function.get("name"))
                request = function_request(name, function.get("args") or {})
                if request is None:
                    await self._send({"toolResponse": {"functionResponses": [{
                        "id": call_id, "name": name, "response": {"error": "Unsupported or invalid call."},
                    }]}})
                    continue
                self._pending_calls[call_id] = name
                await self._callbacks.delegation(call_id, request)
        if "goAway" in event:
            log.warning("Gemini will close the session soon: %s", event["goAway"])

    async def _flush_heard(self) -> None:
        text, self._heard = self._heard.strip(), ""
        if text:
            await self._callbacks.transcript("user", text, True)
            await self._callbacks.turn_done("user")

    async def _flush_said(self) -> None:
        # Gemini's output transcription marks silence with tokens like "<no speech>{pause}".
        text, self._said = _MARKERS.sub("", self._said).strip(), ""
        if text:
            await self._callbacks.transcript("assistant", text, True)

    async def _assistant_done(self) -> None:
        await self._callbacks.turn_done("assistant")

    async def _fail(self, message: str) -> None:
        if self._closed:
            return
        log.warning("%s", message)
        try:
            await self._callbacks.error(message)
        finally:
            await self.close()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        current = asyncio.current_task()
        for task in self._tasks:
            if task is not current:
                task.cancel()
        await asyncio.gather(*(task for task in self._tasks if task is not current), return_exceptions=True)
        await self._output.close()
        ws, self._ws = self._ws, None
        if ws is not None:
            try:
                async with asyncio.timeout(1):
                    await ws.close()
            except Exception:  # noqa: BLE001 - teardown
                pass
