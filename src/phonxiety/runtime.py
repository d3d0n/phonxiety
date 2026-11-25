"""Construct one independently owned daemon runtime."""

from __future__ import annotations

import fcntl
import os

from .controller import CallController
from .device import DeviceHub
from .power import hold_awake
from .settings import Settings
from .store import CallStore


def create_controller(settings: Settings) -> CallController:
    from .auth import CodexAuth
    from .task_agent import TaskAgent
    from .voice import CodexVoiceSession

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
        auth = CodexAuth(settings.auth_path)
        task_agent = TaskAgent(auth, settings.task_model)
        store = CallStore(settings.state_dir / "calls.sqlite3")
        device = DeviceHub(audio_codec=settings.audio_codec, opus_bitrate=settings.opus_bitrate)
        return CallController(
            store,
            device,
            lambda instructions, brief, callbacks: CodexVoiceSession(
                instructions, callbacks, auth=auth, voice=settings.voice, initial_context=brief,
            ),
            task_agent,
            singleton_file=lock,
            keep_awake=hold_awake,
            archive_dir=settings.state_dir / "calls",
        )
    except BaseException:
        lock.close()
        raise
