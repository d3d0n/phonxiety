"""Build and run the packaged DeviceCheck helper, then wrap its token.

The attestation envelope matches the Codex desktop client: a CBOR map with the
DeviceCheck token (or an error code), the ChatGPT bundle id, client signals and
the native call latency, base64url-encoded inside ``v1.<...>`` and returned as
``{"v":1,"s":0,"t":"v1...."}``.  An unavailable token simply omits the header,
exactly as the Codex desktop client does on hosts that cannot attest.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import locale as _locale
import logging
import os
import shutil
import platform
import struct
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

CHATGPT_BUNDLE_ID = "com.openai.codex"
HELPER_TIMEOUT_S = 10.0
APP_SESSION_ID = str(uuid.uuid4())
_STARTED_AT = time.monotonic()

_COMPILE_LOCK = threading.Lock()
_HELPER_SOURCE = Path(__file__).with_name("devicecheck_helper.m")


class DeviceCheckError(RuntimeError):
    """The DeviceCheck helper could not produce a result."""


class NativeBuildError(DeviceCheckError):
    """The packaged helper could not be compiled or found."""


@dataclass(frozen=True, slots=True)
class DeviceCheckToken:
    """One helper invocation's result."""

    supported: bool
    token_base64: str | None = None
    error: str | None = None
    latency_ms: float | None = None


def _cache_dir() -> Path:
    configured = os.environ.get("PHONXIETY_NATIVE_CACHE", "").strip()
    if configured:
        return Path(configured).expanduser()
    cache_home = os.environ.get("XDG_CACHE_HOME", "").strip()
    base = Path(cache_home).expanduser() if cache_home else Path.home() / ".cache"
    return base / "phonxiety" / "native"


def _prebuilt_helper() -> Path | None:
    configured = os.environ.get("PHONXIETY_DEVICECHECK_HELPER", "").strip()
    if not configured:
        return None
    path = Path(configured).expanduser()
    if not path.is_file():
        raise NativeBuildError(
            f"PHONXIETY_DEVICECHECK_HELPER does not name a file: {path}"
        )
    return path


def _find_clang() -> str:
    for candidate in ("clang",):
        found = shutil.which(candidate)
        if found:
            return found
    xcrun = shutil.which("xcrun")
    if xcrun:
        try:
            result = subprocess.run(
                [xcrun, "--find", "clang"],
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise NativeBuildError(
                f"could not query xcrun for clang: {type(exc).__name__}"
            ) from exc
        path = result.stdout.strip()
        if result.returncode == 0 and path and Path(path).is_file():
            return path
    raise NativeBuildError(
        "no clang toolchain found; install the Xcode command line tools to "
        "build the DeviceCheck helper"
    )


def _compile_helper() -> Path:
    with _COMPILE_LOCK:
        source = _HELPER_SOURCE
        prebuilt = _prebuilt_helper()
        if prebuilt is not None:
            return prebuilt
        if not source.is_file():
            raise NativeBuildError(f"packaged DeviceCheck helper is missing: {source}")

        digest = hashlib.sha256(source.read_bytes()).hexdigest()[:16]
        cache = _cache_dir()
        target = cache / f"devicecheck-{digest}"
        if target.is_file() and os.access(target, os.X_OK):
            return target

        if not cache.exists():
            cache.mkdir(mode=0o700, parents=True)
            try:
                cache.chmod(0o700)
            except OSError:
                pass
        else:
            cache.mkdir(parents=True, exist_ok=True)
        clang = _find_clang()
        tmp = cache / f".devicecheck-{digest}.{os.getpid()}.{uuid.uuid4().hex[:6]}.tmp"
        command = [
            clang,
            "-fobjc-arc",
            "-O2",
            "-mmacosx-version-min=11.0",
            "-framework",
            "Foundation",
            "-framework",
            "DeviceCheck",
            "-framework",
            "Security",
            "-o",
            str(tmp),
            str(source),
        ]
        try:
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                timeout=120,
                check=False,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise NativeBuildError(
                f"could not run clang for the DeviceCheck helper: {type(exc).__name__}"
            ) from exc
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "").strip()
            if len(detail) > 2000:
                detail = detail[:2000] + "…"
            raise NativeBuildError(
                f"DeviceCheck helper compilation failed ({result.returncode}): {detail}"
            )
        try:
            os.chmod(tmp, 0o700)
            os.replace(tmp, target)
        except OSError as exc:
            raise NativeBuildError(
                f"could not install the DeviceCheck helper: {exc}"
            ) from exc
        logger.info("built native DeviceCheck helper at %s", target)
        return target


def _run_helper(helper: Path) -> DeviceCheckToken:
    try:
        result = subprocess.run(
            [str(helper)],
            capture_output=True,
            text=True,
            timeout=HELPER_TIMEOUT_S,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise DeviceCheckError("DeviceCheck helper timed out") from exc
    except OSError as exc:
        raise DeviceCheckError(
            f"could not run the DeviceCheck helper: {exc}"
        ) from exc

    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip()
        if len(detail) > 1000:
            detail = detail[:1000] + "…"
        raise DeviceCheckError(
            f"DeviceCheck helper exited {result.returncode}: {detail}"
        )
    line = result.stdout.strip().splitlines()
    if not line:
        raise DeviceCheckError("DeviceCheck helper produced no output")
    try:
        payload = json.loads(line[-1])
    except ValueError as exc:
        raise DeviceCheckError(
            "DeviceCheck helper produced invalid JSON"
        ) from exc
    if not isinstance(payload, dict):
        raise DeviceCheckError("DeviceCheck helper produced an unexpected payload")

    supported = bool(payload.get("supported"))
    token = payload.get("token_base64")
    token = token if isinstance(token, str) and token else None
    error = payload.get("error")
    error = error if isinstance(error, str) and error else None
    latency = payload.get("latency_ms")
    latency = float(latency) if isinstance(latency, (int, float)) else None
    return DeviceCheckToken(
        supported=supported,
        token_base64=token,
        error=error,
        latency_ms=latency,
    )


def _platform_supported() -> bool:
    return sys.platform == "darwin" and platform.machine() == "arm64"


def devicecheck_token() -> DeviceCheckToken:
    """Generate a DeviceCheck token, compiling the helper when necessary.

    On non-macOS/arm64 hosts this reports unsupported without invoking native
    code, mirroring the Codex desktop client.
    """
    if not _platform_supported():
        return DeviceCheckToken(
            supported=False,
            error="DeviceCheck is only available on macOS arm64",
        )
    helper = _compile_helper()
    return _run_helper(helper)


async def devicecheck_token_async() -> DeviceCheckToken:
    return await asyncio.to_thread(devicecheck_token)


# -- CBOR envelope -----------------------------------------------------


def _cbor_head(major: int, value: int) -> bytes:
    if value < 0:
        raise ValueError(f"negative CBOR length: {value}")
    if value < 24:
        return bytes([(major << 5) | value])
    if value <= 0xFF:
        return bytes([(major << 5) | 24, value])
    if value <= 0xFFFF:
        return bytes([(major << 5) | 25]) + struct.pack(">H", value)
    if value <= 0xFFFFFFFF:
        return bytes([(major << 5) | 26]) + struct.pack(">I", value)
    return bytes([(major << 5) | 27]) + struct.pack(">Q", value)


def _cbor_uint(value: int) -> bytes:
    return _cbor_head(0, value)


def _cbor_text(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return _cbor_head(3, len(encoded)) + encoded


def _cbor_bytes(value: bytes) -> bytes:
    return _cbor_head(2, len(value)) + value


def _cbor_array(values: list[bytes]) -> bytes:
    return _cbor_head(4, len(values)) + b"".join(values)


def _cbor_map(entries: list[tuple[bytes, bytes]]) -> bytes:
    return _cbor_head(5, len(entries)) + b"".join(
        key + value for key, value in entries
    )


def _cbor_float64(value: float) -> bytes:
    return b"\xfb" + struct.pack(">d", value)


def _client_locale() -> str:
    candidate = ""
    for name in ("LC_ALL", "LC_CTYPE", "LANG"):
        raw = os.environ.get(name, "")
        if raw:
            candidate = raw
            break
    if not candidate:
        try:
            candidate = _locale.getlocale(_locale.LC_CTYPE)[0] or ""
        except (ValueError, TypeError):
            candidate = ""
    candidate = candidate.split(".", 1)[0].split("@", 1)[0].strip()
    normalized = candidate.replace("_", "-") or "unknown"
    return normalized[:64]


def _client_timezone() -> str:
    configured = os.environ.get("TZ", "").strip().lstrip(":")
    if configured:
        return configured[:64]
    try:
        target = Path("/etc/localtime").resolve()
        parts = target.parts
        if "zoneinfo" in parts:
            index = parts.index("zoneinfo")
            name = "/".join(parts[index + 1 :])
            if name:
                return name[:64]
    except OSError:
        pass
    try:
        name = datetime.now().astimezone().tzinfo
        text = getattr(name, "key", None) or str(name or "")
        if "/" in text and text not in ("UTC",):
            return text[:64]
    except (ValueError, TypeError):
        pass
    return "unknown"


def _attestation_signals() -> bytes:
    locale = _client_locale()
    timezone = _client_timezone()
    return _cbor_map(
        [
            (_cbor_uint(0), _cbor_uint(1)),
            (_cbor_uint(1), _cbor_array([_cbor_text(locale)])),
            (_cbor_uint(2), _cbor_text(locale)),
            (_cbor_uint(3), _cbor_text(timezone)),
            (_cbor_uint(4), _cbor_uint(0)),
            (_cbor_uint(5), _cbor_uint(1)),
            (_cbor_uint(6), _cbor_text(APP_SESSION_ID[:128])),
        ]
    )


def build_attestation(result: DeviceCheckToken) -> str:
    """Encode one helper result as the ``x-oai-attestation`` envelope."""
    entries: list[tuple[bytes, bytes]] = []
    if result.supported and result.token_base64:
        entries.append((_cbor_text("token"), _cbor_text(result.token_base64)))
    else:
        entries.append(
            (
                _cbor_text("error_code"),
                _cbor_uint(4 if result.supported else 3),
            )
        )
    entries.append((_cbor_text("bundle_id"), _cbor_text(CHATGPT_BUNDLE_ID)))
    signals = _attestation_signals()
    entries.append((_cbor_text("f"), _cbor_bytes(signals)))
    if result.latency_ms is not None:
        entries.append((_cbor_text("t"), _cbor_float64(result.latency_ms)))
    payload = _cbor_map(entries)
    encoded = base64.urlsafe_b64encode(payload).rstrip(b"=").decode("ascii")
    return json.dumps(
        {"v": 1, "s": 0, "t": f"v1.{encoded}"},
        separators=(",", ":"),
    )


def codex_attestation() -> str | None:
    """Return the attestation header value, or ``None`` when unavailable.

    The Codex client treats attestation as optional: failures drop the header
    rather than failing the call.  The reason is logged at debug level (never
    including token material).
    """
    if not _platform_supported():
        return None
    try:
        result = devicecheck_token()
    except DeviceCheckError as exc:
        logger.debug("DeviceCheck attestation unavailable: %s", exc)
        return None
    if not result.supported or not result.token_base64:
        logger.debug(
            "DeviceCheck attestation unavailable: %s",
            result.error or "no token",
        )
        return None
    return build_attestation(result)


async def codex_attestation_async() -> str | None:
    return await asyncio.to_thread(codex_attestation)


__all__ = [
    "APP_SESSION_ID",
    "CHATGPT_BUNDLE_ID",
    "DeviceCheckError",
    "DeviceCheckToken",
    "NativeBuildError",
    "build_attestation",
    "codex_attestation",
    "codex_attestation_async",
    "devicecheck_token",
    "devicecheck_token_async",
]
