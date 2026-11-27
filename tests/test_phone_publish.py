"""`phone:publish` publishes only release notes that name the version it publishes."""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "mise-tasks" / "phone" / "publish"


def run_publish(root: Path, heading: str | None, *args: str) -> subprocess.CompletedProcess[str]:
    """Runs the script against a project root holding only module.prop 9.9.9, the notes, and an
    empty release-output directory, so the check after the notes is the missing APK."""
    magisk = root / "android" / "magisk"
    magisk.mkdir(parents=True)
    (magisk / "module.prop").write_text("id=phonxiety_bridge\nversion=9.9.9\nversionCode=99\n")
    (root / "android" / "app" / "build" / "outputs" / "apk" / "release").mkdir(parents=True)
    if heading is not None:
        (root / "android" / "CHANGELOG.md").write_text(f"{heading}\n\n- A change.\n")
    env = {
        "PATH": os.environ["PATH"],
        "HOME": str(root),
        "TMPDIR": str(root),
        "MISE_PROJECT_ROOT": str(root),
    }
    return subprocess.run(
        ["bash", str(SCRIPT), "--no-build", *args],
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )


@pytest.mark.parametrize("heading", ["# Unreleased", "# Phonxiety bridge 0.4.1"])
def test_notes_that_do_not_name_the_version_are_refused(tmp_path: Path, heading: str) -> None:
    result = run_publish(tmp_path, heading)

    assert result.returncode != 0
    assert f"android/CHANGELOG.md starts with '{heading}'" in result.stderr
    assert not (tmp_path / "dist").exists()


def test_missing_notes_are_refused(tmp_path: Path) -> None:
    result = run_publish(tmp_path, None)

    assert result.returncode != 0
    assert "missing android/CHANGELOG.md" in result.stderr


def test_notes_that_name_the_version_reach_the_next_check(tmp_path: Path) -> None:
    result = run_publish(tmp_path, "# Phonxiety bridge 9.9.9")

    assert "CHANGELOG" not in result.stderr
    assert "release APK not found" in result.stderr


def test_a_changelog_hosted_elsewhere_leaves_the_local_notes_alone(tmp_path: Path) -> None:
    result = run_publish(tmp_path, "# Unreleased", "--changelog", "https://192.0.2.10/notes.md")

    assert "CHANGELOG" not in result.stderr
    assert "release APK not found" in result.stderr
