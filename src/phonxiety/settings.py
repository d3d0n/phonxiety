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


@dataclass(frozen=True, slots=True)
class Settings:
    state_dir: Path
    device_token: str
    host_token: str
    host: str = "127.0.0.1"
    port: int = 18762
    device_host: str = "0.0.0.0"
    device_port: int = 18763
    auth_path: Path | None = None
    task_model: str | None = None
    voice: str = "sol"
    audio_codec: str = "opus"
    opus_bitrate: int = DEFAULT_OPUS_BITRATE
    iroh_relays: tuple[str, ...] = ()

    @classmethod
    def from_env(cls) -> Settings:
        state_dir = default_state_dir()
        tokens = _tokens(state_dir)
        port = _port("PHONXIETY_PORT", 18762)
        device_port = _port("PHONXIETY_DEVICE_PORT", 18763)
        if device_port == port:
            raise ValueError("PHONXIETY_DEVICE_PORT must differ from PHONXIETY_PORT")
        auth_path = os.environ.get("PHONXIETY_AUTH_FILE")
        return cls(
            state_dir=state_dir,
            device_token=os.environ.get("PHONXIETY_DEVICE_TOKEN") or tokens["device_token"],
            host_token=os.environ.get("PHONXIETY_HOST_TOKEN") or tokens["host_token"],
            port=port,
            device_host=os.environ.get("PHONXIETY_DEVICE_HOST") or "0.0.0.0",
            device_port=device_port,
            auth_path=Path(auth_path).expanduser() if auth_path else None,
            task_model=os.environ.get("PHONXIETY_TASK_MODEL") or None,
            voice=os.environ.get("PHONXIETY_VOICE", "sol"),
            audio_codec=_codec(),
            opus_bitrate=_opus_bitrate(),
            iroh_relays=tuple(url.strip() for url in os.environ.get("PHONXIETY_IROH_RELAYS", "").split(",") if url.strip()),
        )

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"
