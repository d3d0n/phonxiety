"""Daemon configuration and per-installation connection credentials."""

from __future__ import annotations

import json
import os
import re
import secrets
import tempfile
from dataclasses import dataclass
from pathlib import Path

from phonxiety.audio.opus import CODECS, DEFAULT_OPUS_BITRATE, MAX_OPUS_BITRATE, MIN_OPUS_BITRATE


def default_state_dir() -> Path:
    configured = os.environ.get("PHONXIETY_STATE_DIR")
    return Path(configured).expanduser() if configured else Path.home() / ".local/share/phonxiety"


TOKEN_KEYS = ("host_token", "device_token", "app_token")


def _write_tokens(state_dir: Path, data: dict[str, str]) -> None:
    """Replace the credential file atomically with owner-only access."""
    fd, temporary = tempfile.mkstemp(prefix=".tokens-", dir=state_dir)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(data, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, state_dir / "tokens.json")
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise


def _tokens(state_dir: Path) -> dict[str, str]:
    """Per-installation credentials, generating and backfilling them as needed.

    `app_token` is the phone app's own credential, narrower than `device_token`: it
    was added after the first installs, so an existing file without it gains one.
    """
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = state_dir / "tokens.json"
    try:
        data = json.loads(path.read_text())
        if not isinstance(data, dict):
            raise ValueError(f"Invalid connection credential file: {path}")
    except FileNotFoundError:
        data = {key: secrets.token_urlsafe(32) for key in TOKEN_KEYS}
        fd, temporary = tempfile.mkstemp(prefix=".tokens-", dir=state_dir)
        try:
            with os.fdopen(fd, "w") as stream:
                json.dump(data, stream)
                stream.flush()
                os.fsync(stream.fileno())
            try:
                os.link(temporary, path)
            except FileExistsError:
                data = json.loads(path.read_text())
        finally:
            os.unlink(temporary)
    if not all(isinstance(data.get(key), str) and data[key] for key in ("host_token", "device_token")):
        raise ValueError(f"Invalid connection credential file: {path}")
    if not isinstance(data.get("app_token"), str) or not data["app_token"]:
        data["app_token"] = secrets.token_urlsafe(32)
        _write_tokens(state_dir, data)
    return data


def _port(variable: str, default: int) -> int:
    port = int(os.environ.get(variable, default))
    if not 1 <= port <= 65535:
        raise ValueError(f"{variable} must be between 1 and 65535")
    return port


def _codec() -> str:
    codec = os.environ.get("PHONXIETY_AUDIO_CODEC", "opus").strip().lower()
    if codec not in CODECS:
        raise ValueError(f"PHONXIETY_AUDIO_CODEC must be one of: {', '.join(CODECS)}")
    return codec


def _opus_bitrate() -> int:
    bitrate = int(os.environ.get("PHONXIETY_OPUS_BITRATE", DEFAULT_OPUS_BITRATE))
    if not MIN_OPUS_BITRATE <= bitrate <= MAX_OPUS_BITRATE:
        raise ValueError(f"PHONXIETY_OPUS_BITRATE must be between {MIN_OPUS_BITRATE} and {MAX_OPUS_BITRATE}")
    return bitrate


def _iroh_relays(state_dir: Path) -> tuple[str, ...]:
    """PHONXIETY_IROH_RELAYS (comma-separated), else `<state_dir>/iroh-relays`, one URL per line."""
    configured = os.environ.get("PHONXIETY_IROH_RELAYS")
    if configured is not None:
        entries = configured.split(",")
    else:
        try:
            entries = (state_dir / "iroh-relays").read_text().splitlines()
        except FileNotFoundError:
            return ()
    return tuple(url for url in (entry.split("#", 1)[0].strip() for entry in entries) if url)


VOICE_BACKENDS = ("codex", "gemini", "deepgram")

SETTINGS_FILE = "settings.json"
SETTINGS_SCHEMA_VERSION = 1
# Fields the phone may change. Each is read once when the daemon constructs its parts,
# so a change applies on the next start and the API answers `restart_required`.
RESTART_SETTINGS = (
    "audio_codec",
    "opus_bitrate",
    "voice_backend",
    "codex_voice",
    "gemini_voice",
    "codex_task_model",
    "gemini_model",
    "language_hint",
    "iroh_relays",
)
# Call defaults are read from the settings values on every request, so a phone-side
# change applies to the next call without a restart.
CALL_SETTINGS = (
    "ring_timeout_s",
    "max_duration_s",
    "default_result_fields",
    "default_constraints",
)

RING_TIMEOUT_RANGE = (5, 180)
MAX_DURATION_RANGE = (10, 3600)
MAX_RESULT_FIELDS = 16
MAX_RESULT_FIELD_CHARS = 64
MAX_CONSTRAINTS_CHARS = 2000
_MODEL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,63}$")
_VOICE = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
_LANGUAGE = re.compile(r"^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")


def _int(value: object, name: str, bounds: tuple[int, int]) -> int:
    try:
        number = int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        raise ValueError(f"{name} must be an integer") from None
    low, high = bounds
    if not low <= number <= high:
        raise ValueError(f"{name} must be between {low} and {high}")
    return number


def _model(value: object, name: str) -> str:
    model = str(value).strip()
    if model and not _MODEL.fullmatch(model):
        raise ValueError(f"{name} must be 1..64 letters, digits or '._:/+-'")
    return model


def _voice(value: object, name: str) -> str:
    voice = str(value).strip()
    if not _VOICE.fullmatch(voice):
        raise ValueError(f"{name} must be 1..32 letters, digits, '_' or '-'")
    return voice


def _result_fields(value: object) -> list[str]:
    entries: object = value.split(",") if isinstance(value, str) else value
    if not isinstance(entries, (list, tuple)):
        raise ValueError("default_result_fields must be a list of field names")
    fields: list[str] = []
    for entry in entries:
        field = str(entry).strip()
        if not field:
            continue
        if len(field) > MAX_RESULT_FIELD_CHARS:
            raise ValueError(f"default_result_fields entries must be at most {MAX_RESULT_FIELD_CHARS} characters")
        fields.append(field)
    if len(fields) > MAX_RESULT_FIELDS:
        raise ValueError(f"at most {MAX_RESULT_FIELDS} default result fields")
    return fields


def _voice_backend(name: str | None = None) -> str:
    backend = (name if name is not None else os.environ.get("PHONXIETY_VOICE_BACKEND", "codex")).strip().lower()
    if backend not in VOICE_BACKENDS:
        raise ValueError(f"PHONXIETY_VOICE_BACKEND must be one of: {', '.join(VOICE_BACKENDS)}")
    return backend


def validate_runtime(values: dict[str, object]) -> dict[str, object]:
    """Coerce and check the fields the phone may change; raise ValueError on a bad value."""
    if not isinstance(values, dict):
        raise ValueError("settings must be an object")
    clean: dict[str, object] = {}
    for key, value in values.items():
        if key == "audio_codec":
            codec = str(value).strip().lower()
            if codec not in CODECS:
                raise ValueError(f"audio_codec must be one of: {', '.join(CODECS)}")
            clean["audio_codec"] = codec
        elif key == "opus_bitrate":
            try:
                bitrate = int(value)  # type: ignore[arg-type]
            except (TypeError, ValueError):
                raise ValueError("opus_bitrate must be an integer") from None
            if not MIN_OPUS_BITRATE <= bitrate <= MAX_OPUS_BITRATE:
                raise ValueError(f"opus_bitrate must be between {MIN_OPUS_BITRATE} and {MAX_OPUS_BITRATE}")
            clean["opus_bitrate"] = bitrate
        elif key == "voice_backend":
            clean["voice_backend"] = _voice_backend(str(value))
        elif key == "codex_voice":
            clean["codex_voice"] = _voice(value, "codex_voice")
        elif key == "gemini_voice":
            clean["gemini_voice"] = _voice(value, "gemini_voice")
        elif key == "codex_task_model":
            clean["codex_task_model"] = _model(value, "codex_task_model")
        elif key == "gemini_model":
            clean["gemini_model"] = _model(value, "gemini_model")
        elif key == "language_hint":
            hint = str(value).strip()
            if hint and (len(hint) > 35 or not _LANGUAGE.fullmatch(hint)):
                raise ValueError("language_hint must be a language tag such as 'en' or 'ru-RU', or empty")
            clean["language_hint"] = hint
        elif key == "ring_timeout_s":
            clean["ring_timeout_s"] = _int(value, "ring_timeout_s", RING_TIMEOUT_RANGE)
        elif key == "max_duration_s":
            clean["max_duration_s"] = _int(value, "max_duration_s", MAX_DURATION_RANGE)
        elif key == "default_result_fields":
            clean["default_result_fields"] = _result_fields(value)
        elif key == "default_constraints":
            constraints = str(value).strip()
            if len(constraints) > MAX_CONSTRAINTS_CHARS:
                raise ValueError(f"default_constraints must be at most {MAX_CONSTRAINTS_CHARS} characters")
            clean["default_constraints"] = constraints
        elif key == "iroh_relays":
            if isinstance(value, str):
                entries: object = value.split(",")
            else:
                entries = value
            if not isinstance(entries, (list, tuple)):
                raise ValueError("iroh_relays must be a list of relay URLs")
            urls: list[str] = []
            for entry in entries:
                url = str(entry).split("#", 1)[0].strip()
                if not url:
                    continue
                if "://" not in url or len(url) > 512:
                    raise ValueError("iroh_relays entries must be relay URLs up to 512 characters")
                urls.append(url)
            if len(urls) > 8:
                raise ValueError("at most 8 iroh relays")
            clean["iroh_relays"] = urls
        else:
            raise ValueError(f"unknown setting: {key}")
    return clean


def runtime_overrides(state_dir: Path) -> dict[str, object]:
    """Values the phone wrote to `<state_dir>/settings.json`; a bad entry is ignored.

    The file is read after the environment, so a value saved from the app survives the
    daemon's next start even when the environment sets a default.
    """
    try:
        raw = json.loads((state_dir / SETTINGS_FILE).read_text())
    except (FileNotFoundError, ValueError, OSError):
        return {}
    if not isinstance(raw, dict):
        return {}
    stored = raw.get("values")
    if not isinstance(stored, dict):
        return {}
    clean: dict[str, object] = {}
    for key, value in stored.items():
        try:
            clean.update(validate_runtime({key: value}))
        except (ValueError, TypeError):
            continue
    return clean


def write_runtime_settings(state_dir: Path, values: dict[str, object]) -> dict[str, object]:
    """Merge validated values into the settings file and return every stored value."""
    clean = dict(runtime_overrides(state_dir))
    clean.update(validate_runtime(values))
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix=".settings-", dir=state_dir)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump({"schema": SETTINGS_SCHEMA_VERSION, "values": clean}, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, state_dir / SETTINGS_FILE)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise
    return clean


def secret(state_dir: Path, variable: str, filename: str) -> str:
    """An API key from the environment, else from `<state_dir>/<filename>`; never logged."""
    value = os.environ.get(variable, "").strip()
    if not value:
        try:
            value = (state_dir / filename).read_text().strip()
        except FileNotFoundError:
            value = ""
    if not value:
        raise ValueError(f"set {variable} or put the key in {state_dir / filename}")
    return value


@dataclass(frozen=True, slots=True)
class Settings:
    state_dir: Path
    device_token: str
    host_token: str
    app_token: str = ""
    host: str = "127.0.0.1"
    port: int = 18762
    device_host: str = "0.0.0.0"
    device_port: int = 18763
    iroh_port: int = 18764  # fixed IPv4 UDP port for the iroh endpoint; a random one if it is taken
    codex_auth_path: Path | None = None
    codex_task_model: str | None = None
    codex_voice: str = "sol"
    gemini_model: str | None = None
    gemini_voice: str | None = None
    audio_codec: str = "opus"
    opus_bitrate: int = DEFAULT_OPUS_BITRATE
    iroh_relays: tuple[str, ...] = ()
    voice_backend: str = "codex"
    ring_timeout_s: int = 60
    max_duration_s: int = 900
    default_result_fields: tuple[str, ...] = ()
    default_constraints: str = ""
    language_hint: str = ""
    adb_port: int = 15555  # local port that reaches the phone's adbd through the iroh link

    @classmethod
    def from_env(cls) -> Settings:
        state_dir = default_state_dir()
        tokens = _tokens(state_dir)
        port = _port("PHONXIETY_PORT", 18762)
        device_port = _port("PHONXIETY_DEVICE_PORT", 18763)
        if device_port == port:
            raise ValueError("PHONXIETY_DEVICE_PORT must differ from PHONXIETY_PORT")
        codex_auth_path = os.environ.get("PHONXIETY_CODEX_AUTH_FILE")
        # Saved from the app after the environment: a value the user set on the phone wins.
        saved = runtime_overrides(state_dir)
        return cls(
            state_dir=state_dir,
            device_token=os.environ.get("PHONXIETY_DEVICE_TOKEN") or tokens["device_token"],
            host_token=os.environ.get("PHONXIETY_HOST_TOKEN") or tokens["host_token"],
            app_token=os.environ.get("PHONXIETY_APP_TOKEN") or tokens["app_token"],
            port=port,
            device_host=os.environ.get("PHONXIETY_DEVICE_HOST") or "0.0.0.0",
            device_port=device_port,
            iroh_port=_port("PHONXIETY_IROH_PORT", 18764),
            codex_auth_path=Path(codex_auth_path).expanduser() if codex_auth_path else None,
            codex_task_model=str(saved["codex_task_model"]) if saved.get("codex_task_model") else (os.environ.get("PHONXIETY_CODEX_TASK_MODEL") or None),
            codex_voice=str(saved.get("codex_voice") or os.environ.get("PHONXIETY_CODEX_VOICE", "sol")),
            gemini_model=str(saved["gemini_model"]) if saved.get("gemini_model") else (os.environ.get("PHONXIETY_GEMINI_MODEL") or None),
            gemini_voice=str(saved["gemini_voice"]) if saved.get("gemini_voice") else (os.environ.get("PHONXIETY_GEMINI_VOICE") or None),
            audio_codec=str(saved.get("audio_codec") or _codec()),
            opus_bitrate=int(saved.get("opus_bitrate") or _opus_bitrate()),  # type: ignore[arg-type]
            iroh_relays=tuple(saved["iroh_relays"]) if isinstance(saved.get("iroh_relays"), list) else _iroh_relays(state_dir),
            voice_backend=_voice_backend(str(saved["voice_backend"]) if saved.get("voice_backend") else None),
            ring_timeout_s=int(saved.get("ring_timeout_s") or 60),
            max_duration_s=int(saved.get("max_duration_s") or 900),
            default_result_fields=tuple(str(field) for field in saved["default_result_fields"]) if isinstance(saved.get("default_result_fields"), list) else (),
            default_constraints=str(saved.get("default_constraints") or ""),
            language_hint=str(saved.get("language_hint") or ""),
            adb_port=_port("PHONXIETY_ADB_PORT", 15555),
        )

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"
