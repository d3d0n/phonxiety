"""Dedicated credential persistence and refresh behaviour (no network)."""

from __future__ import annotations

import asyncio
import base64
import json
import os
import stat
import time
from pathlib import Path

import pytest

from phonxiety.auth import (
    CodexAuth,
    CodexAuthMissing,
    CodexCredentials,
    codex_cli_auth_path,
    default_auth_path,
    decode_jwt_payload,
)


def _fake_auth_document() -> dict:
    return {
        "auth_mode": "chatgpt",
        "OPENAI_API_KEY": None,
        "tokens": {
            "id_token": "",
            "access_token": "not-a-real-access-token",
            "refresh_token": "not-a-real-refresh-token",
            "account_id": "acct-test",
        },
        "last_refresh": "2026-01-01T00:00:00Z",
        "custom_field": "preserved",
    }


def test_missing_own_login_does_not_consume_cli_credentials(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    codex_home = tmp_path / "codex"
    codex_home.mkdir()
    source = codex_home / "auth.json"
    source.write_text(json.dumps(_fake_auth_document()), encoding="utf-8")
    before = source.read_bytes()
    destination = tmp_path / "phonxiety" / "auth.json"
    monkeypatch.setenv("CODEX_HOME", str(codex_home))

    with pytest.raises(CodexAuthMissing):
        asyncio.run(CodexAuth(destination).credentials())

    assert source.read_bytes() == before
    assert not destination.exists()


def test_auth_paths_follow_environment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("PHONXIETY_AUTH_FILE", str(tmp_path / "one.json"))
    assert default_auth_path() == tmp_path / "one.json"
    monkeypatch.delenv("PHONXIETY_AUTH_FILE")
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    assert default_auth_path() == tmp_path / "state" / "phonxiety" / "auth.json"

    monkeypatch.setenv("CODEX_HOME", str(tmp_path / "codex"))
    assert codex_cli_auth_path() == tmp_path / "codex" / "auth.json"


def test_decode_jwt_rejects_garbage() -> None:
    assert decode_jwt_payload("") == {}
    assert decode_jwt_payload("one.two") == {}


def test_credentials_repr_hides_tokens() -> None:
    credentials = CodexCredentials(
        access_token="secret-access",
        refresh_token="secret-refresh",
        id_token="secret-id",
    )
    assert "secret-access" not in repr(credentials)
    assert "secret-refresh" not in repr(credentials)
    assert "secret-id" not in repr(credentials)


def _jwt(payload: dict) -> str:
    def encode(value: dict) -> str:
        raw = json.dumps(value, separators=(",", ":")).encode("utf-8")
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

    return f"{encode({'alg': 'none'})}.{encode(payload)}.sig"


class _FakeResponse:
    def __init__(self, status_code: int, payload: dict) -> None:
        self.status_code = status_code
        self._payload = payload
        self.text = json.dumps(payload)

    def json(self) -> dict:
        return self._payload


class _FakeClient:
    captured: dict = {}

    def __init__(self, *args, **kwargs) -> None:
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args) -> None:
        return None

    async def post(self, url, data=None, headers=None, **kwargs):
        type(self).captured["url"] = url
        type(self).captured["data"] = dict(data or {})
        return _FakeResponse(
            200,
            {
                "access_token": _jwt(
                    {
                        "exp": int(time.time()) + 3600,
                        "https://api.openai.com/auth": {
                            "chatgpt_account_id": "acct-test"
                        },
                    }
                ),
                "refresh_token": "rotated-refresh",
                "expires_in": 3600,
            },
        )


def test_expired_access_refreshes_own_copy_not_codex_file(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "codex" / "auth.json"
    source.parent.mkdir()
    expired = _jwt(
        {
            "exp": int(time.time()) - 60,
            "https://api.openai.com/auth": {"chatgpt_account_id": "acct-test"},
        }
    )
    document = _fake_auth_document()
    document["tokens"]["access_token"] = expired
    source.write_text(json.dumps(document), encoding="utf-8")
    source_before = source.read_bytes()

    dest = tmp_path / "phonxiety" / "auth.json"
    dest.parent.mkdir()
    dest.write_text(json.dumps(document), encoding="utf-8")

    import phonxiety.auth as auth_module

    _FakeClient.captured = {}
    monkeypatch.setattr(auth_module.httpx, "AsyncClient", _FakeClient)
    credentials = asyncio.run(CodexAuth(dest).credentials())

    assert credentials.access_token != expired
    assert credentials.refresh_token == "rotated-refresh"
    assert _FakeClient.captured["data"]["refresh_reason"] == "proactive_expiry"
    assert _FakeClient.captured["data"]["grant_type"] == "refresh_token"
    assert source.read_bytes() == source_before
    persisted = json.loads(dest.read_text(encoding="utf-8"))
    assert persisted["tokens"]["refresh_token"] == "rotated-refresh"
    assert persisted["tokens"]["access_token"] == credentials.access_token


def test_force_refresh_marks_unauthorized_recovery(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import phonxiety.auth as auth_module

    dest = tmp_path / "auth.json"
    document = _fake_auth_document()
    document["tokens"]["access_token"] = _jwt(
        {
            "exp": int(time.time()) + 3600,
            "https://api.openai.com/auth": {"chatgpt_account_id": "acct-test"},
        }
    )
    dest.write_text(json.dumps(document), encoding="utf-8")

    _FakeClient.captured = {}
    monkeypatch.setattr(auth_module.httpx, "AsyncClient", _FakeClient)
    credentials = asyncio.run(CodexAuth(dest).force_refresh())
    assert credentials.refresh_token == "rotated-refresh"
    assert _FakeClient.captured["data"]["refresh_reason"] == "unauthorized_recovery"
