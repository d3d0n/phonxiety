"""Gemini Live backend (BidiGenerateContent over websocket).

Gemini takes 16 kHz and returns 24 kHz mono PCM, so phone audio is resampled both ways and
the reply is paced to playback speed. Mapping to the controller:

- the client delegation is the function `client_delegation`; its answer is the tool response;
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
from dataclasses import dataclass
from typing import Any

from websockets.asyncio.client import ClientConnection, connect
from websockets.exceptions import ConnectionClosed

from .interfaces import VoiceCallbacks
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
DELEGATION_FUNCTION = "client_delegation"
DEFAULT_MODEL = "gemini-3.8-live"
DEFAULT_VOICE = "Puck"


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
                "tools": [{"functionDeclarations": [{
                    "name": DELEGATION_FUNCTION,
                    "description": (
                        "Create a client delegation: ask the backend that owns the telephone line to act "
                        "(press keypad digits, look something up, decide). Give a complete plain-language request."
                    ),
                    "parameters": {
                        "type": "OBJECT",
                        "properties": {"request": {"type": "STRING", "description": "What the backend should do and why."}},
                        "required": ["request"],
                    },
                }]}],
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
            await self._send({"toolResponse": {"functionResponses": [{
                "id": delegation_id, "name": DELEGATION_FUNCTION, "response": {"result": text},
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
                await self._callbacks.transcript("assistant", self._said.strip(), False)
            if content.get("turnComplete"):
                await self._flush_heard()
                await self._flush_said()
                self._output.drain_then_notify()
        call = event.get("toolCall")
        if isinstance(call, dict):
            await self._flush_heard()
            for function in call.get("functionCalls", []):
                request = (function.get("args") or {}).get("request")
                if function.get("name") != DELEGATION_FUNCTION or not isinstance(request, str) or not request.strip():
                    await self._send({"toolResponse": {"functionResponses": [{
                        "id": function.get("id"), "name": function.get("name"), "response": {"error": "Unsupported request."},
                    }]}})
                    continue
                await self._callbacks.delegation(str(function.get("id")), request)
        if "goAway" in event:
            log.warning("Gemini will close the session soon: %s", event["goAway"])

    async def _flush_heard(self) -> None:
        text, self._heard = self._heard.strip(), ""
        if text:
            await self._callbacks.transcript("user", text, True)
            await self._callbacks.turn_done("user")

    async def _flush_said(self) -> None:
        text, self._said = self._said.strip(), ""
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
