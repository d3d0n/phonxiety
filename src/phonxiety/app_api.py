"""The phone app's API: request/response envelopes over the device control channel.

The phone sends ``{"type": "api", "id", "method", "path", "query", "body", "app_token"}``
and the hub answers ``{"type": "api_result", "id", "status", "result" | "error"}``. A
second credential, ``app_token``, rides inside every request: the link is already
authenticated with ``device_token``, but the app's credential is narrower and can be
rotated without re-pairing.

Bulk data (recordings, diagnostics bundles) travels on its own phone-opened stream when
the link can carry one (iroh), described by :meth:`AppApi.serve_attachment`; on a single
stream (WebSocket) the same bytes are paged back as base64 instead.

Everything here is a reader or a thin wrapper over :class:`CallController`; no call work
is scoped to a request.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import hashlib
import json
import logging
import os
import re
import secrets
import shutil
import subprocess
import time
import uuid
import zipfile
from collections import OrderedDict
from collections.abc import Awaitable, Callable
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from pydantic import ValidationError

from . import __version__
from .audio.opus import CODECS, MAX_OPUS_BITRATE, MIN_OPUS_BITRATE
from .auth import default_auth_path
from .device import PROTOCOL_VERSION, PROTOCOL_VERSIONS, DeviceError
from .models import CallLimits, CallRequest, TERMINAL_STATES
from .net.framing import frame
from .settings import (
    RESTART_SETTINGS,
    SETTINGS_SCHEMA_VERSION,
    VOICE_BACKENDS,
    Settings,
    validate_runtime,
    write_runtime_settings,
)

log = logging.getLogger(__name__)

APP_API_VERSION = 1
MAX_RESULT_BYTES = 256 * 1024
MAX_AUDIO_PAGE = 192 * 1024
MAX_TRANSCRIPT_PAGE = 200
ATTACHMENT_CHUNK = 64 * 1024
CALLS_PAGE = 20
CALLS_PAGE_MAX = 100
DEFAULT_HOURLY_CALL_CAP = 12
FEED_EVENTS_MAX = 2000
FEEDS_MAX = 32
BUNDLES_KEEP = 8
BUNDLES_TTL_S = 2 * 3600
LINK_TESTS_KEEP = 50
MARK_KINDS = ("agreed", "refused", "follow-up", "unclear", "callback")
FIXES = ("relay_config", "state_dirs")
# Paths that exist for some method: anything else on them is 405, not 404.
KNOWN_PATHS = frozenset({
    "/app/v1/info",
    "/app/v1/status",
    "/app/v1/link-test",
    "/app/v1/probe",
    "/app/v1/calls",
    "/app/v1/calls/validate",
    "/app/v1/test-call",
    "/app/v1/loopback",
    "/app/v1/tone",
    "/app/v1/settings",
    "/app/v1/doctor",
    "/app/v1/doctor/fix",
    "/app/v1/diagnostics",
    "/app/v1/diagnostics/bundle",
    "/app/v1/update/manifest",
    "/app/v1/compat",
    "/app/v1/pair/rotate",
})
CALL_ID_PATH = re.compile(r"^/app/v1/calls/(?P<call_id>[A-Za-z0-9._-]{1,128})(?:/(?P<action>[a-z_]+))?$")
BUNDLE_ID_PATH = re.compile(r"^/app/v1/diagnostics/bundle/(?P<bundle_id>[A-Za-z0-9._-]{1,128})$")
BUNDLE_ID = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
# Log lines are redacted before they leave the daemon: values of secret-looking keys,
# bearer headers and long opaque tokens.
_SECRET_LINE = re.compile(
    r"(?i)\b([a-z0-9_-]*(?:token|secret|password|api[_-]?key|credential|authorization)[a-z0-9_-]*)"
    r"(\s*[=:]\s*)(\"?)([^\s\",}]{4,})"
)
_BEARER = re.compile(r"(?i)\bbearer\s+[^\s\",]+")
_OPAQUE = re.compile(r"\b[A-Za-z0-9_-]{40,}\b")


def _iso(timestamp: float | None) -> str | None:
    if timestamp is None:
        return None
    return datetime.fromtimestamp(timestamp, timezone.utc).isoformat(timespec="seconds")


def redact(text: str) -> str:
    """Remove credentials from log text; conservative, so a token cannot slip through."""
    lines = []
    for line in text.splitlines():
        line = _BEARER.sub("Bearer <redacted>", line)
        line = _SECRET_LINE.sub(lambda match: f"{match.group(1)}{match.group(2)}<redacted>", line)
        line = _OPAQUE.sub("<redacted>", line)
        lines.append(line)
    return "\n".join(lines)


def _relay_host(url: str) -> str:
    """A relay URL without its credentials or path, safe to show."""
    parts = urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}" if parts.netloc else url[:80]


class ApiError(Exception):
    """A request the daemon refuses; carries the HTTP-shaped status and code."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message[:600]


class _Feed:
    """Revisioned events for one call, shared by every phone polling its id."""

    def __init__(self, call_id: str) -> None:
        self.call_id = call_id
        self.events: list[dict[str, Any]] = []
        self.revision = 0
        self.dropped = False
        self.state: str | None = None
        self.result_status: str | None = None
        self.call_outcome: str | None = None
        self.sequence = 0
        self.partials: dict[str, str] = {}
        self.marks = 0
        self.muted: bool | None = None
        self.activity: str | None = None
        self.result_emitted = False
        self.touched = time.monotonic()

    @property
    def terminal(self) -> bool:
        return (
            self.state in {state.value for state in TERMINAL_STATES}
            and self.result_status in (None, "ready")
        )

    def add(self, event: dict[str, Any]) -> None:
        self.revision += 1
        event["revision"] = self.revision
        self.events.append(event)
        if len(self.events) > FEED_EVENTS_MAX:
            self.events = self.events[-FEED_EVENTS_MAX:]
            self.dropped = True


class AppApi:
    """Routes `/app/v1` requests and serves bulk attachments.

    `link_info` reports the transport facts only the daemon's listeners know (endpoint
    id, certificate, whether iroh is bound); tests pass a plain dict-returning callable.
    """

    def __init__(
        self,
        settings: Settings,
        controller: Any,
        *,
        link_info: Callable[[], dict[str, Any]] | None = None,
        hourly_call_cap: int | None = None,
    ) -> None:
        self.settings = settings
        self.controller = controller
        self.state_dir = settings.state_dir
        self.archive_dir = settings.state_dir / "calls"
        self.link_tests_dir = settings.state_dir / "link-tests"
        self.updates_dir = settings.state_dir / "updates"
        self.bundles_dir = settings.state_dir / "diagnostics"
        self.log_path = settings.state_dir / "daemon.log"
        self.tokens_path = settings.state_dir / "tokens.json"
        self.app_token = settings.app_token
        self._link_info = link_info or (lambda: {})
        self._started = time.monotonic()
        self._feeds: OrderedDict[str, _Feed] = OrderedDict()
        self._wake = asyncio.Event()
        self._archive_lock = asyncio.Lock()
        self._tasks: set[asyncio.Task[Any]] = set()
        cap = hourly_call_cap if hourly_call_cap is not None else int(
            os.environ.get("PHONXIETY_APP_CALL_CAP", DEFAULT_HOURLY_CALL_CAP)
        )
        self.hourly_call_cap = max(1, cap)
        # Values the phone can see and change; a PUT applies on the next start.
        self.values: dict[str, Any] = {
            "audio_codec": settings.audio_codec,
            "opus_bitrate": settings.opus_bitrate,
            "voice_backend": settings.voice_backend,
            "codex_voice": settings.codex_voice,
            "iroh_relays": list(settings.iroh_relays),
        }

    # -- envelope -------------------------------------------------------

    async def handle(self, message: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        """Answer one api message; never raises, so the hub always sends a result."""
        try:
            return await self._dispatch(message)
        except ApiError as exc:
            return exc.status, {"code": exc.code, "message": exc.message}
        except DeviceError as exc:
            code = str(exc).split(":", 1)[0].strip() or "device_error"
            return 409, {"code": code, "message": str(exc)[:300]}
        except ValidationError as exc:
            return 400, {"code": "invalid_argument", "message": _validation_message(exc)}
        except LookupError as exc:
            code = str(exc).split(":", 1)[0].strip() or "not_found"
            return 404, {"code": code, "message": "no such call"}
        except ValueError as exc:
            code = str(exc).split(":", 1)[0].strip() or "invalid_argument"
            return 400, {"code": code, "message": str(exc)[:300]}
        except RuntimeError as exc:
            code = str(exc).split(":", 1)[0].strip() or "conflict"
            return 409, {"code": code, "message": str(exc)[:300]}
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("App API request failed")
            return 500, {"code": "internal_error", "message": "the daemon could not answer"}

    async def _dispatch(self, message: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        method = message.get("method", "GET")
        path = message.get("path")
        query = message.get("query") or {}
        body = message.get("body") or {}
        if not isinstance(method, str) or not isinstance(path, str):
            raise ApiError(400, "bad_request", "method and path are required")
        method = method.upper()
        if method not in ("GET", "POST", "PUT", "DELETE"):
            raise ApiError(405, "method_not_allowed", f"{method} is not used by the app API")
        if not isinstance(query, dict) or not isinstance(body, dict):
            raise ApiError(400, "bad_request", "query and body must be objects")
        token = message.get("app_token")
        if not isinstance(token, str) or not self.authorized(token):
            raise ApiError(401, "unauthorized", "the app token is missing or no longer valid")
        return await self._route(method, path, query, body)

    def authorized(self, token: str) -> bool:
        expected = self.app_token
        return bool(expected) and secrets.compare_digest(token.encode("utf-8"), expected.encode("utf-8"))

    async def _route(
        self, method: str, path: str, query: dict[str, Any], body: dict[str, Any]
    ) -> tuple[int, dict[str, Any]]:
        if path == "/app/v1/info" and method == "GET":
            return await self._info(query, body)
        if path == "/app/v1/status" and method == "GET":
            return await self._status(query, body)
        if path == "/app/v1/link-test":
            if method == "POST":
                return await self._link_test(query, body)
            if method == "GET":
                return await self._link_tests(query, body)
        if path == "/app/v1/probe" and method == "POST":
            return await self._probe(query, body)
        if path == "/app/v1/calls":
            if method == "GET":
                return await self._calls(query, body)
            if method == "POST":
                return await self._place(query, body)
        if path == "/app/v1/calls/validate":
            if method != "POST":
                raise ApiError(405, "method_not_allowed", f"{method} is not used on {path}")
            return await self._validate(query, body)
        if path == "/app/v1/test-call" and method == "POST":
            return await self._test_call(query, body)
        if path == "/app/v1/loopback" and method == "POST":
            return await self._loopback(query, body)
        if path == "/app/v1/tone" and method == "POST":
            return await self._tone(query, body)
        if path == "/app/v1/settings":
            if method == "GET":
                return await self._get_settings(query, body)
            if method == "PUT":
                return await self._put_settings(query, body)
        if path == "/app/v1/doctor" and method == "GET":
            return await self._doctor(query, body)
        if path == "/app/v1/doctor/fix" and method == "POST":
            return await self._doctor_fix(query, body)
        if path == "/app/v1/diagnostics" and method == "GET":
            return await self._diagnostics(query, body)
        if path == "/app/v1/diagnostics/bundle" and method == "POST":
            return await self._create_bundle(query, body)
        bundle = BUNDLE_ID_PATH.match(path)
        if bundle is not None and method == "GET":
            return await self._bundle_page(query, body, bundle["bundle_id"])
        if path == "/app/v1/update/manifest" and method == "GET":
            return await self._update_manifest(query, body)
        if path == "/app/v1/compat" and method == "GET":
            return await self._compat(query, body)
        if path == "/app/v1/pair/rotate" and method == "POST":
            return await self._rotate(query, body)
        call = CALL_ID_PATH.match(path)
        if call is not None:
            return await self._call_route(method, call["call_id"], call["action"], query, body)
        if path in KNOWN_PATHS or BUNDLE_ID_PATH.match(path) is not None:
            raise ApiError(405, "method_not_allowed", f"{method} is not used on {path}")
        raise ApiError(404, "not_found", f"no endpoint {method} {path}")

    async def _call_route(
        self,
        method: str,
        call_id: str,
        action: str | None,
        query: dict[str, Any],
        body: dict[str, Any],
    ) -> tuple[int, dict[str, Any]]:
        if action is None:
            if method == "GET":
                return await self._call_detail(query, body, call_id)
            if method == "DELETE":
                return await self._delete_call(query, body, call_id)
            raise ApiError(405, "method_not_allowed", f"{method} is not used on a call")
        if action == "transcript" and method == "GET":
            return await self._transcript(query, body, call_id)
        if action == "events" and method == "GET":
            return await self._events(query, body, call_id)
        if action == "audio" and method == "GET":
            return await self._audio(query, body, call_id)
        if action == "instruction" and method == "POST":
            return await self._instruction(query, body, call_id)
        if action in ("pause", "resume") and method == "POST":
            return await self._pause(query, body, call_id, action == "pause")
        if action == "summarize" and method == "POST":
            return await self._summarize(query, body, call_id)
        if action == "dtmf" and method == "POST":
            return await self._dtmf(query, body, call_id)
        if action == "hangup" and method == "POST":
            return await self._hangup(query, body, call_id)
        if action == "marks":
            if method == "POST":
                return await self._add_mark(query, body, call_id)
            if method == "GET":
                return await self._marks(query, body, call_id)
        raise ApiError(404, "not_found", f"no endpoint {method} {action} on a call")

    # -- query helpers --------------------------------------------------

    @staticmethod
    def _int(query: dict[str, Any], key: str, default: int, low: int, high: int) -> int:
        value = query.get(key, default)
        try:
            number = int(value)
        except (TypeError, ValueError):
            raise ApiError(400, "invalid_argument", f"{key} must be an integer") from None
        if not low <= number <= high:
            raise ApiError(400, "invalid_argument", f"{key} must be between {low} and {high}")
        return number

    @staticmethod
    def _float(query: dict[str, Any], key: str, default: float, low: float, high: float) -> float:
        value = query.get(key, default)
        try:
            number = float(value)
        except (TypeError, ValueError):
            raise ApiError(400, "invalid_argument", f"{key} must be a number") from None
        if not low <= number <= high:
            raise ApiError(400, "invalid_argument", f"{key} must be between {low} and {high}")
        return number

    @staticmethod
    def _string(body: dict[str, Any], key: str, *, limit: int, required: bool = True) -> str | None:
        value = body.get(key)
        if value is None:
            if required:
                raise ApiError(400, "invalid_argument", f"{key} is required")
            return None
        if not isinstance(value, str) or not value.strip():
            raise ApiError(400, "invalid_argument", f"{key} must be a non-empty string")
        text = value.strip()
        if len(text) > limit:
            raise ApiError(400, "invalid_argument", f"{key} must be at most {limit} characters")
        return text

    @staticmethod
    def _safe_id(value: str | None, what: str) -> str:
        if not isinstance(value, str) or not value or "/" in value or "\\" in value or value in (".", ".."):
            raise ApiError(400, "invalid_argument", f"{what} is invalid")
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", value):
            raise ApiError(400, "invalid_argument", f"{what} is invalid")
        return value

    def _page(self, data: bytes, query: dict[str, Any]) -> dict[str, Any]:
        total = len(data)
        offset = self._int(query, "offset", 0, 0, max(0, total))
        limit = self._int(query, "limit", MAX_AUDIO_PAGE, 1, MAX_AUDIO_PAGE)
        chunk = data[offset : offset + limit]
        return {
            "total": total,
            "offset": offset,
            "limit": limit,
            "data": base64.b64encode(chunk).decode("ascii"),
        }

    # -- info and status ------------------------------------------------

    def _control_caps(self) -> set[str]:
        info = getattr(self.controller.device, "info", {}) or {}
        caps = info.get("control_caps")
        if isinstance(caps, dict):
            return {str(key) for key, value in caps.items() if value}
        if isinstance(caps, (list, tuple, set)):
            return {str(item) for item in caps}
        return set()

    def _supports(self, command: str) -> bool:
        info = getattr(self.controller.device, "info", {}) or {}
        caps = info.get("capabilities")
        if isinstance(caps, dict) and caps.get(command):
            return True
        return command in self._control_caps()

    async def _info(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        device = getattr(self.controller, "device", None)
        info = getattr(device, "info", {}) or {}
        stream = bool(getattr(device, "supports_attachments", False))
        return 200, {
            "daemon_version": __version__,
            "protocol": PROTOCOL_VERSION,
            "protocol_versions": list(PROTOCOL_VERSIONS),
            "app_api": APP_API_VERSION,
            "settings_schema": SETTINGS_SCHEMA_VERSION,
            "voice_backend": self.values["voice_backend"],
            "voice_controls": {
                "instruction": True,
                "pause": True,
                "resume": True,
                "summarize": True,
                "dtmf": True,
                "marks": True,
                "hangup": True,
                "test_call": True,
                "loopback": self._supports("loopback_start"),
                "tone": self._supports("tone_start"),
            },
            "attachments": "stream" if stream else "paged",
            "capabilities": info.get("capabilities") or {},
            "control_caps": sorted(self._control_caps()),
            "phone": {
                "device": info.get("device"),
                "app_api": info.get("app_api"),
                "preferred_codec": info.get("preferred_codec"),
                "max_bitrate": info.get("max_bitrate"),
            },
            "limits": {
                "max_result_bytes": MAX_RESULT_BYTES,
                "audio_page_bytes": MAX_AUDIO_PAGE,
                "transcript_page": MAX_TRANSCRIPT_PAGE,
                "events_wait_s": 25,
                "max_duration_s": 3600,
                "ring_timeout_s": 180,
                "test_call_max_duration_s": 60,
                "calls_per_hour": self.hourly_call_cap,
            },
            "mark_kinds": list(MARK_KINDS),
        }

    async def _status(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        health = self.controller.health()
        link = dict(self._link_info() or {})
        return 200, {
            "daemon": health.get("daemon"),
            "uptime_s": round(time.monotonic() - self._started, 1),
            "phone": health.get("device"),
            "active_calls": health.get("active_calls", []),
            "link": {
                "endpoint_id": link.get("endpoint_id"),
                "address_port": link.get("address_port"),
                "certificate": link.get("certificate"),
                "iroh_available": bool(link.get("iroh_available")),
            },
        }

    # -- link test and probes -------------------------------------------

    @staticmethod
    def _link_test_record(result: dict[str, Any]) -> dict[str, Any]:
        sent = int(result.get("frames_sent") or 0)
        echoed = int(result.get("frames_echoed") or 0)
        spread = result.get("frame_ms") or {}
        seconds = float(result.get("seconds") or 0.0)
        frame_bytes = int(result.get("frame_bytes") or 0)
        if result.get("link_lost"):
            verdict = "failed"
        elif not echoed:
            verdict = "unknown"
        elif result.get("healthy"):
            verdict = "good"
        else:
            verdict = "poor"
        return {
            "at": _iso(time.time()),
            "codec": result.get("codec"),
            "transport": result.get("transport"),
            "peer": result.get("peer"),
            "route": result.get("route"),
            "route_after": result.get("route_after"),
            "seconds": seconds,
            "frame_bytes": frame_bytes,
            "frames": {"sent": sent, "echoed": echoed},
            "p50": spread.get("median"),
            "p95": spread.get("p95"),
            "max": spread.get("max"),
            "loss": round(1 - echoed / sent, 4) if sent else None,
            "reorder": None,  # the echo carries no sequence comparison the daemon can trust
            "throughput_bps": round(frame_bytes * echoed / seconds) if seconds > 0 else None,
            "ping_ms": result.get("ping_ms"),
            "send_stall_ms": result.get("send_stall_ms"),
            "healthy": bool(result.get("healthy")),
            "verdict": verdict,
        }

    async def _link_test(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        device = self.controller.device
        seconds = body.get("seconds", 5)
        try:
            seconds = min(30.0, max(1.0, float(seconds)))
        except (TypeError, ValueError):
            raise ApiError(400, "invalid_argument", "seconds must be a number between 1 and 30") from None
        codec = body.get("codec")
        if codec is not None and codec not in CODECS:
            raise ApiError(400, "invalid_argument", f"codec must be one of: {', '.join(CODECS)}")
        transport = body.get("transport", "stream")
        if transport not in ("stream", "datagram"):
            raise ApiError(400, "invalid_argument", "transport must be stream or datagram")
        await device.wait_connected(15.0)
        result = await device.link_test(seconds, codec=codec, datagrams=transport == "datagram")
        record = {"id": uuid.uuid4().hex[:16], **self._link_test_record(result)}
        self._store_link_test(record)
        return 200, record

    def _store_link_test(self, record: dict[str, Any]) -> None:
        self.link_tests_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        path = self.link_tests_dir / f"{int(time.time() * 1000)}-{record['id']}.json"
        temporary = path.with_name(f".{path.name}.part")
        try:
            temporary.write_text(json.dumps(record, ensure_ascii=False, indent=2))
            os.chmod(temporary, 0o600)
            os.replace(temporary, path)
        except OSError as exc:
            log.warning("Link test result could not be saved: %s", exc)
            temporary.unlink(missing_ok=True)
        records = sorted(self.link_tests_dir.glob("*.json"))
        for stale in records[:-LINK_TESTS_KEEP]:
            stale.unlink(missing_ok=True)

    async def _link_tests(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        limit = self._int(query, "limit", 10, 1, LINK_TESTS_KEEP)
        records: list[dict[str, Any]] = []
        for path in sorted(self.link_tests_dir.glob("*.json"), reverse=True)[:limit]:
            try:
                records.append(json.loads(path.read_text()))
            except (OSError, ValueError):
                continue
        return 200, {"tests": records}

    async def _ping(self) -> float | None:
        device = self.controller.device
        if not getattr(device, "connected", False):
            return None
        began = time.monotonic()
        try:
            await device.command("ping", timeout=3)
        except DeviceError:
            return None
        return round((time.monotonic() - began) * 1000, 1)

    async def _probe(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        candidates = body.get("candidates") or ["iroh", "lan", "tailscale"]
        if not isinstance(candidates, list) or not all(isinstance(item, str) for item in candidates):
            raise ApiError(400, "invalid_argument", "candidates must be a list of names")
        device = self.controller.device
        link = self._link_info() or {}
        rtt = await self._ping()
        connected = bool(getattr(device, "connected", False))
        answers: list[dict[str, Any]] = []
        for name in candidates[:5]:
            if name == "iroh":
                available = bool(link.get("iroh_available"))
                answers.append({
                    "name": "iroh",
                    "available": available,
                    "reachable": bool(available and connected),
                    "rtt_ms": rtt,
                    "detail": "the phone link arrives at the daemon's iroh endpoint"
                    if available else "the iroh endpoint is not bound on this daemon",
                })
            elif name == "lan":
                answers.append({
                    "name": "lan",
                    "available": True,
                    "reachable": connected,
                    "rtt_ms": rtt,
                    "detail": f"certificate-pinned listener on port {self.settings.device_port}",
                })
            elif name == "tailscale":
                answers.append(self._tailscale_candidate(rtt, connected))
            else:
                answers.append({"name": name, "available": False, "reachable": False, "rtt_ms": None,
                                "reason": "unknown_candidate"})
        return 200, {"candidates": answers, "checked_at": _iso(time.time())}

    @staticmethod
    def _tailscale_candidate(rtt: float | None, connected: bool) -> dict[str, Any]:
        binary = shutil.which("tailscale")
        if binary is None:
            return {"name": "tailscale", "available": False, "reachable": False, "rtt_ms": None,
                    "reason": "not_installed"}
        try:
            result = subprocess.run([binary, "ip", "-4"], capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.TimeoutExpired):
            return {"name": "tailscale", "available": False, "reachable": False, "rtt_ms": None,
                    "reason": "command_failed"}
        address = result.stdout.split()[0] if result.returncode == 0 and result.stdout.strip() else None
        if address is None:
            return {"name": "tailscale", "available": False, "reachable": False, "rtt_ms": None,
                    "reason": "not_connected"}
        return {"name": "tailscale", "available": True, "reachable": connected, "rtt_ms": rtt,
                "address": address, "detail": "pair by this address with `phonxiety pair --tailscale`"}

    # -- calls: place, validate, test -----------------------------------

    def _call_request(self, body: dict[str, Any], *, request_prefix: str = "app",
                      require_confirmation: bool = True) -> tuple[CallRequest, str]:
        confirmation = body.get("confirmation_id")
        if confirmation is None and not require_confirmation:
            confirmation = "validate"
        if not isinstance(confirmation, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", confirmation):
            raise ApiError(
                400, "confirmation_required",
                "confirmation_id must be 1..128 characters of letters, digits, '_', '.', ':' or '-'",
            )
        request_id = body.get("request_id")
        if request_id is None:
            request_id = f"{request_prefix}-{confirmation}"
        if not isinstance(request_id, str):
            raise ApiError(400, "invalid_argument", "request_id must be a string")
        request = CallRequest(
            request_id=request_id,
            destination=body.get("destination"),
            goal=body.get("goal"),
            context=body.get("context", ""),
            constraints=body.get("constraints", ""),
            limits=CallLimits(
                max_duration_s=body.get("max_duration_s", 900),
                ring_timeout_s=body.get("ring_timeout_s", 60),
            ),
            result_fields=body.get("result_fields") or [],
            phone_account=body.get("phone_account"),
        )
        return request, confirmation

    async def _place(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        request, confirmation = self._call_request(body)
        if self.controller.store.recent_count(time.time() - 3600) >= self.hourly_call_cap:
            raise ApiError(429, "hourly_call_cap", f"at most {self.hourly_call_cap} calls per hour")
        snapshot = await self.controller.place_call(request)
        await self._notify_local()
        return 200, {**snapshot, "confirmation_id": confirmation}

    async def _validate(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        try:
            request, _ = self._call_request(body, request_prefix="validate", require_confirmation=False)
        except ApiError as exc:
            return 200, {"valid": False, "errors": [{"location": exc.code, "message": exc.message}], "warnings": []}
        except ValidationError as exc:
            return 200, {"valid": False, "errors": _validation_errors(exc), "warnings": []}
        warnings: list[str] = []
        if len(request.goal.strip()) < 10:
            warnings.append("the goal is very short; the agent may not know what to accomplish")
        if not request.result_fields:
            warnings.append("no result fields: the summary will be free text")
        if len(request.destination) <= 6 and not request.destination.startswith("+"):
            warnings.append("this is a service short number; menus are common")
        if request.limits.max_duration_s >= 1800:
            warnings.append("long calls cost the far end's patience as well as the model's time")
        return 200, {
            "valid": True,
            "errors": [],
            "warnings": warnings,
            "request": request.model_dump(mode="json"),
        }

    TEST_GOAL = "Confirm that the line connects and that audio flows in both directions."
    TEST_CONTEXT = (
        "This is a short audio test call placed from the Phonxiety test lab. Do not transact any "
        "business and do not collect personal information. Say who you are, ask whether you are "
        "heard clearly, wait for the answer and thank the person."
    )
    TEST_CONSTRAINTS = "Keep the call under a minute and end it yourself once the question is answered."

    async def _test_call(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        destination = self._string(body, "destination", limit=32)
        max_duration = body.get("max_duration_s", 45)
        try:
            max_duration = int(max_duration)
        except (TypeError, ValueError):
            raise ApiError(400, "invalid_argument", "max_duration_s must be an integer") from None
        if not 10 <= max_duration <= 60:
            raise ApiError(400, "invalid_argument", "max_duration_s must be between 10 and 60")
        # Never retried: a fresh request id each time, so a repeated tap dials again only
        # when the user taps again.
        request = CallRequest(
            request_id=f"test-{uuid.uuid4().hex}",
            destination=destination,
            goal=self.TEST_GOAL,
            context=self.TEST_CONTEXT,
            constraints=self.TEST_CONSTRAINTS,
            limits=CallLimits(max_duration_s=max_duration, ring_timeout_s=30),
            result_fields=["heard"],
            phone_account=body.get("phone_account"),
        )
        snapshot = await self.controller.place_call(request)
        await self._notify_local()
        return 200, {**snapshot, "profile": "test"}

    # -- calls: history, detail, transcript, audio, delete ---------------

    def _archive_path(self, call_id: str) -> Path:
        return self.archive_dir / f"{self._safe_id(call_id, 'call_id')}.json"

    def _load_archive(self, call_id: str) -> dict[str, Any] | None:
        try:
            data = json.loads(self._archive_path(call_id).read_text())
        except (FileNotFoundError, ValueError, OSError):
            return None
        return data if isinstance(data, dict) else None

    def _call_exists(self, call_id: str) -> bool:
        try:
            self.controller.store.row(call_id)
        except LookupError:
            return self._load_archive(call_id) is not None
        return True

    @staticmethod
    def _summary_of(record: dict[str, Any], *, live: bool = False) -> dict[str, Any]:
        request = record.get("request") if isinstance(record.get("request"), dict) else {}
        created = record.get("created_at")
        connected = record.get("connected_at")
        ended = record.get("ended_at")
        duration = round(ended - connected, 1) if isinstance(ended, (int, float)) and isinstance(connected, (int, float)) else None
        summary = record.get("summary")
        return {
            "call_id": record.get("call_id"),
            "state": record.get("state"),
            "destination": request.get("destination") or record.get("destination"),
            "goal": (request.get("goal") or "")[:200] or None,
            "call_outcome": record.get("call_outcome"),
            "task_outcome": record.get("task_outcome"),
            "result_status": record.get("result_status"),
            "summary": summary[:600] if isinstance(summary, str) else None,
            "error": record.get("error"),
            "far_end": record.get("far_end"),
            "created_at": created,
            "connected_at": connected,
            "ended_at": ended,
            "duration_s": duration,
            "marks": len(record.get("marks") or []),
            "has_recording": bool((record.get("recording") or {}).get("file")),
            "live": live,
        }

    def _history(self) -> list[dict[str, Any]]:
        summaries: dict[str, dict[str, Any]] = {}
        try:
            paths = sorted(self.archive_dir.glob("*.json"), key=lambda path: path.stat().st_mtime, reverse=True)
        except OSError:
            paths = []
        # Bounded: history is read from the newest records only, so a long-lived state
        # directory never turns one list into a full scan.
        for path in paths[:500]:
            try:
                record = json.loads(path.read_text())
            except (OSError, ValueError):
                continue
            if not isinstance(record, dict) or not isinstance(record.get("call_id"), str):
                continue
            summaries[record["call_id"]] = self._summary_of(record)
        for row in self.controller.store.unfinished():
            call_id = row["call_id"]
            if call_id in summaries:
                continue
            record = dict(row)
            with contextlib.suppress(ValueError):
                record["request"] = json.loads(row["request_json"])
            record["marks"] = (self.controller.live_call(call_id) or {}).get("marks", [])
            summaries[call_id] = self._summary_of(record, live=True)
        return sorted(summaries.values(), key=lambda item: item.get("created_at") or 0, reverse=True)

    async def _calls(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        limit = self._int(query, "limit", CALLS_PAGE, 1, CALLS_PAGE_MAX)
        offset = self._int(query, "offset", 0, 0, 100_000)
        before = self._float(query, "before", 0.0, 0.0, 1e12)
        outcome = query.get("outcome")
        far_end = query.get("farEnd")
        rows = self._history()
        if outcome:
            rows = [row for row in rows if row.get("call_outcome") == outcome]
        if far_end:
            rows = [row for row in rows if row.get("far_end") == far_end]
        if before:
            rows = [row for row in rows if (row.get("created_at") or 0) < before]
        page = rows[offset : offset + limit]
        has_more = offset + limit < len(rows)
        return 200, {
            "calls": page,
            "offset": offset,
            "has_more": has_more,
            "next_before": page[-1]["created_at"] if page and has_more else None,
            "total": len(rows),
        }

    async def _call_detail(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        archive = self._load_archive(call_id)
        live = self.controller.live_call(call_id)
        if archive is not None:
            record = dict(archive)
            record.setdefault("marks", [])
            return 200, record
        try:
            snapshot = self.controller.store.snapshot(call_id)
        except LookupError:
            raise ApiError(404, "call_not_found", f"no call {call_id}") from None
        snapshot["request"] = self.controller.store.request(call_id).model_dump(mode="json")
        snapshot["marks"] = (live or {}).get("marks", [])
        snapshot["far_end"] = (live or {}).get("far_end")
        snapshot["audio_codec"] = (live or {}).get("audio_codec")
        snapshot["live"] = live
        snapshot["counters"] = self.controller.device.counters() if live is not None else None
        return 200, snapshot

    async def _transcript(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        cursor = self._int(query, "cursor", 0, 0, 1_000_000)
        limit = self._int(query, "limit", MAX_TRANSCRIPT_PAGE, 1, MAX_TRANSCRIPT_PAGE)
        archive = self._load_archive(call_id)
        if archive is not None:
            turns = [turn for turn in archive.get("transcript") or [] if isinstance(turn, dict)]
            page = [turn for turn in turns if int(turn.get("sequence") or 0) > cursor][:limit]
            has_more = len([turn for turn in turns if int(turn.get("sequence") or 0) > cursor]) > limit
            return 200, {
                "call_id": call_id,
                "transcript": page,
                "next_cursor": page[-1].get("sequence") if page else cursor,
                "has_more": has_more,
                "current_partials": [],
                "transcript_complete": bool(archive.get("transcript_complete")),
            }
        try:
            snapshot = self.controller.store.snapshot(call_id, cursor, min(limit, 100))
        except LookupError:
            raise ApiError(404, "call_not_found", f"no call {call_id}") from None
        return 200, {
            "call_id": call_id,
            "transcript": snapshot["transcript"],
            "next_cursor": snapshot["next_cursor"],
            "has_more": snapshot["has_more"],
            "current_partials": snapshot["current_partials"],
            "transcript_complete": snapshot["transcript_complete"],
        }

    def _recording_file(self, call_id: str, fmt: str) -> Path:
        archive = self._load_archive(call_id)
        if archive is None:
            raise ApiError(404, "audio_unavailable", "the call has no recording yet")
        recording = archive.get("recording") or {}
        name: str | None = None
        if fmt == "m4a":
            compressed = recording.get("compressed") if isinstance(recording.get("compressed"), dict) else {}
            name = compressed.get("file")
        elif fmt == "wav":
            name = recording.get("file")
        else:
            raise ApiError(400, "invalid_argument", "format must be m4a or wav")
        if not isinstance(name, str) or "/" in name or "\\" in name:
            raise ApiError(404, "audio_unavailable", f"no {fmt} recording for this call")
        path = self.archive_dir / name
        if not path.is_file():
            raise ApiError(404, "audio_unavailable", f"the {fmt} recording is gone")
        return path

    def _audio_bytes(self, call_id: str, fmt: str) -> bytes:
        path = self._recording_file(call_id, fmt)
        try:
            return path.read_bytes()
        except OSError as exc:
            raise ApiError(500, "audio_unreadable", f"the recording cannot be read: {exc}") from None

    async def _audio(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        fmt = str(query.get("format") or "m4a").lower()
        data = self._audio_bytes(call_id, fmt)
        return 200, {"call_id": call_id, "format": fmt, **self._page(data, query)}

    async def _delete_call(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        confirm = body.get("confirm")
        if confirm != call_id:
            raise ApiError(400, "confirmation_required", "send {\"confirm\": \"<call_id>\"} to delete a call")
        if self.controller.live_call(call_id) is not None:
            raise ApiError(409, "call_active", "hang up before deleting a live call")
        removed: list[str] = []
        async with self._archive_lock:
            for name in (f"{call_id}.json", f"{call_id}.wav", f"{call_id}.m4a"):
                path = self.archive_dir / name
                if path.is_file():
                    with contextlib.suppress(OSError):
                        path.unlink()
                        removed.append(name)
        if not removed:
            raise ApiError(404, "call_not_found", f"no archived call {call_id}")
        self._feeds.pop(call_id, None)
        return 200, {"call_id": call_id, "deleted": removed}

    # -- live call control ----------------------------------------------

    async def _instruction(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        text = self._string(body, "text", limit=2000)
        await self.controller.inject_instruction(call_id, text or "")
        await self._notify_local()
        return 200, {"call_id": call_id, "delivered": True}

    async def _pause(
        self, query: dict[str, Any], body: dict[str, Any], call_id: str, paused: bool
    ) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        result = await self.controller.set_output_muted(call_id, paused)
        await self._notify_local()
        return 200, {**result, "paused": paused}

    async def _summarize(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        result = await self.controller.summarize_now(call_id)
        await self._notify_local()
        return 200, result

    async def _dtmf(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        digits = body.get("digits")
        if not isinstance(digits, str) or not 1 <= len(digits) <= 32:
            raise ApiError(400, "invalid_argument", "digits must be 1..32 keypad characters")
        digits = digits.replace(" ", "").upper()
        if any(char not in "0123456789*#ABCD" for char in digits):
            raise ApiError(400, "invalid_argument", "digits must be 0-9, *, #, A-D")
        result = await self.controller.send_dtmf(call_id, digits)
        await self._notify_local()
        return 200, result

    async def _hangup(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        snapshot = await self.controller.hangup_call(call_id, reason="app_requested")
        await self._notify_local()
        return 200, snapshot

    async def _add_mark(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        mark = self._string(body, "mark", limit=32)
        note = self._string(body, "note", limit=500, required=False)
        if mark not in MARK_KINDS:
            raise ApiError(400, "invalid_argument", f"mark must be one of: {', '.join(MARK_KINDS)}")
        marks = await self.controller.add_mark(call_id, mark, note)
        if marks is None:
            marks = await self._archive_mark(call_id, mark, note)
        await self._notify_local()
        return 200, {"call_id": call_id, "marks": marks}

    async def _archive_mark(self, call_id: str, mark: str, note: str | None) -> list[dict[str, Any]]:
        """Append a mark to a finished call's JSON record; the archive is the record."""
        async with self._archive_lock:
            record = self._load_archive(call_id)
            if record is None:
                raise ApiError(404, "call_not_found", f"no call {call_id}")
            marks = record.get("marks") if isinstance(record.get("marks"), list) else []
            entry: dict[str, Any] = {"mark": mark, "at": time.time()}
            if note:
                entry["note"] = note
            marks.append(entry)
            record["marks"] = marks
            path = self._archive_path(call_id)
            temporary = path.with_name(f".{path.name}.part")
            try:
                temporary.write_text(json.dumps(record, ensure_ascii=False, indent=2))
                os.chmod(temporary, 0o600)
                os.replace(temporary, path)
            except OSError as exc:
                temporary.unlink(missing_ok=True)
                raise ApiError(500, "archive_write_failed", f"the mark could not be stored: {exc}") from None
        return marks

    async def _marks(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        live = self.controller.live_call(call_id)
        if live is not None:
            return 200, {"call_id": call_id, "marks": live.get("marks", [])}
        archive = self._load_archive(call_id)
        if archive is None:
            raise ApiError(404, "call_not_found", f"no call {call_id}")
        return 200, {"call_id": call_id, "marks": archive.get("marks") or []}

    # -- events long-poll ----------------------------------------------

    async def _notify_local(self) -> None:
        self._wake.set()
        for feed in self._feeds.values():
            feed.touched = time.monotonic()

    def _feed(self, call_id: str) -> _Feed:
        feed = self._feeds.get(call_id)
        if feed is None:
            if not self._call_exists(call_id):
                raise ApiError(404, "call_not_found", f"no call {call_id}")
            feed = _Feed(call_id)
            self._feeds[call_id] = feed
            self._prune_feeds()
        feed.touched = time.monotonic()
        self._sync(feed)
        return feed

    def _prune_feeds(self) -> None:
        while len(self._feeds) > FEEDS_MAX:
            call_id, _ = self._feeds.popitem(last=False)
            if call_id in self._feeds:
                break

    def _sync(self, feed: _Feed) -> None:
        """Turn whatever changed into events; called on every poll iteration."""
        call_id = feed.call_id
        live = self.controller.live_call(call_id)
        row: Any = None
        try:
            row = self.controller.store.row(call_id)
        except LookupError:
            row = None
        archive = self._load_archive(call_id) if row is None else None
        if row is None and archive is None:
            return
        if row is not None:
            state = row["state"]
            outcome = row["call_outcome"]
            result_status = row["result_status"]
            transcript = self.controller.store.transcript(call_id)
            partials = {item["role"]: item["text"] for item in self.controller.store.partials(call_id)}
            marks = (live or {}).get("marks", [])
        else:
            archive = archive or {}
            state = archive.get("state")
            outcome = archive.get("call_outcome")
            result_status = "ready"
            transcript = [turn for turn in archive.get("transcript") or [] if isinstance(turn, dict)]
            partials = {}
            marks = archive.get("marks") or []
        if feed.state != state:
            feed.state = state
            feed.call_outcome = outcome
            feed.result_status = result_status
            event: dict[str, Any] = {"type": "state", "state": state, "call_outcome": outcome, "at": _iso(time.time())}
            feed.add(event)
            if state in {item.value for item in TERMINAL_STATES}:
                feed.add({"type": "ended", "state": state, "call_outcome": outcome, "result_status": result_status})
        for turn in transcript:
            sequence = int(turn.get("sequence") or 0)
            if sequence <= feed.sequence:
                continue
            feed.sequence = sequence
            feed.add({
                "type": "transcript",
                "sequence": sequence,
                "role": turn.get("role"),
                "text": turn.get("text"),
                "final": True,
                "at": _iso(turn.get("at")),
            })
        for role, text in partials.items():
            previous = feed.partials.get(role)
            if previous == text:
                continue
            feed.partials[role] = text
            feed.add({"type": "partial", "role": role, "text": text, "final": False})
        for role in [item for item in feed.partials if item not in partials]:
            feed.partials.pop(role, None)
        if len(marks) != feed.marks:
            feed.marks = len(marks)
            feed.add({"type": "marks", "marks": marks})
        if live is not None:
            if feed.muted != live.get("muted"):
                feed.muted = live.get("muted")
                feed.add({"type": "muted", "muted": bool(feed.muted)})
            if feed.activity != live.get("agent_activity"):
                feed.activity = live.get("agent_activity")
                feed.add({"type": "agent_activity", "activity": feed.activity})
        if row is not None and result_status == "ready" and not feed.result_emitted:
            feed.result_emitted = True
            feed.result_status = result_status
            feed.add({
                "type": "result",
                "task_outcome": row["task_outcome"],
                "summary": row["summary"],
                "fields": json.loads(row["fields_json"]),
            })

    async def _wait_change(self, deadline: float) -> None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        self._wake.clear()
        local = asyncio.create_task(self._wake.wait())
        remote = asyncio.create_task(self.controller.wait_change(self.controller.revision, min(5.0, remaining)))
        try:
            await asyncio.wait({local, remote}, timeout=remaining, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for task in (local, remote):
                task.cancel()
            await asyncio.gather(local, remote, return_exceptions=True)

    async def _events(self, query: dict[str, Any], body: dict[str, Any], call_id: str) -> tuple[int, dict[str, Any]]:
        call_id = self._safe_id(call_id, "call_id")
        since = self._int(query, "since", 0, 0, 1_000_000_000)
        wait_s = self._float(query, "wait_s", 0.0, 0.0, 25.0)
        feed = self._feed(call_id)
        deadline = time.monotonic() + wait_s
        while True:
            events = [event for event in feed.events if event["revision"] > since]
            if events or feed.terminal or time.monotonic() >= deadline:
                break
            await self._wait_change(deadline)
            self._sync(feed)
        events = [event for event in feed.events if event["revision"] > since]
        oldest = feed.events[0]["revision"] if feed.events else feed.revision + 1
        return 200, {
            "call_id": call_id,
            "revision": feed.revision,
            "state": feed.state,
            "call_outcome": feed.call_outcome,
            "result_status": feed.result_status,
            "events": events,
            "truncated": bool(feed.dropped and since and since < oldest - 1),
        }

    # -- settings -------------------------------------------------------

    async def _get_settings(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        return 200, {
            "schema": SETTINGS_SCHEMA_VERSION,
            "values": dict(self.values),
            "restart_required_fields": list(RESTART_SETTINGS),
            "choices": {
                "audio_codec": list(CODECS),
                "voice_backend": list(VOICE_BACKENDS),
                "opus_bitrate": {"min": MIN_OPUS_BITRATE, "max": MAX_OPUS_BITRATE},
            },
        }

    async def _put_settings(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        values = body.get("values") if isinstance(body.get("values"), dict) else body
        if not isinstance(values, dict) or not values:
            raise ApiError(400, "invalid_argument", "send {\"values\": {...}} with at least one setting")
        try:
            clean = validate_runtime(values)
        except (ValueError, TypeError) as exc:
            raise ApiError(400, "invalid_argument", str(exc)) from None
        changed = [key for key, value in clean.items() if self.values.get(key) != value]
        try:
            stored = write_runtime_settings(self.state_dir, clean)
        except OSError as exc:
            raise ApiError(500, "settings_write_failed", f"the settings could not be saved: {exc}") from None
        self.values.update({key: stored.get(key, value) for key, value in clean.items()})
        restart = [key for key in changed if key in RESTART_SETTINGS]
        return 200, {
            "values": dict(self.values),
            "changed": changed,
            "restart_required": bool(restart),
            "restart_required_fields": restart,
        }

    # -- test lab: loopback and tone ------------------------------------

    async def _loopback(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        enabled = body.get("enabled", True)
        if not isinstance(enabled, bool):
            raise ApiError(400, "invalid_argument", "enabled must be true or false")
        command = "loopback_start" if enabled else "loopback_stop"
        if not self._supports(command):
            raise ApiError(409, "unsupported_by_phone", "this phone build does not offer audio loopback")
        result = await self.controller.device.command(command, call_id=body.get("call_id"), timeout=5)
        return 200, {"enabled": enabled, "result": result}

    async def _tone(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        if not self._supports("tone_start"):
            raise ApiError(409, "unsupported_by_phone", "this phone build does not offer a test tone")
        try:
            hz = int(body.get("hz", 1000))
            dbfs = float(body.get("dbfs", -20))
        except (TypeError, ValueError):
            raise ApiError(400, "invalid_argument", "hz must be an integer and dbfs a number") from None
        if not 100 <= hz <= 4000:
            raise ApiError(400, "invalid_argument", "hz must be between 100 and 4000")
        if not -60 <= dbfs <= 0:
            raise ApiError(400, "invalid_argument", "dbfs must be between -60 and 0")
        seconds = body.get("seconds")
        if seconds is not None:
            try:
                seconds = float(seconds)
            except (TypeError, ValueError):
                raise ApiError(400, "invalid_argument", "seconds must be a number") from None
            if not 1 <= seconds <= 10:
                raise ApiError(400, "invalid_argument", "seconds must be between 1 and 10")
        call_id = body.get("call_id") if isinstance(body.get("call_id"), str) else None
        device = self.controller.device
        result = await device.command("tone_start", call_id=call_id, hz=hz, dbfs=dbfs, timeout=5)
        if seconds is not None:
            task = asyncio.create_task(self._stop_tone(device, float(seconds), call_id), name="app-tone-stop")
            self._tasks.add(task)
            task.add_done_callback(self._tasks.discard)
        return 200, {"started": True, "hz": hz, "dbfs": dbfs, "stop_after_s": seconds, "result": result}

    @staticmethod
    async def _stop_tone(device: Any, seconds: float, call_id: str | None) -> None:
        await asyncio.sleep(seconds)
        with contextlib.suppress(Exception):
            await device.command("tone_stop", call_id=call_id, timeout=5)

    # -- doctor ---------------------------------------------------------

    def _doctor_checks(self) -> list[dict[str, Any]]:
        device = self.controller.device
        info = getattr(device, "info", {}) or {}
        checks: list[dict[str, Any]] = [{
            "name": "daemon",
            "status": "ok",
            "detail": f"phonxietyd {__version__} answering (pid {os.getpid()})",
        }]
        connected = bool(getattr(device, "connected", False))
        checks.append({
            "name": "phone",
            "status": "ok" if connected else "fail",
            "detail": f"{info.get('device')} connected" if connected else "no phone is connected",
        })
        link = self._link_info() or {}
        iroh = bool(link.get("iroh_available"))
        checks.append({
            "name": "iroh_relay",
            "status": "ok" if iroh else "warn",
            "detail": "iroh endpoint bound" if iroh else "iroh endpoint unavailable; pair by address instead",
        })
        backend = self.values["voice_backend"]
        if backend == "codex":
            path = self.settings.codex_auth_path or default_auth_path()
            present = path.is_file()
            checks.append({
                "name": "codex_auth",
                "status": "ok" if present else "fail",
                "detail": str(path) if present else f"no Codex credentials at {path}; run `phonxiety auth`",
            })
        else:
            checks.append({"name": "voice_backend", "status": "ok", "detail": f"{backend} is configured"})
        try:
            free = shutil.disk_usage(self.state_dir).free
            checks.append({
                "name": "disk",
                "status": "ok" if free > 200 * 2**20 else "warn",
                "detail": f"{free // 2**20} MiB free in {self.state_dir}",
            })
        except OSError as exc:
            checks.append({"name": "disk", "status": "warn", "detail": f"cannot read free space: {exc}"})
        relays = self.values.get("iroh_relays") or []
        checks.append({
            "name": "relays",
            "status": "ok",
            "detail": f"{len(relays)} custom relay(s): {', '.join(_relay_host(url) for url in relays)}"
            if relays else "using iroh's public relays",
        })
        return checks

    async def _doctor(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        checks = self._doctor_checks()
        worst = "fail" if any(check["status"] == "fail" for check in checks) else (
            "warn" if any(check["status"] == "warn" for check in checks) else "ok"
        )
        return 200, {
            "status": worst,
            "checks": checks,
            "fixes": [
                {"fix": "relay_config", "description": "re-read the relay list from disk", "restart_required": True},
                {"fix": "state_dirs", "description": "recreate the archive and diagnostics directories", "restart_required": False},
            ],
            "checked_at": _iso(time.time()),
        }

    async def _doctor_fix(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        fix = body.get("fix")
        if fix not in FIXES:
            raise ApiError(400, "unknown_fix", f"fix must be one of: {', '.join(FIXES)}")
        applied: list[dict[str, Any]] = []
        restart = False
        if fix == "relay_config":
            from .settings import runtime_overrides

            stored = runtime_overrides(self.state_dir)
            relays = stored.get("iroh_relays")
            if isinstance(relays, list):
                self.values["iroh_relays"] = list(relays)
            else:
                try:
                    entries = (self.state_dir / "iroh-relays").read_text().splitlines()
                except FileNotFoundError:
                    entries = []
                self.values["iroh_relays"] = [
                    url for url in (entry.split("#", 1)[0].strip() for entry in entries) if url
                ]
            applied.append({
                "fix": fix,
                "detail": f"{len(self.values['iroh_relays'])} relay(s): "
                + (", ".join(_relay_host(url) for url in self.values["iroh_relays"]) or "public relays"),
            })
            restart = True
        else:
            for directory in (self.archive_dir, self.link_tests_dir, self.bundles_dir, self.updates_dir):
                directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            applied.append({"fix": fix, "detail": "state directories are present"})
        return 200, {"applied": applied, "restart_required": restart}

    # -- diagnostics ----------------------------------------------------

    def _log_tail(self, lines: int = 300) -> list[str]:
        try:
            data = self.log_path.read_text(errors="replace")
        except (FileNotFoundError, OSError):
            return []
        tail = data.splitlines()[-lines:]
        return redact("\n".join(tail)).splitlines()

    def _diagnostics_payload(self, *, include_log: bool = True) -> dict[str, Any]:
        device = self.controller.device
        link = self._link_info() or {}
        payload: dict[str, Any] = {
            "daemon": {
                "version": __version__,
                "protocol": PROTOCOL_VERSION,
                "app_api": APP_API_VERSION,
                "pid": os.getpid(),
                "uptime_s": round(time.monotonic() - self._started, 1),
            },
            "settings": {
                "audio_codec": self.values["audio_codec"],
                "opus_bitrate": self.values["opus_bitrate"],
                "voice_backend": self.values["voice_backend"],
                "codex_voice": self.values["codex_voice"],
                "iroh_relays": [_relay_host(url) for url in self.values.get("iroh_relays") or []],
            },
            "phone": device.status() if hasattr(device, "status") else {},
            "counters": device.counters() if hasattr(device, "counters") else {},
            "link": {
                "endpoint_id": link.get("endpoint_id"),
                "address_port": link.get("address_port"),
                "certificate": link.get("certificate"),
                "iroh_available": bool(link.get("iroh_available")),
            },
            "paths": {
                "state_dir": str(self.state_dir),
                "archive": str(self.archive_dir),
                "link_tests": str(self.link_tests_dir),
                "log": str(self.log_path),
            },
            "generated_at": _iso(time.time()),
        }
        if include_log:
            payload["log_tail"] = self._log_tail()
        return payload

    async def _diagnostics(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        return 200, self._diagnostics_payload()

    def _bundle_path(self, bundle_id: str) -> Path:
        if not BUNDLE_ID.fullmatch(bundle_id):
            raise ApiError(400, "invalid_argument", "bundle_id is invalid")
        return self.bundles_dir / f"{bundle_id}.zip"

    def _bundle_bytes(self, bundle_id: str) -> bytes:
        path = self._bundle_path(bundle_id)
        try:
            return path.read_bytes()
        except FileNotFoundError:
            raise ApiError(404, "bundle_not_found", "that bundle has expired or was never made") from None
        except OSError as exc:
            raise ApiError(500, "bundle_unreadable", f"the bundle cannot be read: {exc}") from None

    async def _create_bundle(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        bundle_id = uuid.uuid4().hex[:16]
        buffer_path = self._bundle_path(bundle_id)
        self.bundles_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        manifest = {
            "version": __version__,
            "protocol": PROTOCOL_VERSION,
            "app_api": APP_API_VERSION,
            "created_at": _iso(time.time()),
        }
        index = [
            {key: row.get(key) for key in ("call_id", "state", "created_at", "call_outcome", "task_outcome")}
            for row in self._history()[:200]
        ]
        temporary = buffer_path.with_name(f".{buffer_path.name}.part")
        try:
            with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as bundle:
                bundle.writestr("manifest.json", json.dumps(manifest, indent=2))
                bundle.writestr("diagnostics.json", json.dumps(self._diagnostics_payload(include_log=False), indent=2))
                bundle.writestr("calls/index.json", json.dumps(index, indent=2))
                log_text = redact("\n".join(self._log_tail(4000)))[: 1024 * 1024]
                bundle.writestr("logs/daemon.log", log_text)
            os.chmod(temporary, 0o600)
            os.replace(temporary, buffer_path)
        except OSError as exc:
            temporary.unlink(missing_ok=True)
            raise ApiError(500, "bundle_write_failed", f"the bundle could not be written: {exc}") from None
        self._prune_bundles()
        size = buffer_path.stat().st_size
        return 200, {
            "bundle_id": bundle_id,
            "size": size,
            "created_at": manifest["created_at"],
            "attachment": f"/app/v1/diagnostics/bundle/{bundle_id}",
        }

    def _prune_bundles(self) -> None:
        try:
            paths = sorted(self.bundles_dir.glob("*.zip"), key=lambda path: path.stat().st_mtime, reverse=True)
        except OSError:
            return
        for stale in paths[BUNDLES_KEEP:]:
            stale.unlink(missing_ok=True)
        for path in paths[:BUNDLES_KEEP]:
            with contextlib.suppress(OSError):
                if time.time() - path.stat().st_mtime > BUNDLES_TTL_S:
                    path.unlink(missing_ok=True)

    async def _bundle_page(self, query: dict[str, Any], body: dict[str, Any], bundle_id: str) -> tuple[int, dict[str, Any]]:
        data = self._bundle_bytes(bundle_id)
        return 200, {"bundle_id": bundle_id, "format": "zip", **self._page(data, query)}

    # -- updates and pairing --------------------------------------------

    async def _update_manifest(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        path = self.updates_dir / "manifest.json"
        try:
            manifest = json.loads(path.read_text())
        except FileNotFoundError:
            raise ApiError(404, "manifest_unavailable", "this daemon publishes no update manifest") from None
        except (OSError, ValueError) as exc:
            raise ApiError(500, "manifest_invalid", f"the manifest cannot be read: {exc}") from None
        if not isinstance(manifest, dict):
            raise ApiError(500, "manifest_invalid", "the manifest is not an object")
        channels = manifest.get("channels")
        if isinstance(channels, dict) and channels:
            name = str(query.get("channel") or manifest.get("channel") or "stable")
            selected = channels.get(name)
            if not isinstance(selected, dict):
                raise ApiError(404, "unknown_channel", f"no channel {name}")
            payload = {key: value for key, value in manifest.items() if key != "channels"}
            payload["channel"] = name
            payload["entry"] = selected
            return 200, payload
        return 200, manifest

    async def _compat(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        client: dict[str, Any] = {}
        notes: list[str] = []
        compatible = True
        client_app_api = query.get("app_api")
        if client_app_api is not None:
            try:
                client["app_api"] = int(client_app_api)
            except (TypeError, ValueError):
                raise ApiError(400, "invalid_argument", "app_api must be an integer") from None
            if client["app_api"] > APP_API_VERSION:
                compatible = False
                notes.append("update the daemon: the app speaks a newer app API")
            elif client["app_api"] < APP_API_VERSION:
                notes.append("the app speaks an older app API; newer endpoints stay unavailable")
        client_protocol = query.get("protocol")
        if client_protocol is not None:
            try:
                client["protocol"] = int(client_protocol)
            except (TypeError, ValueError):
                raise ApiError(400, "invalid_argument", "protocol must be an integer") from None
            if client["protocol"] not in PROTOCOL_VERSIONS:
                compatible = False
                notes.append(f"the daemon accepts device protocol {', '.join(str(v) for v in PROTOCOL_VERSIONS)}")
        return 200, {
            "daemon_version": __version__,
            "app_api": APP_API_VERSION,
            "protocol": PROTOCOL_VERSION,
            "protocol_versions": list(PROTOCOL_VERSIONS),
            "client": client,
            "compatible": compatible,
            "notes": notes,
        }

    async def _rotate(self, query: dict[str, Any], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        rotate_device = bool(body.get("device_token"))
        try:
            tokens = json.loads(self.tokens_path.read_text())
            if not isinstance(tokens, dict):
                raise ValueError("invalid tokens file")
        except FileNotFoundError:
            tokens = {}
        except (OSError, ValueError) as exc:
            raise ApiError(500, "tokens_unreadable", f"the credential file cannot be read: {exc}") from None
        app_token = secrets.token_urlsafe(32)
        tokens["app_token"] = app_token
        device_token = None
        if rotate_device:
            device_token = secrets.token_urlsafe(32)
            tokens["device_token"] = device_token
        try:
            self._write_tokens(tokens)
        except OSError as exc:
            raise ApiError(500, "tokens_unwritable", f"the credential file cannot be written: {exc}") from None
        self.app_token = app_token
        return 200, {
            "app_token": app_token,
            "device_token": device_token,
            "restart_required": bool(device_token),
        }

    def _write_tokens(self, data: dict[str, Any]) -> None:
        fd_path = self.tokens_path
        directory = fd_path.parent
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        temporary = directory / f".tokens-{os.getpid()}-{uuid.uuid4().hex[:6]}"
        try:
            temporary.write_text(json.dumps(data))
            os.chmod(temporary, 0o600)
            os.replace(temporary, fd_path)
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise

    # -- bulk attachments ----------------------------------------------

    def _attachment_bytes(self, path: str, query: dict[str, Any]) -> bytes:
        call = CALL_ID_PATH.match(path)
        if call is not None and call["action"] == "audio":
            call_id = self._safe_id(call["call_id"], "call_id")
            return self._audio_bytes(call_id, str(query.get("format") or "m4a").lower())
        bundle = BUNDLE_ID_PATH.match(path)
        if bundle is not None:
            return self._bundle_bytes(bundle["bundle_id"])
        raise ApiError(404, "not_found", f"no attachment at {path}")

    async def serve_attachment(self, header: dict[str, Any], send: Any, recv: Any) -> None:
        """Write one attachment on a phone-opened stream: chunks, then an end frame.

        Errors leave as the one text frame the phone understands; the stream is always
        finished so the phone never waits on it.
        """
        try:
            token = header.get("app_token")
            path = header.get("path")
            query = header.get("query") or {}
            if not isinstance(token, str) or not self.authorized(token):
                raise ApiError(401, "unauthorized", "the app token is missing or no longer valid")
            if not isinstance(path, str) or not isinstance(query, dict):
                raise ApiError(400, "bad_request", "path and query are required")
            data = self._attachment_bytes(path, query)
            digest = hashlib.sha256()
            for start in range(0, len(data), ATTACHMENT_CHUNK):
                chunk = data[start : start + ATTACHMENT_CHUNK]
                digest.update(chunk)
                await send.write_all(frame(chunk))
            await send.write_all(frame(json.dumps({
                "type": "end", "total": len(data), "sha256": digest.hexdigest(),
            })))
        except ApiError as exc:
            with contextlib.suppress(Exception):
                await send.write_all(frame(json.dumps({"type": "error", "code": exc.code, "message": exc.message})))
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - the stream is the report
            log.warning("Attachment %s failed: %s", header.get("path"), exc)
            with contextlib.suppress(Exception):
                await send.write_all(frame(json.dumps({
                    "type": "error", "code": "attachment_failed", "message": str(exc)[:300],
                })))
        finally:
            with contextlib.suppress(Exception):
                await send.finish()


def _validation_errors(exc: ValidationError) -> list[dict[str, str]]:
    errors: list[dict[str, str]] = []
    for error in exc.errors()[:10]:
        location = ".".join(str(item) for item in error.get("loc", ())) or "request"
        errors.append({"location": location, "message": str(error.get("msg", "invalid value")).replace("\n", " ")})
    return errors


def _validation_message(exc: ValidationError) -> str:
    errors = _validation_errors(exc)
    text = "; ".join(f"{item['location']}: {item['message']}" for item in errors) or "invalid request"
    return text[:600]


__all__ = ["APP_API_VERSION", "AppApi", "ApiError", "MAX_RESULT_BYTES", "redact"]
