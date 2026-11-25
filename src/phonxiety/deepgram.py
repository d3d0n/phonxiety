"""Deepgram Voice Agent backend (wss://agent.deepgram.com/v1/agent/converse).

Deepgram runs listen (STT), think (LLM) and speak (TTS) in one websocket at 48 kHz
linear16 both ways, so phone audio needs no resampling. Mapping to the controller:

- the call functions (`send_dtmf`, `end_call`, `client_delegation`, see interfaces.py) are
  client-side functions reported as delegations; the answer goes back as the
  FunctionCallResponse, which the agent then speaks;
- spoken context (CALL_CONNECTED, CALL_COMPLETE) is injected as a user message and kept
  out of the transcript; commentary context only extends the prompt;
- each ConversationText is a final transcript segment; UserStartedSpeaking is a barge-in.
"""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass
from typing import Any

from websockets.asyncio.client import ClientConnection, connect
from websockets.exceptions import ConnectionClosed

from .audio.format import SAMPLE_RATE
from .audio.pacing import PacedOutput
from .interfaces import CALL_FUNCTIONS, VoiceCallbacks, function_request

log = logging.getLogger(__name__)

AGENT_URL = "wss://agent.deepgram.com/v1/agent/converse"
KEEPALIVE_S = 5.0
CONNECT_TIMEOUT_S = 15.0


@dataclass(frozen=True, slots=True)
class DeepgramOptions:
    api_key: str
    language: str = "en"
    listen_model: str = "nova-3"
    think_model: str = "gpt-4o-mini"
    speak_model: str = "aura-2-thalia-en"


class DeepgramVoiceSession:
    review_at_turn_end = False  # the model calls end_call itself

    def __init__(
        self, instructions: str, callbacks: VoiceCallbacks, options: DeepgramOptions, *, initial_context: str = ""
    ) -> None:
        self._prompt = f"{instructions}\n\n{initial_context}".strip()
        self._callbacks = callbacks
        self._options = options
        self._ws: ClientConnection | None = None
        self._output = PacedOutput(callbacks.audio, self._assistant_done)
        self._tasks: list[asyncio.Task[Any]] = []
        self._send_lock = asyncio.Lock()
        self._injected: list[str] = []
        self._pending_calls: dict[str, str] = {}
        self._closed = False
        self._uplink: asyncio.Queue[bytes] = asyncio.Queue(maxsize=100)  # two seconds of 20 ms frames

    async def start(self) -> None:
        async with asyncio.timeout(CONNECT_TIMEOUT_S):
            self._ws = await connect(
                AGENT_URL, additional_headers={"Authorization": f"Token {self._options.api_key}"}, max_size=None
            )
            welcome = json.loads(await self._ws.recv())
            if welcome.get("type") != "Welcome":
                raise RuntimeError(f"Deepgram did not welcome the session: {welcome.get('type')}")
            await self._ws.send(json.dumps(self._settings()))
            while True:
                event = json.loads(await self._ws.recv())
                if event.get("type") == "SettingsApplied":
                    break
                if event.get("type") == "Error":
                    raise RuntimeError(f"Deepgram rejected the settings: {event.get('description')}")
        self._output.start()
        self._tasks = [
            asyncio.create_task(self._read(), name="deepgram-read"),
            asyncio.create_task(self._keepalive(), name="deepgram-keepalive"),
            asyncio.create_task(self._write_audio(), name="deepgram-uplink"),
        ]
        log.info("Deepgram voice session ready (%s / %s / %s)",
                 self._options.listen_model, self._options.think_model, self._options.speak_model)

    def _settings(self) -> dict[str, Any]:
        options = self._options
        return {
            "type": "Settings",
            "audio": {
                "input": {"encoding": "linear16", "sample_rate": SAMPLE_RATE},
                "output": {"encoding": "linear16", "sample_rate": SAMPLE_RATE, "container": "none"},
            },
            "agent": {
                "language": options.language,
                "listen": {"provider": {"type": "deepgram", "model": options.listen_model}},
                "think": {
                    "provider": {"type": "open_ai", "model": options.think_model},
                    "prompt": self._prompt,
                    "functions": [
                        {
                            "name": name,
                            "description": description,
                            "parameters": {
                                "type": "object",
                                "properties": {key: {"type": "string", "description": text} for key, text in parameters.items()},
                                "required": list(parameters),
                            },
                        }
                        for name, description, parameters in CALL_FUNCTIONS
                    ],
                },
                "speak": {"provider": {"type": "deepgram", "model": options.speak_model}},
            },
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
            await self._send(await self._uplink.get())

    async def append_context(
        self, text: str, *, delegation_id: str | None = None, commentary: bool = False
    ) -> None:
        if delegation_id is not None and delegation_id in self._pending_calls:
            name = self._pending_calls.pop(delegation_id)
            await self._send(json.dumps({"type": "FunctionCallResponse", "id": delegation_id, "name": name, "content": text}))
        elif commentary:
            self._prompt = f"{self._prompt}\n\n{text}"
            await self._send(json.dumps({"type": "UpdatePrompt", "prompt": text}))
        else:
            self._injected.append(text.strip())
            await self._send(json.dumps({"type": "InjectUserMessage", "content": text}))

    async def _send(self, message: str | bytes) -> None:
        ws = self._ws
        if ws is None or self._closed:
            return
        try:
            async with self._send_lock:
                await ws.send(message)
        except ConnectionClosed:
            await self._fail("Deepgram voice connection closed")

    async def _keepalive(self) -> None:
        while not self._closed:
            await asyncio.sleep(KEEPALIVE_S)
            await self._send(json.dumps({"type": "KeepAlive"}))

    async def _read(self) -> None:
        ws = self._ws
        assert ws is not None
        try:
            async for message in ws:
                if isinstance(message, bytes):
                    self._output.feed(message)
                    continue
                await self._handle(json.loads(message))
            # A normal close (1000/1001) ends the loop without an exception: the voice is gone all the same.
            if not self._closed:
                reason = ws.close_reason or "no reason given"
                await self._fail(f"Deepgram closed the voice session: {reason}")
        except ConnectionClosed as exc:
            if not self._closed:
                await self._fail(f"Deepgram voice connection closed: {exc.rcvd.reason if exc.rcvd else exc}")
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - reader boundary
            if not self._closed:
                await self._fail(f"Deepgram voice failed: {type(exc).__name__}: {exc}")

    async def _handle(self, event: dict[str, Any]) -> None:
        kind = event.get("type")
        if kind == "ConversationText":
            role, text = event.get("role"), str(event.get("content", "")).strip()
            if role == "user" and text in self._injected:
                self._injected.remove(text)  # our own injected context, not speech on the line
                return
            if role in ("user", "assistant") and text:
                await self._callbacks.transcript(role, text, True)
                if role == "user":
                    await self._callbacks.turn_done("user")
        elif kind == "UserStartedSpeaking":
            if not self._injected:
                self._output.clear()
        elif kind == "AgentAudioDone":
            self._output.drain_then_notify()
        elif kind == "FunctionCallRequest":
            for call in event.get("functions", []):
                if not call.get("client_side"):
                    continue
                call_id, name = str(call.get("id")), str(call.get("name"))
                try:
                    arguments = json.loads(call.get("arguments") or "{}")
                except (TypeError, ValueError):
                    arguments = {}
                request = function_request(name, arguments if isinstance(arguments, dict) else {})
                if request is None:
                    await self._send(json.dumps({"type": "FunctionCallResponse", "id": call_id, "name": name,
                                                 "content": "Unsupported or invalid call."}))
                    continue
                self._pending_calls[call_id] = name
                await self._callbacks.delegation(call_id, request)
        elif kind == "Error":
            await self._fail(f"Deepgram error {event.get('code')}: {event.get('description')}")
        elif kind == "Warning":
            log.warning("Deepgram warning %s: %s", event.get("code"), event.get("description"))

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
