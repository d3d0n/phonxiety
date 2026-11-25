"""Keep the computer awake while it carries a call.

An idle laptop goes to sleep in the middle of a long unattended call and takes
the voice session and the phone link with it.
"""

from __future__ import annotations

import contextlib
import os
import shutil
import subprocess
from collections.abc import Callable


def hold_awake() -> Callable[[], None] | None:
    """Prevent idle system sleep until the returned function is called; None where unsupported."""
    binary = shutil.which("caffeinate")
    if binary is None:
        return None
    # -w ties the assertion to this daemon, so a crash cannot leave the computer awake forever.
    process = subprocess.Popen(
        [binary, "-i", "-w", str(os.getpid())],
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )

    def release() -> None:
        process.terminate()
        with contextlib.suppress(subprocess.TimeoutExpired):
            process.wait(timeout=2)

    return release
