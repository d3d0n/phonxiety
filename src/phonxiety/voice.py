"""Standalone Codex realtime voice session.

The daemon owns the WebRTC peer: one Opus audio transceiver plus the
``oai-events`` data channel.  Signaling posts an SDP offer and the live session
object to the Codex backend, then the Frameless Bidi sideband carries control,
context appends and delegation events.  Audio never touches local speakers or a
microphone: callers push PCM downlink audio in and receive decoded PCM uplink
audio through a callback.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import time
import uuid
from collections import deque
from typing import Any
from urllib.parse import quote

import httpx
from aiortc import RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import MediaStreamError
from websockets.asyncio.client import connect as websocket_connect
from websockets.exceptions import ConnectionClosed, InvalidHandshake

from .audio.convert import ToMono48k
from .audio.webrtc import PcmSourceTrack
from .auth import (
    CODEX_BASE_URL,
    CODEX_CLIENT_VERSION,
    CodexAuth,
    CodexCredentials,
    codex_request_headers,
)
from .interfaces import VoiceCallbacks
from .native.devicecheck import codex_attestation_async

logger = logging.getLogger(__name__)

LIVE_MODEL = "gpt-live-1.1-codex"  # the official Codex client's default since openai/codex#61259
CONTEXT_CHUNK_BYTES = 500
MAX_OUTPUT_FRAMES = 48  # one second at 20 ms
SIDEBAND_ATTEMPTS = 5
SIDEBAND_CONNECT_TIMEOUT_S = 15.0
CHANNEL_OPEN_TIMEOUT_S = 10.0
ICE_GATHER_TIMEOUT_S = 5.0
SESSION_CLOSE_TIMEOUT_S = 0.5
TASK_CLOSE_TIMEOUT_S = 1.5
SIDEBAND_CLOSE_TIMEOUT_S = 1.0
PC_CLOSE_TIMEOUT_S = 1.0
MAX_ERROR_BODY = 2048
LIVE_CALL_ID_PATTERN = re.compile(r"^rtc_[\w-]+$")
SIGNALING_PATH = "/codex/realtime/calls?intent=quicksilver&architecture=avas"
SIDEBAND_URL = "wss://api.openai.com/v1/live/{call_id}"


class VoiceSessionError(RuntimeError):
    """Base class for realtime voice failures."""


class VoiceSignalingError(VoiceSessionError):
    """Codex rejected or failed the SDP signaling request."""


class VoiceSidebandError(VoiceSessionError):
    """The Frameless Bidi sideband could not be established."""


class VoiceSessionClosed(VoiceSessionError):
    """A control operation was attempted after close."""

    def __init__(self) -> None:
        super().__init__("the Codex voice session is closed")


class VoiceSessionNotReady(VoiceSessionError):
    """A control operation was attempted before ``start()`` completed."""

    def __init__(self) -> None:
        super().__init__("the Codex voice session is not ready; call start() first")


def codex_base_url() -> str:
    configured = os.environ.get("PHONXIETY_CODEX_BASE_URL", "").strip()
    return (configured or CODEX_BASE_URL).rstrip("/")


def codex_client_version() -> str:
    return (
        os.environ.get("PHONXIETY_CODEX_CLIENT_VERSION", "").strip()
        or CODEX_CLIENT_VERSION
    )


def chunk_utf8(text: str, limit: int = CONTEXT_CHUNK_BYTES) -> list[str]:
    """Split text into character-safe chunks of at most ``limit`` UTF-8 bytes."""
    if not isinstance(text, str):
        raise TypeError("context text must be a string")
    if limit <= 0:
        raise ValueError("chunk limit must be positive")
    if text == "":
        return [""]
    chunks: list[str] = []
    start = 0
    used = 0
    index = 0
    while index < len(text):
        char = text[index]
        width = len(char.encode("utf-8"))
        if used + width > limit:
            chunks.append(text[start:index])
            start = index
            used = 0
        used += width
        index += 1
    chunks.append(text[start:])
    return chunks


def parse_live_call_id(location: str | None) -> str | None:
    """Extract the server-assigned ``rtc_*`` id from a Location header."""
    if not location:
        return None
    path = location.split("?", 1)[0]
    for segment in path.split("/"):
        if LIVE_CALL_ID_PATTERN.match(segment):
            return segment
    return None


class _TranscriptAssembler:
    """Role-local transcript coalescing for cumulative or delta text events."""

    __slots__ = ("_text", "_final", "_emitted")

    def __init__(self) -> None:
        self._text: dict[str, str] = {"user": "", "assistant": ""}
        self._final: dict[str, bool] = {"user": False, "assistant": False}
        self._emitted: dict[str, tuple[str, bool]] = {}

    def _normalize(self, role: str) -> str | None:
        if role not in ("user", "assistant"):
            return None
        return role

    def added(self, role: str, text: str) -> tuple[str, str, bool] | None:
        role = self._normalize(role)  # type: ignore[assignment]
        if role is None or not text:
            return None
        current = self._text[role]
        was_final = self._final[role]
        if not current:
            next_text = text
        elif was_final:
            next_text = text
        elif text.startswith(current):
            next_text = text
        elif current.endswith(text):
            return None
        else:
            next_text = current + text
        return self._store(role, next_text, False)

    def finished(self, role: str, text: str) -> tuple[str, str, bool] | None:
        role = self._normalize(role)  # type: ignore[assignment]
        if role is None or not text:
            return None
        current = self._text[role]
        was_final = self._final[role]
        if not current:
            next_text = text
        elif was_final:
            if text == current:
                return None
            next_text = text
        else:
            next_text = text
        return self._store(role, next_text, True)

    def _store(
        self, role: str, text: str, final: bool
    ) -> tuple[str, str, bool] | None:
        normalized = text.strip() if final else text
        self._text[role] = text
        self._final[role] = final
        if not normalized.strip():
            return None
        previous = self._emitted.get(role)
        if previous == (normalized, final):
            return None
        self._emitted[role] = (normalized, final)
        return role, normalized, final


class CodexVoiceSession:
    """One Codex realtime call with phone-owned audio I/O."""

    # Client delegation carries no function tools, so the model only says goodbye (see brief.j2).
    review_at_turn_end = True

    def __init__(
        self,
        instructions: str,
        callbacks: VoiceCallbacks,
        *,
        auth: CodexAuth,
        voice: str = "sol",
        initial_context: str | None = None,
    ) -> None:
        if not isinstance(instructions, str) or not instructions.strip():
            raise ValueError("voice instructions must be a non-empty string")
        if not isinstance(voice, str) or not voice.strip():
            raise ValueError("voice must be a non-empty string")
        self._instructions = instructions
        self._initial_context = initial_context
        self._callbacks = callbacks
        self._auth = auth
        self._voice = voice.strip()

        self._pc: RTCPeerConnection | None = None
        self._channel: Any = None
        self._sideband: Any = None
        self._source = PcmSourceTrack()
        self._remote_track: Any = None

        self._session_id = str(uuid.uuid4())
        self._realtime_session_id = str(uuid.uuid4())
        self._call_id: str | None = None
        self._sdp_answer: str | None = None

        self._state = "idle"
        self._closing = False
        self._closed = False
        self._sideband_open = False
        self._ready = asyncio.Event()
        self._state_lock = asyncio.Lock()
        self._close_lock = asyncio.Lock()
        self._send_lock = asyncio.Lock()
        self._started = False

        self._reader_task: asyncio.Task[None] | None = None
        self._consume_task: asyncio.Task[None] | None = None
        self._emit_task: asyncio.Task[None] | None = None
        self._dc_task: asyncio.Task[None] | None = None
        self._dc_queue: asyncio.Queue[str] = asyncio.Queue(maxsize=256)
        self._output: deque[bytes] = deque(maxlen=MAX_OUTPUT_FRAMES)
        self._output_ready = asyncio.Event()

        self._transcript = _TranscriptAssembler()
        self._error_reported = False
        self._audio_callback_error_reported = False

    # -- properties ----------------------------------------------------

    @property
    def state(self) -> str:
        return self._state

    @property
    def call_id(self) -> str | None:
        return self._call_id

    @property
    def session_id(self) -> str:
        return self._session_id

    @property
    def sdp_answer(self) -> str | None:
        return self._sdp_answer

    @property
    def is_ready(self) -> bool:
        return self._ready.is_set() and not self._closed

    # -- start ---------------------------------------------------------

    async def start(self) -> None:
        """Signal a call and return once media and the sideband are ready."""
        async with self._state_lock:
            if self._closed:
                raise VoiceSessionClosed()
            if self._started:
                if self._ready.is_set() and not self._closed:
                    return
                raise VoiceSessionError("this voice session has already been started")
            self._started = True
        self._state = "connecting"
        try:
            await self._connect()
        except asyncio.CancelledError:
            await self.close()
            raise
        except Exception as exc:
            message = str(exc) or type(exc).__name__
            logger.warning("Codex voice start failed: %s", message)
            if not self._closed:
                await self._report_error(message)
            await self.close()
            raise
        if self._closed:
            raise VoiceSessionError(
                "the Codex voice session closed while starting"
            )
        self._state = "ready"
        self._ready.set()

    def _session_headers(
        self, credentials: CodexCredentials, attestation: str | None
    ) -> tuple[dict[str, str], dict[str, str]]:
        sideband_headers = codex_request_headers(
            credentials,
            session_id=self._session_id,
            realtime_session_id=self._realtime_session_id,
            attestation=attestation,
            client_version=codex_client_version(),
        )
        sideband_headers["OpenAI-Alpha"] = "quicksilver=v2"
        signaling_headers = dict(sideband_headers)
        signaling_headers.update(
            {"Accept": "*/*", "Content-Type": "application/json"}
        )
        return sideband_headers, signaling_headers

    async def _connect(self) -> None:
        credentials = await self._auth.credentials()
        attestation = await codex_attestation_async()
        sideband_headers, headers = self._session_headers(
            credentials, attestation
        )

        pc = RTCPeerConnection()
        self._pc = pc

        @pc.on("track")
        def _on_track(track: Any) -> None:
            if getattr(track, "kind", None) != "audio":
                return
            self._remote_track = track
            if self._consume_task is None or self._consume_task.done():
                self._consume_task = asyncio.create_task(
                    self._consume_remote(track), name="phonxiety-remote-audio"
                )

        @pc.on("connectionstatechange")
        def _on_connection_state() -> None:
            state = pc.connectionState
            if state in ("failed", "closed") and not self._closing:
                asyncio.create_task(
                    self._fail(f"Codex voice media connection is {state}")
                )

        @pc.on("datachannel")
        def _on_datachannel(channel: Any) -> None:
            if getattr(channel, "label", None) == "oai-events":
                self._attach_channel(channel)

        pc.addTrack(self._source)
        if self._dc_task is None:
            self._dc_task = asyncio.create_task(
                self._run_datachannel_events(), name="phonxiety-datachannel"
            )
        channel = pc.createDataChannel("oai-events")
        self._attach_channel(channel)

        offer = await pc.createOffer()
        await pc.setLocalDescription(offer)
        await self._wait_ice_gathering(pc)
        local = pc.localDescription
        if local is None:
            raise VoiceSignalingError("WebRTC local description was not created")

        session_payload = {
            "model": LIVE_MODEL,
            "instructions": self._instructions,
            "audio": {"output": {"voice": self._voice}},
            # No spoken filler ("one moment") when a request goes to the backend, as in the Codex client.
            "delegation": {"type": "client", "ack_filler": False},
        }
        if self._initial_context:
            session_payload["initial_items"] = [{
                "type": "message",
                "role": "developer",
                "content": [{"type": "input_text", "text": self._initial_context}],
            }]
        url = codex_base_url() + SIGNALING_PATH
        timeout = httpx.Timeout(30.0, connect=10.0)
        body = json.dumps(
            {"sdp": local.sdp, "session": session_payload},
            ensure_ascii=False,
        ).encode("utf-8")
        response: httpx.Response | None = None
        answer = ""
        for attempt in (0, 1):
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    response = await client.post(
                        url, headers=headers, content=body
                    )
                    answer = response.text
            except httpx.HTTPError as exc:
                raise VoiceSignalingError(
                    f"Codex live signaling request failed: {type(exc).__name__}"
                ) from exc
            if response.status_code == 401 and attempt == 0:
                logger.info(
                    "Codex live signaling returned 401; refreshing OAuth token"
                )
                credentials = await self._auth.force_refresh(
                    credentials.access_token
                )
                sideband_headers, headers = self._session_headers(
                    credentials, attestation
                )
                continue
            break
        if response is None:
            raise VoiceSignalingError("Codex live signaling made no request")
        if response.status_code >= 400:
            detail = self._bounded(answer, response.reason_phrase)
            raise VoiceSignalingError(
                f"Codex live signaling failed ({response.status_code}): {detail}"
            )
        if not answer.strip():
            raise VoiceSignalingError(
                "Codex live signaling returned an empty SDP answer"
            )
        call_id = parse_live_call_id(response.headers.get("location"))
        if call_id is None:
            raise VoiceSignalingError(
                "Codex live signaling returned no valid call id in Location"
            )
        if self._closing or self._closed:
            raise VoiceSessionClosed()
        self._call_id = call_id
        self._sdp_answer = answer

        await pc.setRemoteDescription(
            RTCSessionDescription(sdp=answer, type="answer")
        )
        await self._wait_channel_open(pc, CHANNEL_OPEN_TIMEOUT_S)
        if self._closing or self._closed:
            raise VoiceSessionClosed()

        # Keep consuming remote audio before the sideband finishes connecting so
        # the receiver queue never accumulates stale audio.
        if self._emit_task is None:
            self._emit_task = asyncio.create_task(
                self._emit_output(), name="phonxiety-audio-output"
            )
        await self._connect_sideband(call_id, sideband_headers)
        if self._closing or self._closed:
            sideband = self._sideband
            if sideband is not None:
                try:
                    await sideband.close(code=1000, reason="cancelled")
                except Exception:  # noqa: BLE001 - startup cancellation boundary
                    pass
            raise VoiceSessionClosed()
        self._sideband_open = True
        self._reader_task = asyncio.create_task(
            self._read_sideband(), name="phonxiety-sideband"
        )
        logger.info("Codex voice session ready (call %s)", call_id)

    async def _wait_ice_gathering(self, pc: RTCPeerConnection) -> None:
        deadline = time.monotonic() + ICE_GATHER_TIMEOUT_S
        while pc.iceGatheringState != "complete":
            if time.monotonic() > deadline:
                raise VoiceSignalingError(
                    "WebRTC ICE gathering did not complete in time"
                )
            await asyncio.sleep(0.02)

    async def _wait_channel_open(
        self, pc: RTCPeerConnection, timeout_s: float
    ) -> None:
        deadline = time.monotonic() + timeout_s
        while True:
            channel = self._channel
            if channel is not None and channel.readyState == "open":
                return
            if pc.connectionState in ("failed", "closed"):
                raise VoiceSignalingError(
                    f"WebRTC connection is {pc.connectionState} before the data channel opened"
                )
            if time.monotonic() > deadline:
                raise VoiceSignalingError(
                    "Codex voice data channel did not open in time"
                )
            await asyncio.sleep(0.01)

    async def _connect_sideband(
        self, call_id: str, headers: dict[str, str]
    ) -> None:
        url = SIDEBAND_URL.format(call_id=quote(call_id, safe=""))
        sideband_headers = dict(headers)
        user_agent = sideband_headers.pop("User-Agent", None)
        last: Exception | None = None
        for attempt in range(SIDEBAND_ATTEMPTS):
            if self._closing:
                raise VoiceSidebandError(
                    "Codex voice session closed while connecting its sideband"
                )
            try:
                self._sideband = await websocket_connect(
                    url,
                    additional_headers=sideband_headers,
                    user_agent_header=user_agent,
                    open_timeout=SIDEBAND_CONNECT_TIMEOUT_S,
                    ping_interval=20,
                    ping_timeout=20,
                    close_timeout=5,
                    max_size=1 << 20,
                )
                return
            except (OSError, asyncio.TimeoutError, InvalidHandshake, ConnectionClosed) as exc:
                last = exc
                if attempt + 1 < SIDEBAND_ATTEMPTS:
                    await asyncio.sleep(0.2 * (2**attempt))
        raise VoiceSidebandError(
            f"Codex live sideband connection failed: {type(last).__name__ if last else 'unknown'}"
        )

    # -- control -------------------------------------------------------

    def push_audio(self, pcm: bytes) -> None:
        """Queue PCM16LE mono 48 kHz downlink audio; never blocks or awaits."""
        if self._closed or self._closing:
            return
        self._source.push(pcm)

    async def append_context(
        self,
        text: str,
        *,
        delegation_id: str | None = None,
        commentary: bool = False,
    ) -> None:
        """Append UTF-8-safe context through the Codex sideband."""
        if self._closed or self._closing:
            raise VoiceSessionClosed()
        if not self._ready.is_set():
            raise VoiceSessionNotReady()
        chunks = chunk_utf8(text)
        channel = "commentary" if commentary else None
        async with self._send_lock:
            for chunk in chunks:
                if self._closed or self._closing:
                    raise VoiceSessionClosed()
                if delegation_id is None:
                    message: dict[str, Any] = {
                        "type": "session.context.append",
                        "content": [{"type": "input_text", "text": chunk}],
                    }
                else:
                    message = {
                        "type": "delegation.context.append",
                        "delegation_item_id": delegation_id,
                        "content": [{"type": "input_text", "text": chunk}],
                    }
                if channel is not None:
                    message["channel"] = channel
                await self._send_control(message)

    async def _send_control(self, message: dict[str, Any]) -> None:
        sideband = self._sideband
        if sideband is None or not self._sideband_open:
            raise VoiceSessionNotReady()
        try:
            await sideband.send(
                json.dumps(message, ensure_ascii=False, separators=(",", ":"))
            )
        except ConnectionClosed as exc:
            raise VoiceSessionClosed() from exc

    # -- inbound events ------------------------------------------------

    def _attach_channel(self, channel: Any) -> None:
        if self._channel is channel:
            return
        self._channel = channel

        def _on_message(message: Any) -> None:
            if isinstance(message, bytes):
                logger.debug("ignoring binary Codex data channel frame")
                return
            if not isinstance(message, str):
                return
            try:
                self._dc_queue.put_nowait(message)
            except asyncio.QueueFull:
                logger.debug("dropping Codex data channel event: queue full")

        channel.on("message", _on_message)

    async def _run_datachannel_events(self) -> None:
        try:
            while not self._closing and not self._closed:
                raw = await self._dc_queue.get()
                try:
                    await self._handle_raw_event(raw, from_sideband=False)
                except asyncio.CancelledError:
                    raise
                except Exception as exc:  # noqa: BLE001 - data-channel event boundary
                    if not self._closing:
                        await self._fail(
                            f"Codex data channel event failed: {type(exc).__name__}: {exc}"
                        )
        except asyncio.CancelledError:
            raise

    async def _read_sideband(self) -> None:
        sideband = self._sideband
        if sideband is None:
            return
        try:
            async for raw in sideband:
                if isinstance(raw, bytes):
                    logger.debug("ignoring binary Codex sideband frame")
                    continue
                await self._handle_raw_event(raw, from_sideband=True)
        except asyncio.CancelledError:
            raise
        except ConnectionClosed as exc:
            if not self._closing:
                await self._fail(
                    f"Codex live sideband closed ({exc.code})"
                    + (f": {exc.reason}" if exc.reason else "")
                )
        except Exception as exc:  # noqa: BLE001 - surface sideband failures
            if not self._closing:
                await self._fail(
                    f"Codex live sideband failed: {type(exc).__name__}: {exc}"
                )

    async def _handle_raw_event(self, raw: str, *, from_sideband: bool) -> None:
        if self._closing or self._closed:
            return
        try:
            payload = json.loads(raw)
        except ValueError:
            return
        if not isinstance(payload, dict):
            return
        event_type = payload.get("type")
        if not isinstance(event_type, str):
            return
        # The data channel and sideband can both deliver early events.  Once the
        # sideband is open it is the single event source; errors stay visible.
        if not from_sideband and self._sideband_open and event_type != "error":
            return
        await self._handle_event(event_type, payload)

    async def _handle_event(
        self, event_type: str, payload: dict[str, Any]
    ) -> None:
        logger.debug("Codex voice event %s", event_type)
        if event_type in ("session.started", "session.updated"):
            return
        if event_type == "output_audio.delta":
            # Media arrives over WebRTC; this mirrored text event is not audio.
            return
        if event_type == "input_transcript.added":
            item = payload.get("item")
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                await self._emit_transcript(
                    self._transcript.added("user", item["text"])
                )
            return
        if event_type == "output_transcript.added":
            item = payload.get("item")
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                await self._emit_transcript(
                    self._transcript.added("assistant", item["text"])
                )
            return
        if event_type == "turn.done":
            turn = payload.get("turn")
            if not isinstance(turn, dict):
                return
            role = turn.get("role")
            transcript = turn.get("transcript")
            if role not in ("user", "assistant"):
                return
            if isinstance(transcript, str) and transcript:
                await self._emit_transcript(
                    self._transcript.finished(role, transcript)
                )
            await self._callbacks.turn_done(role)
            return
        if event_type == "delegation.created":
            item = payload.get("item")
            if not isinstance(item, dict):
                logger.warning("Ignoring a delegation without an item")
                return
            delegation_id = item.get("id")
            content = item.get("content")
            if (
                item.get("target") != "client"
                or not isinstance(delegation_id, str)
                or not delegation_id
                or not isinstance(content, list)
            ):
                logger.warning(
                    "Ignoring a delegation with an unsupported shape (target %r)",
                    item.get("target"),
                )
                return
            parts: list[str] = []
            for entry in content:
                if (
                    isinstance(entry, dict)
                    and entry.get("type") == "input_text"
                    and isinstance(entry.get("text"), str)
                ):
                    parts.append(entry["text"])
            request = "\n".join(parts).strip()
            if not request:
                logger.warning("Ignoring delegation %s without text content", delegation_id)
                return
            await self._callbacks.delegation(delegation_id, request)
            return
        if event_type == "error":
            message = payload.get("message")
            if not isinstance(message, str) or not message:
                error = payload.get("error")
                if isinstance(error, dict):
                    message = error.get("message")
                elif isinstance(error, str):
                    message = error
            if isinstance(message, str) and message:
                await self._fail(message)
            return
        if event_type == "unknown":
            return

    async def _emit_transcript(
        self, event: tuple[str, str, bool] | None
    ) -> None:
        if event is None:
            return
        role, text, final = event
        await self._callbacks.transcript(role, text, final)

    # -- outbound media ------------------------------------------------

    async def _consume_remote(self, track: Any) -> None:
        to_mono = ToMono48k()
        try:
            while not self._closing and not self._closed:
                frame = await track.recv()
                pcm = to_mono.convert(frame)
                if pcm:
                    self._output.append(pcm)
                    self._output_ready.set()
        except asyncio.CancelledError:
            raise
        except MediaStreamError:
            if not self._closing:
                await self._report_nonfatal_error("Codex remote audio stream ended")
        except Exception as exc:
            if not self._closing:
                await self._report_nonfatal_error(f"Codex remote audio failed: {type(exc).__name__}")

    async def _emit_output(self) -> None:
        try:
            while not self._closing and not self._closed:
                await self._output_ready.wait()
                self._output_ready.clear()
                while self._output and not self._closing:
                    pcm = self._output.popleft()
                    try:
                        await self._callbacks.audio(pcm)
                    except asyncio.CancelledError:
                        raise
                    except Exception as exc:  # noqa: BLE001 - sink failure
                        logger.warning(
                            "voice audio callback failed: %s", type(exc).__name__
                        )
                        if not self._audio_callback_error_reported:
                            self._audio_callback_error_reported = True
                            await self._report_nonfatal_error(
                                f"voice audio callback failed: {type(exc).__name__}"
                            )
        except asyncio.CancelledError:
            raise

    # -- errors and teardown -------------------------------------------

    async def _report_error(self, message: str) -> None:
        if self._error_reported:
            return
        self._error_reported = True
        self._state = "error"
        try:
            await self._callbacks.error(message)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - error callback boundary
            logger.debug("voice error callback failed: %s", type(exc).__name__)

    async def _report_nonfatal_error(self, message: str) -> None:
        try:
            await self._callbacks.error(message)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - error callback boundary
            logger.debug("voice error callback failed: %s", type(exc).__name__)

    async def _fail(self, message: str) -> None:
        if self._closed:
            return
        await self._report_error(message)
        await self.close()

    async def close(self) -> None:
        """Tear down control and media; safe to call repeatedly."""
        async with self._close_lock:
            if self._closed:
                return
            self._closing = True
            self._state = "closing"
            sideband = self._sideband
            if sideband is not None and self._sideband_open:
                try:
                    async with asyncio.timeout(SESSION_CLOSE_TIMEOUT_S):
                        await sideband.send('{"type":"session.close"}')
                except (TimeoutError, ConnectionClosed, OSError, VoiceSessionError):
                    pass
                except Exception as exc:  # noqa: BLE001 - best-effort farewell
                    logger.debug("session.close send failed: %s", type(exc).__name__)
            self._sideband_open = False
            current = asyncio.current_task()
            tasks = (
                self._reader_task,
                self._consume_task,
                self._emit_task,
                self._dc_task,
            )
            running = [
                task
                for task in tasks
                if task is not None and task is not current and not task.done()
            ]
            for task in running:
                task.cancel()
            if running:
                _, pending = await asyncio.wait(
                    running, timeout=TASK_CLOSE_TIMEOUT_S
                )
                if pending:
                    logger.debug(
                        "%d voice task(s) ignored cancellation", len(pending)
                    )
            self._reader_task = None
            self._consume_task = None
            self._emit_task = None
            self._dc_task = None
            while not self._dc_queue.empty():
                try:
                    self._dc_queue.get_nowait()
                except asyncio.QueueEmpty:
                    break
            if sideband is not None:
                try:
                    async with asyncio.timeout(SIDEBAND_CLOSE_TIMEOUT_S):
                        await sideband.close(code=1000, reason="done")
                except (TimeoutError, ConnectionClosed, OSError):
                    pass
                except Exception as exc:  # noqa: BLE001 - teardown boundary
                    logger.debug("sideband close failed: %s", type(exc).__name__)
            self._sideband = None
            if self._pc is not None:
                try:
                    async with asyncio.timeout(PC_CLOSE_TIMEOUT_S):
                        await self._pc.close()
                except TimeoutError:
                    logger.debug("peer connection close timed out")
                except Exception as exc:  # noqa: BLE001 - teardown boundary
                    logger.debug(
                        "peer connection close failed: %s", type(exc).__name__
                    )
                self._pc = None
            self._source.stop()
            self._source.clear()
            self._output.clear()
            self._output_ready.set()
            self._ready.clear()
            self._closed = True
            self._state = "closed"

    @staticmethod
    def _bounded(text: str, status_text: str = "") -> str:
        normalized = " ".join(text.split())
        if not normalized:
            return status_text or "empty response body"
        if len(normalized) <= MAX_ERROR_BODY:
            return normalized
        return normalized[:MAX_ERROR_BODY] + "…"

    async def __aenter__(self) -> "CodexVoiceSession":
        await self.start()
        return self

    async def __aexit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        await self.close()


__all__ = [
    "CodexVoiceSession",
    "CONTEXT_CHUNK_BYTES",
    "LIVE_MODEL",
    "VoiceSessionClosed",
    "VoiceSessionError",
    "VoiceSessionNotReady",
    "VoiceSidebandError",
    "VoiceSignalingError",
    "chunk_utf8",
    "codex_base_url",
    "parse_live_call_id",
]
