"""Construct one independently owned daemon runtime."""

from __future__ import annotations

import fcntl
import os
from collections.abc import Callable
from typing import Any

from .controller import CallController
from .device import DeviceHub
from .power import hold_awake
from .settings import Settings, secret
from .store import CallStore


def voice_factory(settings: Settings, auth: Any) -> Callable[[str, str, Any], Any]:
    """Build the configured voice backend; keys are checked now, not on the first call."""
    if settings.voice_backend == "deepgram":
        from .deepgram import DeepgramOptions, DeepgramVoiceSession

        env = os.environ.get
        options = DeepgramOptions(
            api_key=secret(settings.state_dir, "DEEPGRAM_API_KEY", "deepgram-api-key"),
            language=env("PHONXIETY_DEEPGRAM_LANGUAGE", "en"),
            listen_model=env("PHONXIETY_DEEPGRAM_LISTEN_MODEL", "nova-3"),
            think_model=env("PHONXIETY_DEEPGRAM_THINK_MODEL", "gpt-4o-mini"),
            speak_model=env("PHONXIETY_DEEPGRAM_SPEAK_MODEL", "aura-2-thalia-en"),
        )
        return lambda instructions, brief, callbacks: DeepgramVoiceSession(
            instructions, callbacks, options, initial_context=brief,
        )
    if settings.voice_backend == "gemini":
        from .gemini import DEFAULT_MODEL, DEFAULT_VOICE, GeminiOptions, GeminiVoiceSession

        gemini = GeminiOptions(
            api_key=secret(settings.state_dir, "GEMINI_API_KEY", "gemini-api-key"),
            model=os.environ.get("PHONXIETY_GEMINI_MODEL", DEFAULT_MODEL),
            voice=os.environ.get("PHONXIETY_GEMINI_VOICE", DEFAULT_VOICE),
        )
        return lambda instructions, brief, callbacks: GeminiVoiceSession(
            instructions, callbacks, gemini, initial_context=brief,
        )
    from .voice import CodexVoiceSession

    return lambda instructions, brief, callbacks: CodexVoiceSession(
        instructions, callbacks, auth=auth, voice=settings.codex_voice, initial_context=brief,
    )


def create_controller(settings: Settings) -> CallController:
    from .auth import CodexAuth
    from .task_agent import TaskAgent

    settings.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    lock = open(settings.state_dir / "daemon.lock", "a+")
    try:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as exc:
        lock.close()
        raise RuntimeError("A phonxiety daemon already owns this state directory") from exc
    lock.seek(0)
    lock.truncate()
    lock.write(str(os.getpid()))
    lock.flush()
    try:
        auth = CodexAuth(settings.codex_auth_path)
        task_agent = TaskAgent(auth, settings.codex_task_model)
        store = CallStore(settings.state_dir / "calls.sqlite3")
        device = DeviceHub(audio_codec=settings.audio_codec, opus_bitrate=settings.opus_bitrate)
        return CallController(
            store,
            device,
            voice_factory(settings, auth),
            task_agent,
            singleton_file=lock,
            keep_awake=hold_awake,
            archive_dir=settings.state_dir / "calls",
        )
    except BaseException:
        lock.close()
        raise
