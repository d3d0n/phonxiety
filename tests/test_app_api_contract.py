"""Shared `/app/v1` exchanges: one JSON file per request/response pair, for both halves.

Every file under `contracts/app-api` holds the envelope the phone sends (without the
transport-generated `id` and the app token) and the answer the daemon gives:

    {"request": {"method", "path", "query", "body"},
     "response": {"status": <int>, "body": <JSON>}}

The same files are decoded by the Android `DaemonContractTest`, so a renamed key on
either side fails one of the two suites instead of passing both. Files are generated
from the daemon's real output against a canonical, deterministic state (fixed clock,
deterministic ids and a pinned daemon version); regenerate them with

    PHONXIETY_UPDATE_CONTRACTS=1 uv run pytest tests/test_app_api_contract.py
"""

from __future__ import annotations

import ast
import asyncio
import contextlib
import difflib
import inspect
import itertools
import json
import os
import types
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from unittest import mock

import pytest

from phonxiety.app_api import KNOWN_PATHS, AppApi
from phonxiety.models import CallRequest, CallState
from test_app_api import TOKEN, _ApiCase

CONTRACTS = Path(__file__).resolve().parents[1] / "contracts" / "app-api"
REWRITE = os.environ.get("PHONXIETY_UPDATE_CONTRACTS") == "1"
REWRITE_HINT = "PHONXIETY_UPDATE_CONTRACTS=1 uv run pytest tests/test_app_api_contract.py"

# All sample data is synthetic: a documented test number, documentation-only networks.
DESTINATION = "+998901234567"
GOAL = "Confirm the Friday appointment is still on."
BASE_TIME = 1767225600.0  # 2026-01-01T00:00:00+00:00
ENDPOINT_ID = "864b90b7" + "0123456789abcdef" * 3 + "0123" + "cc62"
CERTIFICATE = "cc" * 32
LAN_ADDRESS = "wss://192.0.2.10:18763/device"
TAILNET_ADDRESS = "wss://[2001:db8::1]:18763/device"
ARCHIVED_CALL = "11111111-1111-4111-8111-111111111111"
UNKNOWN_CALL = "00000000-0000-4000-8000-000000000000"

ASSISTANT_LINE = "Hello, this is the Phonxiety contract line."
USER_LINE = "Yes, Friday at three works."
PARTIAL_LINE = "Yes, Friday at"

# Every route and method `AppApi._route`/`AppApi._call_route` answers, keyed by the
# path patterns used in the exchanges. The guard below probes the real dispatcher and
# compares it with this set, so a new route or method fails until it is listed here
# and covered by an exchange or an EXEMPT reason.
SERVED = frozenset({
    ("GET", "/app/v1/info"),
    ("GET", "/app/v1/status"),
    ("GET", "/app/v1/link-test"),
    ("POST", "/app/v1/link-test"),
    ("POST", "/app/v1/probe"),
    ("GET", "/app/v1/calls"),
    ("POST", "/app/v1/calls"),
    ("POST", "/app/v1/calls/validate"),
    ("POST", "/app/v1/test-call"),
    ("POST", "/app/v1/loopback"),
    ("POST", "/app/v1/tone"),
    ("GET", "/app/v1/settings"),
    ("PUT", "/app/v1/settings"),
    ("GET", "/app/v1/doctor"),
    ("POST", "/app/v1/doctor/fix"),
    ("GET", "/app/v1/diagnostics"),
    ("POST", "/app/v1/diagnostics/bundle"),
    ("GET", "/app/v1/diagnostics/bundle/{bundle_id}"),
    ("GET", "/app/v1/update/manifest"),
    ("GET", "/app/v1/update/artifact"),
    ("GET", "/app/v1/compat"),
    ("POST", "/app/v1/pair/rotate"),
    ("GET", "/app/v1/calls/{call_id}"),
    ("DELETE", "/app/v1/calls/{call_id}"),
    ("GET", "/app/v1/calls/{call_id}/transcript"),
    ("GET", "/app/v1/calls/{call_id}/events"),
    ("GET", "/app/v1/calls/{call_id}/audio"),
    ("POST", "/app/v1/calls/{call_id}/instruction"),
    ("POST", "/app/v1/calls/{call_id}/pause"),
    ("POST", "/app/v1/calls/{call_id}/resume"),
    ("POST", "/app/v1/calls/{call_id}/summarize"),
    ("POST", "/app/v1/calls/{call_id}/dtmf"),
    ("POST", "/app/v1/calls/{call_id}/hangup"),
    ("GET", "/app/v1/calls/{call_id}/marks"),
    ("POST", "/app/v1/calls/{call_id}/marks"),
})

# Byte streams whose bytes cannot be reproduced from a fresh canonical state.
EXEMPT = {
    ("GET", "/app/v1/diagnostics/bundle/{bundle_id}"):
        "byte stream: the same zip served page by page, not reproducible from a fresh state",
}


class _Harness(_ApiCase):
    """`_ApiCase` with the fakes from `test_app_api` used outside the unittest runner."""

    __test__ = False  # pytest would otherwise collect the unittest method below

    async def runTest(self) -> None:  # pragma: no cover - never run as a unittest
        pass


class _FrozenClock:
    """A clock for the daemon modules only: fixed base, one second per reading.

    Patching `time.time` itself would also reach logging and the test runner; each
    daemon module gets this proxy instead, and `monotonic` stays the real one so
    deadlines keep working.
    """

    def __init__(self) -> None:
        self._readings = itertools.count()

    def time(self) -> float:
        return BASE_TIME + next(self._readings)

    @staticmethod
    def monotonic() -> float:
        import time as real

        return real.monotonic()


class _FixedIds:
    def __init__(self) -> None:
        self._ids = itertools.count(1)

    def __call__(self) -> uuid.UUID:
        return uuid.UUID(f"00000000-0000-4000-8000-{next(self._ids):012x}")


class _FixedTokens:
    def __init__(self) -> None:
        self._tokens = itertools.count(1)

    def __call__(self, _length: int) -> str:
        return f"contract-token-{next(self._tokens):04d}"


@contextlib.contextmanager
def _deterministic() -> Any:
    clock = _FrozenClock()
    with (
        mock.patch("phonxiety.app_api.time", clock),
        mock.patch("phonxiety.controller.time", clock),
        mock.patch("phonxiety.store.time", clock),
        mock.patch("uuid.uuid4", side_effect=_FixedIds()),
        mock.patch("secrets.token_urlsafe", side_effect=_FixedTokens()),
        mock.patch("os.getpid", return_value=4242),
        # The daemon version is part of the answer; pin it so a release bump does
        # not rewrite every contract file.
        mock.patch("phonxiety.app_api.__version__", "0.4.0"),
        mock.patch(
            "phonxiety.app_api.addresses_for",
            return_value={"lan": [LAN_ADDRESS], "tailscale": [TAILNET_ADDRESS]},
        ),
        mock.patch("phonxiety.app_api.shutil.which", return_value=None),
        mock.patch(
            "phonxiety.app_api.shutil.disk_usage",
            return_value=types.SimpleNamespace(free=10 * 1024**3),
        ),
        mock.patch(
            "phonxiety.app_api._probe_lan_listener",
            return_value={
                "reachable": False,
                "address": LAN_ADDRESS,
                "reason": "no_listener_answer",
            },
        ),
    ):
        yield


def _canonical_environment(case: _ApiCase) -> None:
    """The listener facts and voice backend the canned answers assume."""
    case.api._link_info = lambda: {
        "endpoint_id": ENDPOINT_ID,
        "address_port": 18763,
        "certificate": CERTIFICATE,
        "iroh_available": True,
    }
    # The default `codex` backend reads a credentials file that exists on some
    # computers and not others; the doctor answer must not depend on the host.
    case.api.values["voice_backend"] = "gemini"
    # The shared fake fingerprints itself at a private address; the exchanges use a
    # documentation address instead, so a route string carries no real network.
    case.device.link_test_result["route"] = "direct 192.0.2.10, 4 ms"
    case.device.link_test_result["route_after"] = "direct 192.0.2.10, 5 ms"


def _manifest_entry(version: str = "0.2.0") -> dict[str, Any]:
    """One release document every field of the app's installer contract requires.

    The artifacts' digests are synthetic: the app checks their shape here, and the
    bytes of `app.apk`/`module.zip` never claim to match them.
    """
    return {
        "version": version,
        "versionCode": 2,
        "zipUrl": "https://192.0.2.10/phonxiety-bridge-0.2.0.zip",
        "changelog": "https://192.0.2.10/CHANGELOG.md",
        "published_at": "2026-01-01T00:00:00+00:00",
        "apk": {
            "version": version,
            "versionCode": 2,
            "url": "daemon:apk",
            "file": "app.apk",
            "sha256": "a" * 64,
            "size": 12,
            "signingCertSha256": CERTIFICATE,
            "minSdk": 34,
        },
        "module": {
            "version": version,
            "versionCode": 2,
            "zipUrl": "https://192.0.2.10/phonxiety-bridge-0.2.0.zip",
            "url": "daemon:module",
            "file": "module.zip",
            "sha256": "b" * 64,
            "size": 14,
            "schemaVersion": 1,
            "requiresReboot": True,
            "permissions": ["android.permission.CAPTURE_AUDIO_OUTPUT"],
        },
        "daemon": {"minVersion": "0.1.0", "protocol": 3, "appApi": 1, "moduleSchema": 1},
    }


def _manifest_document() -> dict[str, Any]:
    """The published file: channels wrap the release, as the daemon serves them."""
    return {
        "version": "0.2.0",
        "channels": {
            "stable": _manifest_entry("0.2.0"),
            "beta": _manifest_entry("0.3.0-rc1"),
        },
    }


def _record(case: _ApiCase) -> Path:
    return case.state / "calls"


def _archive(
    case: _ApiCase,
    *,
    call_id: str = ARCHIVED_CALL,
    marks: list[dict[str, Any]] | None = None,
    recording: dict[str, Any] | None = None,
    transcript: list[dict[str, Any]] | None = None,
) -> None:
    """The record an ended call leaves in the archive, with fixed timestamps."""
    request = CallRequest(
        request_id="contract-archived", destination=DESTINATION, goal=GOAL,
    )
    record = {
        "call_id": call_id,
        "request_id": "contract-archived",
        "destination": DESTINATION,
        "state": "ended",
        "call_outcome": "answered",
        "task_outcome": "succeeded",
        "result_status": "ready",
        "summary": "Friday at three is confirmed.",
        "fields": {"when": "Friday at three"},
        "error": None,
        "created_at": BASE_TIME - 60,
        "connected_at": BASE_TIME - 55,
        "ended_at": BASE_TIME - 5,
        "disconnect_cause": None,
        "transcript": transcript if transcript is not None else [
            {"sequence": 1, "role": "assistant", "text": ASSISTANT_LINE, "at": BASE_TIME - 50},
            {"sequence": 2, "role": "user", "text": USER_LINE, "at": BASE_TIME - 40},
        ],
        "current_partials": [],
        "transcript_complete": True,
        "request": request.model_dump(mode="json"),
        "audio_codec": "opus",
        "far_end": "person",
        "recording": recording or {
            "file": None,
            "error": "recording_unavailable: the canonical state has no audio",
        },
        "marks": marks or [],
    }
    directory = _record(case)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{call_id}.json").write_text(json.dumps(record, ensure_ascii=False, indent=2))


async def _answer(case: _ApiCase, *, request_id: str = "contract-live") -> str:
    """A call the daemon owns, answered and live."""
    request = CallRequest(request_id=request_id, destination=DESTINATION, goal=GOAL)
    snapshot = await case.controller.place_call(request)
    call_id = snapshot["call_id"]
    await asyncio.wait_for(case.device.dialled.wait(), 1)
    await case.controller.on_device_event(
        {"type": "call_state", "call_id": call_id, "state": "active"},
    )
    case.contract_call_id = call_id
    return call_id


async def _live(case: _ApiCase) -> str:
    """A live call with final turns, a partial and a mark, for the events page."""
    call_id = await _answer(case)
    case.store.append_transcript(call_id, "assistant", ASSISTANT_LINE, True)
    case.store.append_transcript(call_id, "user", USER_LINE, True)
    case.store.append_transcript(call_id, "user", PARTIAL_LINE, False)
    await case.controller.add_mark(call_id, "agreed", "Friday 15:00")
    return call_id


def _finished_row(case: _ApiCase) -> str:
    """A terminal store row with its result ready, as a finished call leaves it."""
    request = CallRequest(request_id="contract-ended", destination=DESTINATION, goal=GOAL)
    call_id, _ = case.store.reserve(request)
    case.store.submit_dial(call_id)
    case.store.set_active(call_id)
    case.store.append_transcript(call_id, "assistant", ASSISTANT_LINE, True)
    case.store.append_transcript(call_id, "user", USER_LINE, True)
    case.store.terminate(call_id, CallState.ENDED, "answered")
    case.store.finish_result(
        call_id,
        task_outcome="succeeded",
        summary="Friday at three is confirmed.",
        fields={"when": "Friday at three"},
        transcript_complete=True,
    )
    case.contract_call_id = call_id
    return call_id


def _publish_updates(case: _ApiCase, manifest: dict[str, Any], files: dict[str, bytes]) -> None:
    updates = case.state / "updates"
    updates.mkdir(parents=True, exist_ok=True)
    (updates / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    for name, data in files.items():
        (updates / name).write_bytes(data)


def _store_link_test(case: _ApiCase) -> dict[str, Any]:
    record = {
        "id": "0123456789abcdef",
        "at": "2026-01-01T00:00:00+00:00",
        "codec": "opus",
        "bitrate": None,
        "transport": "stream",
        "peer": "iroh:abcd",
        "route": "direct 192.0.2.10, 4 ms",
        "route_after": "direct 192.0.2.10, 5 ms",
        "seconds": 1.0,
        "frame_bytes": 48,
        "frames": {"sent": 50, "echoed": 50},
        "p50": 30,
        "p95": 44,
        "max": 61,
        "loss": 0.0,
        "reorder": None,
        "throughput_bps": 2400,
        "ping_ms": {"median": 4, "p95": 8, "max": 9},
        "send_stall_ms": 2,
        "healthy": True,
        "verdict": "good",
    }
    directory = case.state / "link-tests"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "0000000000000-0123456789abcdef.json").write_text(
        json.dumps(record, ensure_ascii=False, indent=2),
    )
    return record


def _write_daemon_log(case: _ApiCase) -> None:
    secret = "sk-" + "A" * 48
    (case.state / "daemon.log").write_text(
        "2026-01-01 INFO daemon started on the canonical state\n"
        f"2026-01-01 INFO app_token={secret} accepted\n"
        f"2026-01-01 INFO Authorization: Bearer {secret}\n"
    )


@dataclass(frozen=True)
class Exchange:
    name: str
    route: tuple[str, str]
    run: Callable[[_ApiCase], Awaitable[tuple[dict[str, Any], int, dict[str, Any]]]]
    prepare: Callable[[_ApiCase], Awaitable[None] | None] = lambda case: None


async def _call(
    case: _ApiCase,
    method: str,
    path: str,
    *,
    query: dict[str, str] | None = None,
    body: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], int, dict[str, Any]]:
    status, response = await case.call(method, path, query=query or {}, body=body or {})
    request: dict[str, Any] = {"method": method, "path": path}
    if query:
        request["query"] = query
    if body is not None:
        request["body"] = body
    return request, status, response


async def _prepare_live(case: _ApiCase) -> None:
    await _live(case)


async def _prepare_answer(case: _ApiCase) -> None:
    await _answer(case)


async def _prepare_archive(case: _ApiCase) -> None:
    _archive(case)


async def _prepare_archive_marks(case: _ApiCase) -> None:
    _archive(case, marks=[{"mark": "agreed", "at": BASE_TIME - 30, "note": "Friday 15:00"}])


async def _prepare_audio(case: _ApiCase) -> None:
    _archive(case, recording={
        "file": f"{ARCHIVED_CALL}.wav",
        "compressed": {"file": f"{ARCHIVED_CALL}.m4a"},
    })
    (_record(case) / f"{ARCHIVED_CALL}.wav").write_bytes(b"RIFF" + bytes(8))
    (_record(case) / f"{ARCHIVED_CALL}.m4a").write_bytes(b"M4A-contract")


async def _prepare_ended_row(case: _ApiCase) -> None:
    _finished_row(case)


async def _prepare_history(case: _ApiCase) -> None:
    _archive(case)
    await _answer(case)


async def _prepare_phone_controls(case: _ApiCase) -> None:
    case.device.info["control_caps"] = ["loopback_start", "loopback_stop", "tone_start"]


EXCHANGES: list[Exchange] = [
    Exchange(
        "info_get", ("GET", "/app/v1/info"),
        lambda case: _call(case, "GET", "/app/v1/info"),
    ),
    Exchange(
        "status_get", ("GET", "/app/v1/status"),
        lambda case: _call(case, "GET", "/app/v1/status"),
    ),
    Exchange(
        "link_test_post", ("POST", "/app/v1/link-test"),
        lambda case: _call(case, "POST", "/app/v1/link-test", body={"seconds": 1.0}),
    ),
    Exchange(
        "link_tests_get", ("GET", "/app/v1/link-test"),
        lambda case: _call(case, "GET", "/app/v1/link-test", query={"limit": "1"}),
        lambda case: _store_link_test(case),
    ),
    Exchange(
        "probe_post", ("POST", "/app/v1/probe"),
        lambda case: _call(
            case, "POST", "/app/v1/probe", body={"candidates": ["iroh", "tailscale", "lan"]},
        ),
        _prepare_phone_controls,
    ),
    Exchange(
        "calls_get", ("GET", "/app/v1/calls"),
        lambda case: _call(case, "GET", "/app/v1/calls", query={"limit": "2"}),
        _prepare_history,
    ),
    Exchange(
        "calls_place", ("POST", "/app/v1/calls"),
        lambda case: _call(case, "POST", "/app/v1/calls", body={
            "destination": DESTINATION,
            "goal": GOAL,
            "context": "Synthetic contract sample.",
            "constraints": "Keep the call under a minute.",
            "confirmation_id": "contract-1",
            "max_duration_s": 600,
            "ring_timeout_s": 45,
            "result_fields": ["heard"],
        }),
    ),
    Exchange(
        "calls_place_missing_confirmation", ("POST", "/app/v1/calls"),
        lambda case: _call(case, "POST", "/app/v1/calls", body={
            "destination": DESTINATION,
            "goal": GOAL,
        }),
    ),
    Exchange(
        "calls_validate_ok", ("POST", "/app/v1/calls/validate"),
        lambda case: _call(case, "POST", "/app/v1/calls/validate", body={
            "destination": DESTINATION,
            "goal": "Confirm a table for two.",
        }),
    ),
    Exchange(
        "calls_validate_invalid", ("POST", "/app/v1/calls/validate"),
        lambda case: _call(case, "POST", "/app/v1/calls/validate", body={
            "destination": "12",
            "goal": "Book",
        }),
    ),
    Exchange(
        "calls_get_unknown", ("GET", "/app/v1/calls/{call_id}"),
        lambda case: _call(case, "GET", f"/app/v1/calls/{UNKNOWN_CALL}"),
    ),
    Exchange(
        # The app's `call("validate")`: this daemon answers 405 on a call route used
        # with the wrong method, and the app must surface the code.
        "calls_validate_wrong_method", ("GET", "/app/v1/calls/validate"),
        lambda case: _call(case, "GET", "/app/v1/calls/validate"),
    ),
    Exchange(
        "test_call_post", ("POST", "/app/v1/test-call"),
        lambda case: _call(case, "POST", "/app/v1/test-call", body={
            "destination": DESTINATION,
            "max_duration_s": 30,
        }),
    ),
    Exchange(
        "call_get_live", ("GET", "/app/v1/calls/{call_id}"),
        lambda case: _call(case, "GET", f"/app/v1/calls/{case.contract_call_id}"),
        _prepare_live,
    ),
    Exchange(
        "call_get_ended", ("GET", "/app/v1/calls/{call_id}"),
        lambda case: _call(case, "GET", f"/app/v1/calls/{ARCHIVED_CALL}"),
        _prepare_archive,
    ),
    Exchange(
        "call_transcript_get", ("GET", "/app/v1/calls/{call_id}/transcript"),
        lambda case: _call(
            case, "GET", f"/app/v1/calls/{ARCHIVED_CALL}/transcript",
            query={"cursor": "0", "limit": "1"},
        ),
        _prepare_archive,
    ),
    Exchange(
        "call_events_live", ("GET", "/app/v1/calls/{call_id}/events"),
        lambda case: _call(
            case, "GET", f"/app/v1/calls/{case.contract_call_id}/events",
            query={"since": "0", "wait_s": "0.0"},
        ),
        _prepare_live,
    ),
    Exchange(
        "call_events_ended", ("GET", "/app/v1/calls/{call_id}/events"),
        lambda case: _call(
            case, "GET", f"/app/v1/calls/{case.contract_call_id}/events",
            query={"since": "0", "wait_s": "0.0"},
        ),
        _prepare_ended_row,
    ),
    Exchange(
        "call_instruction_post", ("POST", "/app/v1/calls/{call_id}/instruction"),
        lambda case: _call(
            case, "POST", f"/app/v1/calls/{case.contract_call_id}/instruction",
            body={"text": "Ask whether Friday at three still works."},
        ),
        _prepare_answer,
    ),
    Exchange(
        "call_pause_post", ("POST", "/app/v1/calls/{call_id}/pause"),
        lambda case: _call(case, "POST", f"/app/v1/calls/{case.contract_call_id}/pause"),
        _prepare_answer,
    ),
    Exchange(
        "call_resume_post", ("POST", "/app/v1/calls/{call_id}/resume"),
        lambda case: _call(case, "POST", f"/app/v1/calls/{case.contract_call_id}/resume"),
        _prepare_answer,
    ),
    Exchange(
        "call_summarize_post", ("POST", "/app/v1/calls/{call_id}/summarize"),
        lambda case: _call(case, "POST", f"/app/v1/calls/{case.contract_call_id}/summarize"),
        _prepare_answer,
    ),
    Exchange(
        "call_dtmf_post", ("POST", "/app/v1/calls/{call_id}/dtmf"),
        lambda case: _call(
            case, "POST", f"/app/v1/calls/{case.contract_call_id}/dtmf", body={"digits": "12#*"},
        ),
        _prepare_answer,
    ),
    Exchange(
        "call_hangup_post", ("POST", "/app/v1/calls/{call_id}/hangup"),
        lambda case: _call(case, "POST", f"/app/v1/calls/{case.contract_call_id}/hangup"),
        _prepare_ended_row,
    ),
    Exchange(
        "call_marks_get", ("GET", "/app/v1/calls/{call_id}/marks"),
        lambda case: _call(case, "GET", f"/app/v1/calls/{ARCHIVED_CALL}/marks"),
        _prepare_archive_marks,
    ),
    Exchange(
        "call_marks_post", ("POST", "/app/v1/calls/{call_id}/marks"),
        lambda case: _call(
            case, "POST", f"/app/v1/calls/{ARCHIVED_CALL}/marks",
            body={"mark": "agreed", "note": "Friday 15:00"},
        ),
        _prepare_archive,
    ),
    Exchange(
        "call_delete", ("DELETE", "/app/v1/calls/{call_id}"),
        lambda case: _call(
            case, "DELETE", f"/app/v1/calls/{ARCHIVED_CALL}",
            body={"confirm": ARCHIVED_CALL},
        ),
        _prepare_archive,
    ),
    Exchange(
        "call_audio_get", ("GET", "/app/v1/calls/{call_id}/audio"),
        lambda case: _call(
            case, "GET", f"/app/v1/calls/{ARCHIVED_CALL}/audio", query={"format": "m4a"},
        ),
        _prepare_audio,
    ),
    Exchange(
        "settings_get", ("GET", "/app/v1/settings"),
        lambda case: _call(case, "GET", "/app/v1/settings"),
    ),
    Exchange(
        "settings_put", ("PUT", "/app/v1/settings"),
        lambda case: _call(case, "PUT", "/app/v1/settings", body={
            "values": {"audio_codec": "pcm", "opus_bitrate": 24000},
        }),
    ),
    Exchange(
        "settings_put_invalid", ("PUT", "/app/v1/settings"),
        lambda case: _call(case, "PUT", "/app/v1/settings", body={
            "values": {"audio_codec": "mp3"},
        }),
    ),
    Exchange(
        "loopback_post", ("POST", "/app/v1/loopback"),
        lambda case: _call(case, "POST", "/app/v1/loopback", body={"enabled": True}),
        _prepare_phone_controls,
    ),
    Exchange(
        "tone_post", ("POST", "/app/v1/tone"),
        lambda case: _call(case, "POST", "/app/v1/tone", body={"hz": 440, "dbfs": -18.0}),
        _prepare_phone_controls,
    ),
    Exchange(
        "doctor_get", ("GET", "/app/v1/doctor"),
        lambda case: _call(case, "GET", "/app/v1/doctor"),
    ),
    Exchange(
        "doctor_fix_post", ("POST", "/app/v1/doctor/fix"),
        lambda case: _call(case, "POST", "/app/v1/doctor/fix", body={"fix": "state_dirs"}),
    ),
    Exchange(
        "diagnostics_get", ("GET", "/app/v1/diagnostics"),
        lambda case: _call(case, "GET", "/app/v1/diagnostics"),
        lambda case: _write_daemon_log(case),
    ),
    Exchange(
        "diagnostics_bundle_post", ("POST", "/app/v1/diagnostics/bundle"),
        lambda case: _call(case, "POST", "/app/v1/diagnostics/bundle"),
    ),
    Exchange(
        "update_manifest_get", ("GET", "/app/v1/update/manifest"),
        lambda case: _call(case, "GET", "/app/v1/update/manifest", query={"channel": "stable"}),
        lambda case: _publish_updates(case, _manifest_document(), {"app.apk": b"contract-apk"}),
    ),
    Exchange(
        "update_artifact_get", ("GET", "/app/v1/update/artifact"),
        lambda case: _call(
            case, "GET", "/app/v1/update/artifact", query={"name": "apk"},
        ),
        lambda case: _publish_updates(
            case,
            _manifest_document(),
            {"app.apk": b"contract-apk", "module.zip": b"contract-module"},
        ),
    ),
    Exchange(
        "compat_get", ("GET", "/app/v1/compat"),
        lambda case: _call(
            case, "GET", "/app/v1/compat", query={"app_api": "1", "protocol": "3"},
        ),
    ),
    Exchange(
        "pair_rotate_post", ("POST", "/app/v1/pair/rotate"),
        lambda case: _call(case, "POST", "/app/v1/pair/rotate", body={}),
    ),
]


def _scrub(value: Any, needle: str, replacement: str) -> Any:
    if isinstance(value, str):
        return value.replace(needle, replacement)
    if isinstance(value, list):
        return [_scrub(item, needle, replacement) for item in value]
    if isinstance(value, dict):
        return {key: _scrub(item, needle, replacement) for key, item in value.items()}
    return value


def _canonical(case: _ApiCase, body: dict[str, Any]) -> dict[str, Any]:
    """Machine-independent values: this run's state path and a just-started uptime."""
    body = _scrub(body, str(case.state), "<state>")
    if "uptime_s" in body:
        body["uptime_s"] = 0.0
    daemon = body.get("daemon")
    if isinstance(daemon, dict) and "uptime_s" in daemon:
        daemon["uptime_s"] = 0.0
    # A bundle's zip length follows the file paths across machines; the app reads the
    # id. The key itself stays, so its presence and type are still checked.
    if "bundle_id" in body and "size" in body:
        body["size"] = 0
    return body


def _path(exchange: Exchange) -> Path:
    return CONTRACTS / f"{exchange.name}.json"


async def _run_exchange(exchange: Exchange) -> tuple[dict[str, Any], int, dict[str, Any]]:
    case = _Harness("runTest")
    await case.asyncSetUp()
    try:
        with _deterministic():
            _canonical_environment(case)
            prepared = exchange.prepare(case)
            if inspect.isawaitable(prepared):
                await prepared
            request, status, body = await exchange.run(case)
            return request, status, _canonical(case, body)
    finally:
        await case.asyncTearDown()


def _diff(expected: Any, actual: Any) -> str:
    return "\n".join(
        difflib.unified_diff(
            json.dumps(expected, indent=2, sort_keys=True, ensure_ascii=False).splitlines(),
            json.dumps(actual, indent=2, sort_keys=True, ensure_ascii=False).splitlines(),
            fromfile="contracts/app-api (checked in)",
            tofile="the daemon's answer",
            lineterm="",
        )
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("exchange", EXCHANGES, ids=[item.name for item in EXCHANGES])
async def test_exchange_matches_the_daemon(exchange: Exchange) -> None:
    request, status, body = await _run_exchange(exchange)
    payload = {"request": request, "response": {"status": status, "body": body}}
    path = _path(exchange)
    if REWRITE:
        path.parent.mkdir(parents=True, exist_ok=True)
        text = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
        if not path.exists() or path.read_text() != text:
            path.write_text(text)
        return
    assert path.is_file(), f"{path.name} is missing; run {REWRITE_HINT}"
    expected = json.loads(path.read_text())
    if expected.get("request") != request or expected.get("response") != payload["response"]:
        pytest.fail(
            f"{path.name} no longer matches the daemon.\n{_diff(expected, payload)}\n\n"
            f"Run {REWRITE_HINT} to rewrite the files from the daemon's real output."
        )


def _call_actions() -> list[str]:
    """Every action `_call_route` dispatches, read from its source instead of a list here."""
    tree = ast.parse(Path(inspect.getsourcefile(AppApi)).read_text())
    actions: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        if node.name != "_call_route":
            continue
        for compare in ast.walk(node):
            if not isinstance(compare, ast.Compare):
                continue
            if not (isinstance(compare.left, ast.Name) and compare.left.id == "action"):
                continue
            for part in [compare.left, *compare.comparators]:
                if isinstance(part, ast.Constant) and isinstance(part.value, str):
                    actions.add(part.value)
                elif isinstance(part, (ast.Tuple, ast.List)):
                    actions.update(
                        item.value
                        for item in part.elts
                        if isinstance(item, ast.Constant) and isinstance(item.value, str)
                    )
    return sorted(actions)


def _served(status: int, body: dict[str, Any]) -> bool:
    """Whether one probe reached a handler; `_call_route` names a missing action in a 404."""
    if status == 405:
        return False
    return not (status == 404 and str(body.get("message", "")).startswith("no endpoint"))


async def _served_inventory() -> set[tuple[str, str]]:
    """Every method/route pair the real dispatcher answers, probed one by one.

    Known paths answer 405 for a method they do not use; a call action the router
    does not know answers the same 404 as an unknown path. No hand-kept list is
    consulted, so a new route, method or call action shows up here as soon as the
    daemon learns it.
    """
    case = _Harness("runTest")
    await case.asyncSetUp()
    try:
        with _deterministic():
            _canonical_environment(case)
            _archive(case)
            probes = [(path, path) for path in sorted(KNOWN_PATHS)]
            probes.append(
                ("/app/v1/diagnostics/bundle/0000000000000000", "/app/v1/diagnostics/bundle/{bundle_id}"),
            )
            probes.append((f"/app/v1/calls/{ARCHIVED_CALL}", "/app/v1/calls/{call_id}"))
            probes += [
                (
                    f"/app/v1/calls/{ARCHIVED_CALL}/{action}",
                    f"/app/v1/calls/{{call_id}}/{action}",
                )
                for action in _call_actions()
            ]
            found: set[tuple[str, str]] = set()
            for concrete, pattern in probes:
                for method in ("GET", "POST", "PUT", "DELETE"):
                    # A rotate probe replaces the token; every other probe needs it back.
                    case.api.app_token = TOKEN
                    status, body = await case.call(method, concrete)
                    if _served(status, body):
                        found.add((method, pattern))
            return found
    finally:
        await case.asyncTearDown()


def test_every_route_has_an_exchange_or_an_exempt_reason() -> None:
    inventory = asyncio.run(_served_inventory())
    assert inventory == SERVED, (
        f"routes the daemon serves and SERVED does not list: {sorted(inventory - SERVED)}; "
        f"routes SERVED lists and the daemon does not serve: {sorted(SERVED - inventory)}"
    )
    covered = {exchange.route for exchange in EXCHANGES}
    uncovered = sorted(route for route in SERVED if route not in covered and route not in EXEMPT)
    assert not uncovered, f"no exchange for {uncovered}; add one or an EXEMPT reason"
    assert not (set(EXEMPT) & covered), "an exempted route also has an exchange"


def test_no_contract_file_is_orphaned() -> None:
    on_disk = {path.name for path in CONTRACTS.glob("*.json")}
    expected = {f"{exchange.name}.json" for exchange in EXCHANGES}
    assert on_disk == expected, (
        f"contracts/app-api has files no exchange describes: {sorted(on_disk - expected)}; "
        f"missing files: {sorted(expected - on_disk)}"
    )
