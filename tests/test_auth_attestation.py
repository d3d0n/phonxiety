"""DeviceCheck envelope encoding (no native compile or token generation)."""

from __future__ import annotations

import base64
import json

from phonxiety.native.devicecheck import DeviceCheckToken, build_attestation


def _decode(envelope: str) -> bytes:
    payload = json.loads(envelope)
    assert payload["v"] == 1
    assert payload["s"] == 0
    assert payload["t"].startswith("v1.")
    encoded = payload["t"][3:]
    return base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))


def test_attestation_with_token_is_cbor_map_envelope() -> None:
    raw = _decode(build_attestation(DeviceCheckToken(supported=True, token_base64="tok", latency_ms=1.5)))
    # CBOR map with four text keys: token, bundle_id, f, t.
    assert raw[0] == 0xA4
    assert b"com.openai.codex" in raw
    assert b"tok" in raw


def test_attestation_without_token_uses_error_code() -> None:
    raw = _decode(build_attestation(DeviceCheckToken(supported=False, error="no gui")))
    assert raw[0] == 0xA3
    assert b"error_code" in raw
    assert b"com.openai.codex" in raw
