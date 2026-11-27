"""The host pairing task under a fake adb: what it stops, restores and refuses.

The real phone is never contacted.  A fake ``adb`` and a fake ``uv`` live in a temporary
directory that is the only place either tool may be resolved from, so a mistake in this harness
fails the test instead of reaching a device.  The fake ``adb`` records every call and answers from
a tiny state file, so the script's own sequencing — preflight root, force-stop the whole app,
confirm it is gone, replace the file, verify the hash, restart — is what each test observes.

The harness is deliberately fail-closed:

* the subprocess environment is built explicitly, never inherited wholesale; ``ADB`` points at
  the fake because the script honours ``${ADB:-adb}``, and an inherited value would point at the
  real tool;
* ``PATH`` is the fake directory followed only by original entries that hold neither ``adb`` nor
  ``uv``;
* the fakes' shebang is this interpreter (``sys.executable``), so they never depend on that PATH;
* before every run ``shutil.which`` must resolve both tools to the fakes, and every remaining PATH
  entry is checked for a shadowing executable; anything else fails the test without running the
  script;
* ``ANDROID_SERIAL`` is a name no real device can have, as defence in depth.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "mise-tasks" / "phone" / "pair"
PACKAGE = "com.phonxiety.bridge"

CONFIG = {
    "url": "wss://example.invalid:18763/device",
    "token": "device-token",
    "cert_sha256": "ab" * 32,
}
CONFIG_TEXT = json.dumps(CONFIG)
CONFIG_HASH = hashlib.sha256((CONFIG_TEXT + "\n").encode()).hexdigest()

FAKE_ADB = """#!{python}
import json, os, sys

state_dir = os.environ["FAKE_STATE_DIR"]
state_path = os.path.join(state_dir, "state.json")
with open(state_path) as handle:
    state = json.load(handle)

log_path = os.environ.get("FAKE_ADB_LOG")
if log_path:
    with open(log_path, "a") as handle:
        handle.write(" ".join(sys.argv[1:]) + "\\n")

def save():
    with open(state_path, "w") as handle:
        json.dump(state, handle)

def flag(name):
    return os.environ.get(name) == "1"

args = sys.argv[1:]
if args == ["get-state"]:
    print("device")
    sys.exit(0)

if not args or args[0] != "shell":
    sys.exit(2)

command = " ".join(args[1:])

if command.startswith("pm path"):
    print("package:/data/app/base/base.apk")
elif command.startswith("dumpsys package"):
    for perm in (
        "CAPTURE_AUDIO_OUTPUT",
        "MODIFY_PHONE_STATE",
        "CONTROL_INCALL_EXPERIENCE",
        "RECORD_AUDIO",
        "CALL_PHONE",
        "READ_PHONE_STATE",
        "ACCESS_LOCAL_NETWORK",
    ):
        print(f"{{perm}}: granted=true")
elif command.startswith("pm grant"):
    pass
elif command.startswith("getprop ro.build.version.sdk"):
    print("35")
elif command.startswith("su -c id"):
    sys.exit(1 if flag("FAKE_ROOT_DENIED") else 0)
elif command.startswith("dumpsys activity services"):
    if state.get("service"):
        print("ServiceRecord{{42: com.phonxiety.bridge/.BridgeService}}")
elif command.startswith("am force-stop"):
    if not flag("FAKE_PROCESS_REFUSES_STOP"):
        state["process"] = False
        state["service"] = False
        save()
elif command.startswith("pidof"):
    if flag("FAKE_PIDOF_ERROR"):
        sys.exit(1)
    if state.get("process"):
        print("1234")
        print("PX_STATUS=0")
    else:
        print("PX_STATUS=1")
elif command.startswith("am start-foreground-service"):
    state["process"] = True
    state["service"] = True
    save()
elif command.startswith("su -c 'sh -s'"):
    sys.stdin.read()
    outcome = os.environ.get("FAKE_PAIR_RESULT", "ok")
    if outcome == "ok":
        print("PX_REPLACED=1")
        print(f"{{os.environ['FAKE_ADB_HASH']}}  /data/user_de/0/com.phonxiety.bridge/files/config.json")
        sys.exit(0)
    if outcome == "hash_fail":
        print("PX_REPLACED=1")
        print("0" * 64 + "  /data/user_de/0/com.phonxiety.bridge/files/config.json")
        sys.exit(0)
    if outcome == "replaced_unverified":
        print("PX_REPLACED=1")
        print("final file label mismatch", file=sys.stderr)
        sys.exit(1)
    if outcome == "labeling_fail":
        print("cannot label the temporary file", file=sys.stderr)
        sys.exit(1)
    # Any other failure happens before the rename: no marker.
    print("temporary file label mismatch", file=sys.stderr)
    sys.exit(1)
else:
    sys.exit(2)

sys.exit(0)
""".format(python=sys.executable)

FAKE_UV = """#!{python}
import sys

args = sys.argv[1:]
if "pair" in args:
    print({config!r})
elif "status" in args:
    if "--json" in args:
        print('{{"connected": true}}')
    else:
        print("connected")
""".format(python=sys.executable, config=CONFIG_TEXT)


class Fakes:
    """The fake tool directory and the explicit environment the script runs in."""

    def __init__(self, base: Path) -> None:
        self.base = base
        self.bin = base / "bin"
        self.state = base / "state"
        self.tmp = base / "tmp"
        self.log = base / "adb.log"
        self.bin.mkdir()
        self.state.mkdir()
        self.tmp.mkdir()
        self.log.write_text("")
        self.write_tool("adb", FAKE_ADB)
        self.write_tool("uv", FAKE_UV)
        (self.state / "state.json").write_text(json.dumps({"process": True, "service": True}))

    def write_tool(self, name: str, source: str) -> None:
        path = self.bin / name
        path.write_text(source)
        path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    def path_entries(self) -> list[str]:
        """The fake directory first, then only original entries that hold no real adb or uv."""
        safe: list[str] = []
        for entry in os.environ.get("PATH", "").split(os.pathsep):
            if not entry:
                continue
            if any(os.access(os.path.join(entry, tool), os.X_OK) for tool in ("adb", "uv")):
                continue
            safe.append(entry)
        return [str(self.bin), *safe]

    def environment(self) -> dict[str, str]:
        # Built from nothing: an inherited ADB, PATH or ANDROID_SERIAL must never reach the script.
        return {
            "PATH": os.pathsep.join(self.path_entries()),
            "ADB": str(self.bin / "adb"),
            "MISE_PROJECT_ROOT": str(ROOT),
            "TMPDIR": str(self.tmp),
            "ANDROID_SERIAL": "phonxiety-test-no-device",
            "LANG": "C",
            "FAKE_STATE_DIR": str(self.state),
            "FAKE_ADB_LOG": str(self.log),
            "FAKE_ADB_HASH": CONFIG_HASH,
        }

    def refuse_if_unsafe(self, case: unittest.TestCase) -> dict[str, str]:
        """Fails the test — never skips — unless every tool resolves to a fake."""
        environment = self.environment()
        expected_adb = str(self.bin / "adb")
        expected_uv = str(self.bin / "uv")
        if environment.get("ADB") != expected_adb:
            case.fail(f"ADB must point at the fake, not {environment.get('ADB')!r}")
        resolved_adb = shutil.which("adb", path=environment["PATH"])
        resolved_uv = shutil.which("uv", path=environment["PATH"])
        if resolved_adb != expected_adb:
            case.fail(f"adb resolves to {resolved_adb!r}, not the fake; refusing to run")
        if resolved_uv != expected_uv:
            case.fail(f"uv resolves to {resolved_uv!r}, not the fake; refusing to run")
        for entry in environment["PATH"].split(os.pathsep)[1:]:
            for tool in ("adb", "uv"):
                candidate = os.path.join(entry, tool)
                if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                    case.fail(f"a real {tool} is reachable at {candidate}; refusing to run")
        return environment

    def calls(self) -> list[str]:
        return self.log.read_text().splitlines()


class HostPairScriptTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.fakes = Fakes(Path(self.temporary.name))

    def run_script(self, **extra: str) -> subprocess.CompletedProcess[str]:
        environment = self.fakes.refuse_if_unsafe(self)
        environment.update(extra)
        return subprocess.run(
            ["bash", str(SCRIPT)],
            env=environment,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )

    def assert_not_started(self) -> None:
        self.assertFalse(
            any("start-foreground-service" in call for call in self.fakes.calls()),
            "the service must not be started here",
        )

    def assert_restored(self) -> None:
        self.assertTrue(
            any("am start-foreground-service" in call for call in self.fakes.calls()),
            "the previously running service must be restored",
        )

    def test_the_harness_refuses_to_run_without_the_fake_adb(self) -> None:
        (self.fakes.bin / "adb").unlink()

        with self.assertRaises(AssertionError):
            self.run_script()

        self.assertEqual([], self.fakes.calls(), "the script must never be invoked")

    def test_the_harness_refuses_to_run_without_the_fake_uv(self) -> None:
        (self.fakes.bin / "uv").unlink()

        with self.assertRaises(AssertionError):
            self.run_script()

        self.assertEqual([], self.fakes.calls(), "the script must never be invoked")

    def test_root_is_preflighted_before_anything_is_stopped(self) -> None:
        result = self.run_script(FAKE_ROOT_DENIED="1")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("root access is not available", result.stderr)
        self.assertFalse(any("force-stop" in call for call in self.fakes.calls()))
        self.assert_not_started()

    def test_an_unconfirmed_process_stop_aborts_and_restores_the_service(self) -> None:
        result = self.run_script(FAKE_PROCESS_REFUSES_STOP="1")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("still running", result.stderr)
        self.assert_restored()

    def test_an_adb_query_failure_is_not_read_as_an_empty_pidof(self) -> None:
        result = self.run_script(FAKE_PIDOF_ERROR="1")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("could not confirm the app process stopped", result.stderr)
        self.assert_restored()

    def test_a_failure_before_the_rename_restores_the_service(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="fail_before")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("writing the phone configuration failed", result.stderr)
        self.assert_restored()

    def test_a_labeling_failure_is_a_failure_before_the_rename(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="labeling_fail")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("cannot label the temporary file", result.stderr)
        self.assertIn("writing the phone configuration failed", result.stderr)
        self.assert_restored()

    def test_an_unverified_replacement_is_reported_and_never_restarted(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="replaced_unverified")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("could not be verified", result.stderr)
        self.assert_not_started()

    def test_a_hash_mismatch_after_the_rename_never_restarts(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="hash_fail")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("hash mismatch after replacement", result.stderr)
        self.assert_not_started()

    def test_a_verified_replacement_stops_the_app_and_starts_the_service(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="ok")

        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.fakes.calls()
        self.assertTrue(any("am force-stop" in call for call in calls))
        self.assertTrue(any("pidof" in call for call in calls))
        self.assertTrue(any("am start-foreground-service" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
