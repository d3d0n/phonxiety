"""TaskAgent: delegated phone work through the Codex Responses endpoint.

The realtime voice model delegates plain-text requests to this text agent.
It reasons over the call brief and transcript, executes the two phone tools the
call controller owns, and returns the final text for the voice agent to speak.
Summaries use a forced function call so the result is structured and validated.

The wire details follow the Codex provider reference at OMP commit
230e7494405bee0188c9597d3c4647828a0824ba
(``packages/ai/src/providers/openai-codex-responses.ts``): ``POST
/codex/responses`` with ``stream: true``, flat function tool objects, SSE
events, and no sampling/output-cap parameters (the Codex backend rejects
them).  This daemon does not run OMP.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import tomllib
import uuid
from collections import OrderedDict
from collections.abc import AsyncIterator, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import httpx

from .auth import (
    CODEX_BASE_URL,
    CODEX_CLIENT_VERSION,
    CodexAuth,
    CodexAuthError,
    CodexCredentials,
    codex_request_headers,
)
from .interfaces import ToolHandler
from .native.devicecheck import codex_attestation_async

logger = logging.getLogger(__name__)

MAX_ROUNDS = 8
MAX_BRIEF_CHARS = 20_000
MAX_REQUEST_CHARS = 8_000
MAX_TRANSCRIPT_CHARS = 60_000
MAX_TOOL_OUTPUT_CHARS = 16_000
MAX_FINAL_TEXT_CHARS = 100_000
MAX_TOOL_ARGUMENT_CHARS = 200_000

TASK_OUTCOMES = (
    "succeeded",
    "declined",
    "incomplete",
    "needs_user",
    "unknown",
)

TASK_INSTRUCTIONS = """\
You are the task agent supervising a live phone call. A separate realtime voice agent talks
to the other party; you cannot hear or speak. You own the telephone line: `send_dtmf`
presses keypad digits on the active call, `finish_call` ends the call and records the
outcome.

You are invoked in one of two situations, named in the request:
- DELEGATED REQUEST: the voice agent asks for an action or a decision.
- LINE STATUS: the line has gone quiet and you decide what it needs.

Rules:
- Work only from the call brief and the transcript. Never pretend to have heard audio or
  to have completed an action.
- Lines of the other party come from automatic speech recognition and can contain misheard
  words. The voice agent hears the audio directly: when the agent repeats or confirms a
  word, name or number and the other party does not correct it, the agent's version is
  what was said.
- Use tools for actions. Do not claim an action succeeded unless the tool result says so.
- DTMF digits are a string made of 0-9, *, #, A-D.
- Transcript lines labelled `phone` record keypad input already sent on this call. Do not
  send the same input again unless the menu asks for it again.
- Call `finish_call` when the conversation is over: the goal is reached or cannot be
  reached, and nothing further is expected from either side. Set `farewell_pending` to
  true only when the agent has not said goodbye yet; the agent then says one brief goodbye
  before the line disconnects.
- Do not finish while a question from either side is unanswered, while the other party
  asked to wait, or while an automated system is still talking. A pause of less than
  about twenty seconds after an unanswered question is normal.
- For a DELEGATED REQUEST return the final text for the voice agent: a short plain-text
  result or an instruction describing what to say or ask. For LINE STATUS reply WAIT when
  no action is needed.
- Return plain text without markdown, lists, or code blocks. Keep it short.
"""

SUMMARY_INSTRUCTIONS = """\
You finalize a finished or ending phone call. Use the `submit_summary` tool exactly once.
Choose `task_outcome` from the allowed values. `summary` is a short factual account of
what happened, including failures or unverified points. `fields` contains only the
requested result fields, as JSON scalars. Do not invent data that is not in the brief or
transcript; use null when unknown. Do not answer with prose instead of the tool call.
Lines of the other party come from automatic speech recognition and can contain misheard
words. The voice agent heard the audio directly: when the agent repeated or confirmed a
word, name or number and the other party did not correct it, record the agent's version.
"""


class TaskAgentError(RuntimeError):
    """Base class for task-agent failures."""


class TaskModelConfigError(TaskAgentError):
    """No task model is configured."""


class TaskProviderError(TaskAgentError):
    """The Codex text endpoint failed or returned an unusable stream."""


class CodexApiError(TaskProviderError):
    """Non-success HTTP response from the Codex Responses endpoint."""

    def __init__(self, status: int, message: str, code: str | None = None) -> None:
        super().__init__(f"Codex Responses failed ({status}): {message}")
        self.status = status
        self.code = code


class CodexRateLimitError(CodexApiError):
    """The Codex account is rate limited or out of usage."""


class _Unauthorized(CodexApiError):
    __slots__ = ("rejected_access_token",)

    def __init__(self, status: int, message: str, token: str, code: str | None) -> None:
        super().__init__(status, message, code)
        self.rejected_access_token = token


class TaskToolError(TaskAgentError):
    """The caller supplied an unsupported tool."""


class TaskResultError(TaskAgentError):
    """Structured summary output failed validation."""


class TaskTurnLimitError(TaskAgentError):
    """The model used more tool rounds than allowed."""


@dataclass(slots=True)
class _ToolCall:
    call_id: str
    name: str
    arguments: str


@dataclass(slots=True)
class _StreamResult:
    text: str
    calls: list[_ToolCall] = field(default_factory=list)


def _bounded(text: str, limit: int, marker: str = "\n...[truncated]") -> str:
    if len(text) <= limit:
        return text
    return text[: max(0, limit - len(marker))] + marker


def _json_text(value: Any) -> str:
    try:
        return json.dumps(value, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        return json.dumps(str(value), ensure_ascii=False)


def default_config_path() -> Path:
    codex_home = os.environ.get("CODEX_HOME", "").strip()
    base = Path(codex_home).expanduser() if codex_home else Path.home() / ".codex"
    return base / "config.toml"


def resolve_task_model(explicit: str | None = None) -> str:
    """Resolve the text model without inventing a model id."""
    if explicit is not None and explicit.strip():
        return explicit.strip()
    configured = os.environ.get("PHONXIETY_CODEX_TASK_MODEL", "").strip()
    if configured:
        return configured
    path = default_config_path()
    if not path.is_file():
        raise TaskModelConfigError(
            "no task model configured: set PHONXIETY_CODEX_TASK_MODEL or add `model` "
            f"to {path}"
        )
    try:
        parsed = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise TaskModelConfigError(
            f"cannot read the Codex model configuration at {path}: {exc}"
        ) from exc
    model = parsed.get("model") if isinstance(parsed, dict) else None
    if isinstance(model, str) and model.strip():
        return model.strip()
    raise TaskModelConfigError(
        f"no `model` in {path}; set PHONXIETY_CODEX_TASK_MODEL explicitly"
    )


def _responses_url(base_url: str | None = None) -> str:
    raw = (base_url or os.environ.get("PHONXIETY_CODEX_BASE_URL", "") or CODEX_BASE_URL)
    normalized = raw.rstrip("/")
    if normalized.endswith("/codex/responses"):
        return normalized
    if normalized.endswith("/codex"):
        return f"{normalized}/responses"
    return f"{normalized}/codex/responses"


def _tool_schemas(names: Mapping[str, ToolHandler]) -> list[dict[str, Any]]:
    schemas: list[dict[str, Any]] = []
    for name in names:
        if name == "send_dtmf":
            schemas.append(
                {
                    "type": "function",
                    "name": "send_dtmf",
                    "description": "Press DTMF digits on the active phone call.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "digits": {
                                "type": "string",
                                "description": "Digits 0-9, *, #, A-D; no spaces.",
                                "minLength": 1,
                                "maxLength": 64,
                            }
                        },
                        "required": ["digits"],
                        "additionalProperties": False,
                    },
                }
            )
        elif name == "finish_call":
            schemas.append(
                {
                    "type": "function",
                    "name": "finish_call",
                    "description": (
                        "End the call and record the outcome. The line disconnects "
                        "at once, or after one goodbye when farewell_pending is true."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "outcome": {
                                "type": "string",
                                "enum": list(TASK_OUTCOMES),
                            },
                            "summary": {
                                "type": "string",
                                "description": "Short factual call summary.",
                                "maxLength": 4000,
                            },
                            "fields": {
                                "type": "object",
                                "description": "Requested result fields.",
                                "additionalProperties": {},
                            },
                            "farewell_pending": {
                                "type": "boolean",
                                "description": (
                                    "True when the voice agent has not said goodbye "
                                    "yet and must say it before the line disconnects."
                                ),
                            },
                        },
                        "required": ["outcome", "summary", "fields", "farewell_pending"],
                        "additionalProperties": False,
                    },
                }
            )
        else:
            raise TaskToolError(
                f"unsupported task tool {name!r}; supported tools are send_dtmf and finish_call"
            )
    return schemas


def _format_transcript(transcript: Sequence[Mapping[str, Any]]) -> str:
    lines: list[str] = []
    for entry in transcript:
        if not isinstance(entry, Mapping):
            continue
        role = entry.get("role")
        text = entry.get("text")
        if not isinstance(role, str) or not isinstance(text, str):
            continue
        text = text.strip()
        if not text:
            continue
        label = "other party" if role == "user" else "agent" if role == "assistant" else role
        lines.append(f"{label}: {text}")
    joined = "\n".join(lines)
    if not joined:
        return "(no transcript yet)"
    return _bounded(joined, MAX_TRANSCRIPT_CHARS)


def _user_content(
    brief: Mapping[str, Any],
    transcript: Sequence[Mapping[str, Any]],
    request: str | None = None,
    *,
    line_status: str | None = None,
    instruction: str | None = None,
) -> str:
    sections = [
        "CALL BRIEF:\n" + _bounded(_json_text(brief), MAX_BRIEF_CHARS),
        "TRANSCRIPT:\n" + _format_transcript(transcript),
    ]
    if request is not None:
        sections.append("DELEGATED REQUEST:\n" + _bounded(request, MAX_REQUEST_CHARS))
    if line_status is not None:
        sections.append("LINE STATUS:\n" + line_status)
    if instruction is not None:
        sections.append(instruction)
    return "\n\n".join(sections)


class _ResponseAccumulator:
    """Collects text and function calls from Codex Responses SSE events."""

    __slots__ = (
        "_text_parts",
        "_text_len",
        "_final_text",
        "_calls",
        "_open_items",
        "_open_indexes",
        "_last_key",
        "finished",
    )

    def __init__(self) -> None:
        self._text_parts: list[str] = []
        self._text_len = 0
        self._final_text: str | None = None
        self._calls: OrderedDict[str, _ToolCall] = OrderedDict()
        self._open_items: dict[str, str] = {}
        self._open_indexes: dict[int, str] = {}
        self._last_key: str | None = None
        self.finished = False

    def feed(self, event_type: str, payload: Mapping[str, Any]) -> None:
        if event_type in ("response.output_text.delta", "response.refusal.delta"):
            delta = payload.get("delta")
            if isinstance(delta, str) and delta:
                self._text_parts.append(delta)
                self._text_len += len(delta)
                if self._text_len > MAX_FINAL_TEXT_CHARS:
                    raise TaskProviderError("task model produced too much text")
            return
        if event_type == "response.output_text.done":
            text = payload.get("text")
            if isinstance(text, str):
                self._final_text = text
            return
        if event_type == "response.refusal.done":
            text = payload.get("refusal")
            if isinstance(text, str):
                self._final_text = text
            return
        if event_type == "response.output_item.added":
            item = payload.get("item")
            if isinstance(item, Mapping):
                self._add_item(item)
            return
        if event_type == "response.output_item.done":
            item = payload.get("item")
            if isinstance(item, Mapping):
                self._finish_item(item)
            return
        if event_type == "response.function_call_arguments.delta":
            delta = payload.get("delta")
            if isinstance(delta, str):
                call = self._call_for(payload)
                if call is not None:
                    call.arguments += delta
                    if len(call.arguments) > MAX_TOOL_ARGUMENT_CHARS:
                        raise TaskProviderError("task tool arguments became too large")
            return
        if event_type == "response.function_call_arguments.done":
            call = self._call_for(payload)
            if call is not None:
                arguments = payload.get("arguments")
                if isinstance(arguments, str) and (
                    arguments or not call.arguments
                ):
                    call.arguments = arguments
            return
        if event_type in ("response.completed", "response.done"):
            self._handle_completed(payload)
            return
        if event_type in ("error", "response.failed"):
            raise TaskProviderError(self._error_message(payload))

    # -- items ---------------------------------------------------------

    def _add_item(self, item: Mapping[str, Any]) -> None:
        if item.get("type") != "function_call":
            return
        call_id = item.get("call_id") or item.get("id")
        name = item.get("name")
        if not isinstance(call_id, str) or not call_id:
            raise TaskProviderError("task model returned a function call without call_id")
        if not isinstance(name, str) or not name:
            raise TaskProviderError("task model returned a function call without a name")
        arguments = item.get("arguments")
        arguments = arguments if isinstance(arguments, str) else ""
        existing = self._calls.get(call_id)
        if existing is None:
            existing = _ToolCall(call_id=call_id, name=name, arguments=arguments)
            self._calls[call_id] = existing
        else:
            existing.name = name
            if arguments:
                existing.arguments = arguments
        item_id = item.get("id")
        if isinstance(item_id, str) and item_id:
            self._open_items[item_id] = call_id
        output_index = item.get("output_index")
        if isinstance(output_index, int):
            self._open_indexes[output_index] = call_id
        self._last_key = call_id

    def _finish_item(self, item: Mapping[str, Any]) -> None:
        if item.get("type") == "function_call":
            self._add_item(item)
            arguments = item.get("arguments")
            call_id = item.get("call_id") or item.get("id")
            if isinstance(call_id, str) and isinstance(arguments, str):
                call = self._calls.get(call_id)
                if call is not None and (arguments or not call.arguments):
                    call.arguments = arguments
            item_id = item.get("id")
            if isinstance(item_id, str):
                self._open_items.pop(item_id, None)
            output_index = item.get("output_index")
            if isinstance(output_index, int):
                self._open_indexes.pop(output_index, None)
            return
        if item.get("type") == "message":
            text = self._message_text(item)
            if text:
                self._final_text = text

    def _call_for(self, payload: Mapping[str, Any]) -> _ToolCall | None:
        item_id = payload.get("item_id")
        key = self._open_items.get(item_id) if isinstance(item_id, str) else None
        if key is None:
            output_index = payload.get("output_index")
            if isinstance(output_index, int):
                key = self._open_indexes.get(output_index)
        if key is None:
            key = self._last_key
        return self._calls.get(key) if key is not None else None

    @staticmethod
    def _message_text(item: Mapping[str, Any]) -> str:
        content = item.get("content")
        if not isinstance(content, list):
            return ""
        parts: list[str] = []
        for entry in content:
            if (
                isinstance(entry, Mapping)
                and entry.get("type") in ("output_text", "text")
                and isinstance(entry.get("text"), str)
            ):
                parts.append(entry["text"])
        return "".join(parts)

    def _handle_completed(self, payload: Mapping[str, Any]) -> None:
        response = payload.get("response")
        if not isinstance(response, Mapping):
            response = payload
        status = response.get("status")
        if status == "incomplete":
            details = response.get("incomplete_details")
            reason = ""
            if isinstance(details, Mapping) and isinstance(details.get("reason"), str):
                reason = f": {details['reason']}"
            raise TaskProviderError(
                f"task model returned an incomplete response{reason}"
            )
        output = response.get("output")
        if isinstance(output, list):
            for item in output:
                if not isinstance(item, Mapping):
                    continue
                if item.get("type") == "function_call":
                    self._finish_item(item)
                elif item.get("type") == "message":
                    text = self._message_text(item)
                    if text:
                        self._final_text = text
        self.finished = True

    @staticmethod
    def _error_message(payload: Mapping[str, Any]) -> str:
        error = payload.get("error")
        if isinstance(error, Mapping):
            message = error.get("message")
            if isinstance(message, str) and message:
                return message
        message = payload.get("message")
        return message if isinstance(message, str) and message else "Codex stream error"

    # -- result --------------------------------------------------------

    def result(self) -> _StreamResult:
        text = self._final_text
        if text is None:
            text = "".join(self._text_parts)
        calls = [call for call in self._calls.values() if call.name]
        for call in calls:
            if not call.arguments.strip():
                call.arguments = "{}"
        return _StreamResult(text=text, calls=calls)


async def _iter_sse(
    lines: AsyncIterator[str],
) -> AsyncIterator[tuple[str | None, str]]:
    data_lines: list[str] = []
    event_name: str | None = None
    async for line in lines:
        if line == "":
            if data_lines:
                yield event_name, "\n".join(data_lines)
            data_lines = []
            event_name = None
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_name = line[6:].strip() or None
            continue
        if line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
            continue
        # The Responses stream uses `data:` lines; ignore other fields.
    if data_lines:
        yield event_name, "\n".join(data_lines)


class TaskAgent:
    """Codex text agent for one delegated phone action."""

    def __init__(self, auth: CodexAuth, model: str | None = None) -> None:
        self.auth = auth
        # Model resolution is deliberately lazy: the daemon and MCP shim must
        # start even when the Codex config or auth is not usable yet, and the
        # failure must surface on place_call with a configuration error.
        self._requested_model = model
        self._resolved_model: str | None = None
        self._session_id = str(uuid.uuid4())
        self._closed = False

    @property
    def model(self) -> str:
        if self._resolved_model is None:
            self._resolved_model = resolve_task_model(self._requested_model)
        return self._resolved_model

    @property
    def configured_model(self) -> str | None:
        """Model from the constructor, without touching Codex configuration."""
        return self._requested_model or None

    @property
    def base_url(self) -> str:
        return _responses_url()

    async def close(self) -> None:
        """Release per-agent state.

        Requests use a short-lived ``httpx.AsyncClient`` each, so there is no
        persistent connection to close.  Marking the agent closed makes later
        delegations fail explicitly instead of reusing finished call state.
        """
        self._closed = True

    def _ensure_open(self) -> None:
        if self._closed:
            raise TaskAgentError("this task agent has been closed")

    async def run(
        self,
        brief: dict[str, Any],
        transcript: list[dict[str, Any]],
        request: str,
        tools: Mapping[str, ToolHandler],
    ) -> str:
        """Execute one delegated turn with streamed tool rounds."""
        self._ensure_open()
        if not isinstance(request, str) or not request.strip():
            raise ValueError("delegated request must be a non-empty string")
        text = await self._act(
            _user_content(
                brief,
                transcript,
                request,
                instruction=(
                    "Handle this delegated request now. Use a tool for any "
                    "phone action, then give the final text for the voice agent."
                ),
            ),
            tools,
        )
        if not text:
            raise TaskProviderError("task model returned no final text")
        return text

    async def review(
        self,
        brief: dict[str, Any],
        transcript: list[dict[str, Any]],
        quiet_s: float,
        tools: Mapping[str, ToolHandler],
    ) -> str:
        """Decide what a quiet line needs when the voice agent asked for nothing."""
        self._ensure_open()
        return await self._act(
            _user_content(
                brief,
                transcript,
                line_status=(
                    f"Nobody has spoken for {quiet_s:.0f} seconds. The voice agent "
                    "has not asked for any action."
                ),
                instruction=(
                    "Decide what the line needs now. Call finish_call when the "
                    "conversation is over, send_dtmf when an automated menu is "
                    "waiting for keypad input, otherwise reply WAIT."
                ),
            ),
            tools,
        )

    async def _act(self, user_text: str, tools: Mapping[str, ToolHandler]) -> str:
        """Run tool rounds for one request and return the model's final text."""
        model = self.model
        tool_map = dict(tools)
        schemas = _tool_schemas(tool_map)
        input_items: list[dict[str, Any]] = [
            {"role": "user", "content": [{"type": "input_text", "text": user_text}]}
        ]

        for _ in range(MAX_ROUNDS):
            body: dict[str, Any] = {
                "model": model,
                "instructions": TASK_INSTRUCTIONS,
                "input": input_items,
                "stream": True,
                "store": False,
                "prompt_cache_key": self._session_id,
            }
            if schemas:
                body["tools"] = schemas
                body["tool_choice"] = "auto"
            result = await self._stream_turn(body)
            if not result.calls:
                return result.text.strip()

            for call in result.calls:
                input_items.append(
                    {
                        "type": "function_call",
                        "call_id": call.call_id,
                        "name": call.name,
                        "arguments": call.arguments,
                    }
                )
                output = await self._execute_tool(call, tool_map)
                input_items.append(
                    {
                        "type": "function_call_output",
                        "call_id": call.call_id,
                        "output": output,
                    }
                )
        raise TaskTurnLimitError(
            f"task model used more than {MAX_ROUNDS} tool rounds"
        )

    async def summarize(
        self, brief: dict[str, Any], transcript: list[dict[str, Any]]
    ) -> dict[str, Any]:
        """Ask the model for a validated structured call summary."""
        self._ensure_open()
        model = self.model
        outcome_schema = {
            "type": "object",
            "properties": {
                "task_outcome": {"type": "string", "enum": list(TASK_OUTCOMES)},
                "summary": {"type": "string", "maxLength": 4000},
                "fields": {
                    "type": "object",
                    "additionalProperties": {},
                },
            },
            "required": ["task_outcome", "summary", "fields"],
            "additionalProperties": False,
        }
        body: dict[str, Any] = {
            "model": model,
            "instructions": SUMMARY_INSTRUCTIONS,
            "input": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "input_text",
                            "text": _user_content(
                                brief,
                                transcript,
                                instruction=(
                                    "Finalize this call. Record only the requested result "
                                    "fields and only facts present in the brief or transcript."
                                ),
                            ),
                        }
                    ],
                }
            ],
            "stream": True,
            "store": False,
            "prompt_cache_key": self._session_id,
            "tools": [
                {
                    "type": "function",
                    "name": "submit_summary",
                    "description": "Submit the validated final call result.",
                    "parameters": outcome_schema,
                }
            ],
            "tool_choice": {"type": "function", "name": "submit_summary"},
        }
        result = await self._stream_turn(body)
        call = next(
            (item for item in result.calls if item.name == "submit_summary"), None
        )
        if call is None:
            raise TaskResultError(
                "task model did not call submit_summary; no structured result"
            )
        try:
            arguments = json.loads(call.arguments or "{}")
        except ValueError as exc:
            raise TaskResultError(
                "task model returned invalid summary arguments"
            ) from exc
        if not isinstance(arguments, dict):
            raise TaskResultError("task model summary arguments were not an object")

        outcome = arguments.get("task_outcome")
        if outcome not in TASK_OUTCOMES:
            raise TaskResultError(
                f"task model summary outcome is invalid: {outcome!r}"
            )
        summary = arguments.get("summary")
        if not isinstance(summary, str):
            raise TaskResultError("task model summary text is missing")
        fields = arguments.get("fields")
        if not isinstance(fields, dict):
            raise TaskResultError("task model summary fields are not an object")

        requested = brief.get("result_fields")
        allowed = (
            {str(value) for value in requested if isinstance(value, str)}
            if isinstance(requested, list)
            else None
        )
        validated: dict[str, Any] = {}
        for key, value in fields.items():
            if not isinstance(key, str):
                raise TaskResultError("task model summary field name is not a string")
            if allowed is not None and key not in allowed:
                continue
            if value is not None and not isinstance(
                value, (str, int, float, bool)
            ):
                raise TaskResultError(
                    f"task model summary field {key!r} is not a JSON scalar"
                )
            validated[key] = value
        return {
            "task_outcome": outcome,
            "summary": summary.strip(),
            "fields": validated,
        }

    # -- model transport ----------------------------------------------

    async def _stream_turn(
        self, body: dict[str, Any], *, retry_auth: bool = True
    ) -> _StreamResult:
        credentials = await self.auth.credentials()
        try:
            return await self._post_stream(body, credentials)
        except _Unauthorized as exc:
            if not retry_auth:
                raise
            logger.info("Codex Responses returned 401; refreshing OAuth token")
            try:
                await self.auth.force_refresh(exc.rejected_access_token)
            except CodexAuthError:
                raise
            return await self._stream_turn(body, retry_auth=False)

    async def _attestation_header(self) -> str | None:
        # A fresh DeviceCheck token per request, like the Codex client: tokens
        # are short-lived attestations, not reusable session identifiers.
        return await codex_attestation_async()

    async def _post_stream(
        self, body: dict[str, Any], credentials: CodexCredentials
    ) -> _StreamResult:
        headers = codex_request_headers(
            credentials,
            session_id=self._session_id,
            attestation=await self._attestation_header(),
            routing_model=self.model,
            client_version=os.environ.get(
                "PHONXIETY_CODEX_CLIENT_VERSION", ""
            ).strip()
            or CODEX_CLIENT_VERSION,
        )
        headers.update(
            {
                "Accept": "text/event-stream",
                "Content-Type": "application/json",
                "x-client-request-id": self._session_id,
            }
        )
        timeout = httpx.Timeout(connect=15.0, read=180.0, write=30.0, pool=30.0)
        accumulator = _ResponseAccumulator()
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream(
                    "POST",
                    self.base_url,
                    headers=headers,
                    content=json.dumps(
                        body, ensure_ascii=False, separators=(",", ":")
                    ).encode("utf-8"),
                ) as response:
                    if response.status_code >= 400:
                        raw = await response.aread()
                        text = raw.decode("utf-8", "replace")
                        self._raise_http_error(response.status_code, text, credentials)
                    async for event_name, data in _iter_sse(response.aiter_lines()):
                        if data == "[DONE]":
                            break
                        try:
                            payload = json.loads(data)
                        except ValueError:
                            continue
                        if not isinstance(payload, dict):
                            continue
                        event_type = payload.get("type") or event_name
                        if not isinstance(event_type, str):
                            continue
                        accumulator.feed(event_type, payload)
                        if accumulator.finished:
                            break
        except httpx.HTTPError as exc:
            raise TaskProviderError(
                f"Codex Responses request failed: {type(exc).__name__}"
            ) from exc
        if not accumulator.finished:
            raise TaskProviderError(
                "Codex Responses stream ended before response.completed"
            )
        return accumulator.result()

    @staticmethod
    def _raise_http_error(
        status: int, body_text: str, credentials: CodexCredentials
    ) -> None:
        message = "request failed"
        code: str | None = None
        try:
            parsed = json.loads(body_text)
        except ValueError:
            parsed = None
        if isinstance(parsed, dict):
            error = parsed.get("error")
            if isinstance(error, dict):
                candidate = error.get("message")
                if isinstance(candidate, str) and candidate:
                    message = candidate
                raw_code = error.get("code") or error.get("type")
                if isinstance(raw_code, str) and raw_code:
                    code = raw_code
            elif isinstance(error, str) and error:
                message = error
            if message == "request failed":
                detail = parsed.get("detail") or parsed.get("message")
                if isinstance(detail, str) and detail:
                    message = detail
        if status == 401:
            raise _Unauthorized(status, message, credentials.access_token, code)
        if status == 429 or (code and "rate_limit" in code):
            raise CodexRateLimitError(status, message, code)
        if code and ("usage_limit" in code or "usage_not_included" in code):
            raise CodexRateLimitError(status, message, code)
        raise CodexApiError(status, message, code)

    async def _execute_tool(
        self, call: _ToolCall, tools: Mapping[str, ToolHandler]
    ) -> str:
        handler = tools.get(call.name)
        if handler is None:
            return _bounded(
                _json_text({"ok": False, "error": f"unknown tool {call.name}"}),
                MAX_TOOL_OUTPUT_CHARS,
            )
        try:
            arguments = json.loads(call.arguments or "{}")
        except ValueError:
            return _json_text({"ok": False, "error": "invalid JSON arguments"})
        if not isinstance(arguments, dict):
            return _json_text(
                {"ok": False, "error": "tool arguments must be a JSON object"}
            )
        try:
            result = await handler(arguments)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - report tool failure to the model
            logger.warning("task tool %s failed: %s", call.name, type(exc).__name__)
            return _bounded(
                _json_text({"ok": False, "error": f"{type(exc).__name__}: {exc}"}),
                MAX_TOOL_OUTPUT_CHARS,
            )
        if not isinstance(result, dict):
            return _bounded(
                _json_text(
                    {"ok": False, "error": "tool handler returned a non-object"}
                ),
                MAX_TOOL_OUTPUT_CHARS,
            )
        return _bounded(_json_text(result), MAX_TOOL_OUTPUT_CHARS)


__all__ = [
    "CodexApiError",
    "CodexRateLimitError",
    "TaskAgent",
    "TaskAgentError",
    "TaskModelConfigError",
    "TaskProviderError",
    "TaskResultError",
    "TaskToolError",
    "TaskTurnLimitError",
    "default_config_path",
    "resolve_task_model",
]
