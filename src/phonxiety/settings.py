"""Daemon configuration and per-installation connection credentials."""

from __future__ import annotations

import json
import os
import secrets
import tempfile
from dataclasses import dataclass
from pathlib import Path

from phonxiety.codec import CODECS, DEFAULT_OPUS_BITRATE, MAX_OPUS_BITRATE, MIN_OPUS_BITRATE


def default_state_dir() -> Path:
    configured = os.environ.get("PHONXIETY_STATE_DIR")
    return Path(configured).expanduser() if configured else Path.home() / ".local/share/phonxiety"


def _tokens(state_dir: Path) -> dict[str, str]:
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = state_dir / "tokens.json"
    try:
        data = json.loads(path.read_text())
    except FileNotFoundError:
        data = {"host_token": secrets.token_urlsafe(32), "device_token": secrets.token_urlsafe(32)}
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
    if not isinstance(data, dict) or not all(isinstance(data.get(key), str) and data[key] for key in ("host_token", "device_token")):
        raise ValueError(f"Invalid connection credential file: {path}")
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


def _voice_backend() -> str:
    backend = os.environ.get("PHONXIETY_VOICE_BACKEND", "codex").strip().lower()
    if backend not in VOICE_BACKENDS:
        raise ValueError(f"PHONXIETY_VOICE_BACKEND must be one of: {', '.join(VOICE_BACKENDS)}")
    return backend


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
    host: str = "127.0.0.1"
    port: int = 18762
    device_host: str = "0.0.0.0"
    device_port: int = 18763
    codex_auth_path: Path | None = None
    codex_task_model: str | None = None
    codex_voice: str = "sol"
    audio_codec: str = "opus"
    opus_bitrate: int = DEFAULT_OPUS_BITRATE
    iroh_relays: tuple[str, ...] = ()
    voice_backend: str = "codex"

    @classmethod
    def from_env(cls) -> Settings:
        state_dir = default_state_dir()
        tokens = _tokens(state_dir)
        port = _port("PHONXIETY_PORT", 18762)
        device_port = _port("PHONXIETY_DEVICE_PORT", 18763)
        if device_port == port:
            raise ValueError("PHONXIETY_DEVICE_PORT must differ from PHONXIETY_PORT")
        codex_auth_path = os.environ.get("PHONXIETY_CODEX_AUTH_FILE")
        return cls(
            state_dir=state_dir,
            device_token=os.environ.get("PHONXIETY_DEVICE_TOKEN") or tokens["device_token"],
            host_token=os.environ.get("PHONXIETY_HOST_TOKEN") or tokens["host_token"],
            port=port,
            device_host=os.environ.get("PHONXIETY_DEVICE_HOST") or "0.0.0.0",
            device_port=device_port,
            codex_auth_path=Path(codex_auth_path).expanduser() if codex_auth_path else None,
            codex_task_model=os.environ.get("PHONXIETY_CODEX_TASK_MODEL") or None,
            codex_voice=os.environ.get("PHONXIETY_CODEX_VOICE", "sol"),
            audio_codec=_codec(),
            opus_bitrate=_opus_bitrate(),
            iroh_relays=_iroh_relays(state_dir),
            voice_backend=_voice_backend(),
        )

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"
