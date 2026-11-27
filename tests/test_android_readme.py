"""The Android module map documents exactly the modules Gradle builds."""

from __future__ import annotations

import re
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1] / "android"


def test_the_module_map_lists_every_gradle_module_and_no_other() -> None:
    settings = (ANDROID / "settings.gradle.kts").read_text()
    built = set(re.findall(r'^include\("(:[a-z:]+)"\)', settings, re.MULTILINE))
    readme = (ANDROID / "README.md").read_text()
    mapped = set(re.findall(r"^\| `(:[a-z:]+)` \|", readme, re.MULTILINE))

    assert built, "settings.gradle.kts includes no modules"
    assert mapped == built
