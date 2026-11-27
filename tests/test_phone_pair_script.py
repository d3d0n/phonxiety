"""The host pairing task under a fake adb: what it stops, restores and refuses.

The real phone is never contacted.  A fake ``adb`` and a fake ``uv`` live in a temporary
directory that is the only place either tool may be resolved from, so a mistake in this harness
fails the test instead of reaching a device.  The fake ``adb`` records every call and the exact
remote script it receives, and answers from a tiny state file, so the script's own sequencing —
preflight root, read the old configuration, force-stop the whole app, confirm it is gone,
replace the file, verify the hash, restart — is what each test observes.

The harness is deliberately fail-closed:

* the subprocess environment is built explicitly, never inherited wholesale; ``ADB`` points at
  the fake because the script honours ``${ADB:-adb}``, and an inherited value would point at the
  real tool;
* a test may override only the ``FAKE_*`` control variables; any other key (``ADB``,
  ``ANDROID_SERIAL``, ``PATH``, ``BASH_ENV``, ``ENV``, ...) fails the test;
* the final environment is checked immediately before the run: ``PATH`` starts at the fake
  directory, ``ADB`` is the fake, ``ANDROID_SERIAL`` is the sentinel, ``BASH_ENV`` and ``ENV``
  are absent, and ``shutil.which`` resolves both tools to the fakes;
* the fakes' shebang is this interpreter (``sys.executable``), so they never depend on that PATH;
* anything unsafe fails the test without running the script — never a skip.
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
SERIAL_SENTINEL = "phonxiety-test-no-device"

CONFIG = {
    "url": "wss://example.invalid:18763/device",
    "token": "device-token",
    "cert_sha256": "ab" * 32,
}
CONFIG_TEXT = json.dumps(CONFIG)
CONFIG_HASH = hashlib.sha256((CONFIG_TEXT + "\n").encode()).hexdigest()

OLD_CONFIG = {
    "url": "wss://old.invalid:18763/device",
    "token": "old-token",
    "cert_sha256": "cd" * 32,
}
OLD_CONFIG_TEXT = json.dumps(OLD_CONFIG)
OLD_HASH = hashlib.sha256((OLD_CONFIG_TEXT + "\n").encode()).hexdigest()

# The only environment keys a test may override.  Everything else is either fixed by the
# harness (ADB, PATH, ANDROID_SERIAL) or could make the runtime load something else.
FAKE_CONTROL_VARIABLES = frozenset(
    {
        "FAKE_ROOT_DENIED",
        "FAKE_PROCESS_REFUSES_STOP",
        "FAKE_PIDOF_ERROR",
        "FAKE_PIDOF_STATUS",
        "FAKE_PIDOF_EMPTY",
        "FAKE_HASH_QUERY_ERROR",
        "FAKE_PAIR_RESULT",
    }
)

FAKE_ADB = """#!{python}
import json, os, sys

state_dir = os.environ["FAKE_STATE_DIR"]
state_path = os.path.join(state_dir, "state.json")
with open(state_path) as handle:
    state = json.load(handle)

def save():
    with open(state_path, "w") as handle:
        json.dump(state, handle)

def flag(name):
    return os.environ.get(name) == "1"

def config_hash():
    kind = state.get("config", "absent")
    if kind == "absent":
        return "absent"
    if kind == "old":
        return os.environ["FAKE_OLD_HASH"]
    if kind == "other":
        return "f" * 64
    return os.environ["FAKE_ADB_HASH"]

log_path = os.environ.get("FAKE_ADB_LOG")
if log_path:
    with open(log_path, "a") as handle:
        handle.write(" ".join(sys.argv[1:]) + "\\n")

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
        print(perm + ": granted=true")
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
    status = os.environ.get("FAKE_PIDOF_STATUS")
    if status is not None:
        if status == "0" and not flag("FAKE_PIDOF_EMPTY") and state.get("process"):
            print("1234")
        print("PX_STATUS=" + status)
    elif state.get("process"):
        print("1234")
        print("PX_STATUS=0")
    else:
        print("PX_STATUS=1")
elif command.startswith("am start-foreground-service"):
    state["process"] = True
    state["service"] = True
    save()
elif "PX_ABSENT" in command:
    # The host's read of the current configuration, through root.
    if flag("FAKE_HASH_QUERY_ERROR"):
        sys.exit(1)
    current = config_hash()
    if current == "absent":
        print("PX_ABSENT")
    else:
        print(current + "  /data/user_de/0/com.phonxiety.bridge/files/config.json")
    print("PX_STATUS=0")
elif command.startswith("su -c 'sh -s'"):
    script = sys.stdin.read()
    remote_log = os.environ.get("FAKE_REMOTE_LOG")
    if remote_log:
        with open(remote_log, "a") as handle:
            handle.write(json.dumps(script) + "\\n")
    outcome = os.environ.get("FAKE_PAIR_RESULT", "ok")
    if outcome == "ok":
        state["config"] = "new"
        save()
        print("PX_BEFORE_MV")
        print("PX_REPLACED=1")
        print(os.environ["FAKE_ADB_HASH"] + "  /data/user_de/0/com.phonxiety.bridge/files/config.json")
        sys.exit(0)
    if outcome == "hash_fail":
        state["config"] = "new"
        save()
        print("PX_BEFORE_MV")
        print("PX_REPLACED=1")
        print("0" * 64 + "  /data/user_de/0/com.phonxiety.bridge/files/config.json")
        sys.exit(0)
    if outcome == "replaced_unverified":
        state["config"] = "new"
        save()
        print("PX_BEFORE_MV")
        print("PX_REPLACED=1")
        print("final file label mismatch", file=sys.stderr)
        sys.exit(1)
    if outcome == "missing_marker":
        # The rename happened, but the marker that follows it never arrived.
        state["config"] = "new"
        save()
        print("PX_BEFORE_MV")
        print(os.environ["FAKE_ADB_HASH"] + "  /data/user_de/0/com.phonxiety.bridge/files/config.json")
        sys.exit(0)
    if outcome == "failed_after_rename":
        # The rename happened, then the remote script failed before the marker.
        state["config"] = "new"
        save()
        print("PX_BEFORE_MV")
        print("final file label mismatch", file=sys.stderr)
        sys.exit(1)
    if outcome == "unknown_after_rename":
        # The phone cannot say which file it now holds.
        state["config"] = "other"
        save()
        print("PX_BEFORE_MV")
        sys.exit(1)
    if outcome == "labeling_fail":
        print("cannot label the temporary file", file=sys.stderr)
        sys.exit(1)
    # Any other failure happens before the rename: no marker, file untouched.
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
        self.remote_log = base / "remote.log"
        self.bin.mkdir()
        self.state.mkdir()
        self.tmp.mkdir()
        self.log.write_text("")
        self.remote_log.write_text("")
        self.write_tool("adb", FAKE_ADB)
        self.write_tool("uv", FAKE_UV)
        (self.state / "state.json").write_text(json.dumps({"process": True, "service": True, "config": "absent"}))

    def write_tool(self, name: str, source: str) -> None:
        path = self.bin / name
        path.write_text(source)
        path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    def set_state(self, **values: object) -> None:
        path = self.state / "state.json"
        state = json.loads(path.read_text())
        state.update(values)
        path.write_text(json.dumps(state))

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
            "ANDROID_SERIAL": SERIAL_SENTINEL,
            "LANG": "C",
            "FAKE_STATE_DIR": str(self.state),
            "FAKE_ADB_LOG": str(self.log),
            "FAKE_REMOTE_LOG": str(self.remote_log),
            "FAKE_ADB_HASH": CONFIG_HASH,
            "FAKE_OLD_HASH": OLD_HASH,
        }

    def checked_environment(self, case: unittest.TestCase, overrides: dict[str, str]) -> dict[str, str]:
        """Refuses anything outside the control allow-list, then checks the final environment."""
        for key in overrides:
            if key not in FAKE_CONTROL_VARIABLES:
                case.fail(f"{key!r} is not a fake-control variable; refusing to override it")
        environment = self.environment()
        environment.update(overrides)
        self.assert_environment_safe(case, environment)
        return environment

    def assert_environment_safe(self, case: unittest.TestCase, environment: dict[str, str]) -> None:
        """Fails the test — never skips — unless the final environment can only reach fakes."""
        expected_adb = str(self.bin / "adb")
        expected_uv = str(self.bin / "uv")
        if environment.get("ADB") != expected_adb:
            case.fail(f"ADB must point at the fake, not {environment.get('ADB')!r}")
        if environment.get("ANDROID_SERIAL") != SERIAL_SENTINEL:
            case.fail(f"ANDROID_SERIAL must be the sentinel, not {environment.get('ANDROID_SERIAL')!r}")
        for name in ("BASH_ENV", "ENV"):
            if name in environment:
                case.fail(f"{name} must not be present")
        for tool, expected in (("adb", expected_adb), ("uv", expected_uv)):
            if not os.path.isfile(expected) or not os.access(expected, os.X_OK):
                case.fail(f"the fake {tool} is missing or not executable: {expected}")
        path = environment.get("PATH", "")
        entries = path.split(os.pathsep)
        first_entry = entries[0] if entries else ""
        if not entries or entries[0] != str(self.bin):
            case.fail(f"PATH must start at the fake directory, not {first_entry!r}")
        resolved_adb = shutil.which("adb", path=path)
        resolved_uv = shutil.which("uv", path=path)
        if resolved_adb != expected_adb:
            case.fail(f"adb resolves to {resolved_adb!r}, not the fake; refusing to run")
        if resolved_uv != expected_uv:
            case.fail(f"uv resolves to {resolved_uv!r}, not the fake; refusing to run")
        for entry in entries[1:]:
            for tool in ("adb", "uv"):
                candidate = os.path.join(entry, tool)
                if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                    case.fail(f"a real {tool} is reachable at {candidate}; refusing to run")

    def calls(self) -> list[str]:
        return self.log.read_text().splitlines()

    def remote_scripts(self) -> list[str]:
        return [json.loads(line) for line in self.remote_log.read_text().splitlines() if line]


class HostPairScriptTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.fakes = Fakes(Path(self.temporary.name))

    def run_script(self, **extra: str) -> subprocess.CompletedProcess[str]:
        environment = self.fakes.checked_environment(self, extra)
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

    def test_the_harness_refuses_an_override_outside_the_allow_list(self) -> None:
        # Every value below is otherwise safe; the allow-list itself must refuse the key.
        overrides = {
            "ADB": str(self.fakes.bin / "adb"),
            "ANDROID_SERIAL": SERIAL_SENTINEL,
            "PATH": os.pathsep.join(self.fakes.path_entries()),
            "BASH_ENV": str(self.fakes.base / "env.sh"),
            "ENV": str(self.fakes.base / "env.sh"),
            "MISE_PROJECT_ROOT": str(ROOT),
        }
        for name, value in overrides.items():
            with self.subTest(variable=name):
                with self.assertRaises(AssertionError):
                    self.run_script(**{name: value})
        self.assertEqual([], self.fakes.calls(), "the script must never be invoked")

    def test_the_final_environment_check_rejects_a_bad_environment(self) -> None:
        base = self.fakes.environment()
        rendered = os.pathsep.join(self.fakes.path_entries())
        for name, value in (
            ("ADB", "/usr/bin/adb"),
            ("ANDROID_SERIAL", "a-real-device"),
            ("PATH", os.pathsep.join(["/usr/bin", *self.fakes.path_entries()[1:]])),
            ("BASH_ENV", "/tmp/env.sh"),
            ("ENV", "/tmp/env.sh"),
        ):
            with self.subTest(variable=name):
                environment = dict(base)
                environment[name] = value
                with self.assertRaises(AssertionError):
                    self.fakes.assert_environment_safe(self, environment)
        environment = dict(base)
        environment["PATH"] = rendered
        self.fakes.assert_environment_safe(self, environment)

    def test_root_is_preflighted_before_anything_is_stopped(self) -> None:
        result = self.run_script(FAKE_ROOT_DENIED="1")

        self.assertEqual(1, result.returncode)
        self.assertIn("root access is not available", result.stderr)
        self.assertFalse(any("force-stop" in call for call in self.fakes.calls()))
        self.assert_not_started()

    def test_an_unconfirmed_process_stop_aborts_and_restores_the_service(self) -> None:
        result = self.run_script(FAKE_PROCESS_REFUSES_STOP="1")

        self.assertEqual(1, result.returncode)
        self.assertIn("still running", result.stderr)
        self.assert_restored()

    def test_an_adb_query_failure_is_not_read_as_an_empty_pidof(self) -> None:
        result = self.run_script(FAKE_PIDOF_ERROR="1")

        self.assertEqual(1, result.returncode)
        self.assertIn("could not confirm the app process stopped", result.stderr)
        self.assert_restored()

    def test_a_pidof_error_status_is_a_failed_query(self) -> None:
        # 127 (pidof missing) with no output is not the documented no-match status.
        result = self.run_script(FAKE_PIDOF_STATUS="127")

        self.assertEqual(1, result.returncode)
        self.assertIn("could not confirm the app process stopped", result.stderr)
        self.assert_restored()
        self.assertFalse(any("su -c 'sh -s'" in call for call in self.fakes.calls()))

    def test_pidof_status_zero_without_pids_is_a_failed_query(self) -> None:
        result = self.run_script(FAKE_PIDOF_STATUS="0", FAKE_PIDOF_EMPTY="1")

        self.assertEqual(1, result.returncode)
        self.assertIn("could not confirm the app process stopped", result.stderr)
        self.assert_restored()

    def test_a_failure_before_the_rename_restores_the_service(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="fail_before")

        self.assertEqual(1, result.returncode)
        self.assertIn("writing the phone configuration failed", result.stderr)
        self.assert_restored()

    def test_a_failure_before_the_rename_restores_when_the_old_file_is_present(self) -> None:
        self.fakes.set_state(config="old")
        result = self.run_script(FAKE_PAIR_RESULT="fail_before")

        self.assertEqual(1, result.returncode)
        self.assertIn("writing the phone configuration failed", result.stderr)
        self.assert_restored()

    def test_a_labeling_failure_is_a_failure_before_the_rename(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="labeling_fail")

        self.assertEqual(1, result.returncode)
        self.assertIn("cannot label the temporary file", result.stderr)
        self.assertIn("writing the phone configuration failed", result.stderr)
        self.assert_restored()

    def test_an_unverified_replacement_is_reported_and_never_restarted(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="replaced_unverified")

        self.assertEqual(2, result.returncode)
        self.assertIn("could not be verified", result.stderr)
        self.assert_not_started()

    def test_a_hash_mismatch_after_the_rename_never_restarts(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="hash_fail")

        self.assertEqual(2, result.returncode)
        self.assertIn("hash mismatch after replacement", result.stderr)
        self.assert_not_started()

    def test_a_missing_marker_after_the_rename_does_not_restore(self) -> None:
        # The new bytes are on the phone; only the marker after the rename was lost.  The
        # follow-up read proves the rename happened, so no restore is allowed.
        result = self.run_script(FAKE_PAIR_RESULT="missing_marker")

        self.assertEqual(2, result.returncode)
        self.assertIn("did not confirm it", result.stderr)
        self.assert_not_started()
        hashes = [call for call in self.fakes.calls() if "sha256sum" in call]
        self.assertTrue(hashes, "the host must re-read the file before deciding")

    def test_a_failure_after_the_rename_without_a_marker_does_not_restore(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="failed_after_rename")

        self.assertEqual(2, result.returncode)
        self.assertIn("replaced before the run failed", result.stderr)
        self.assert_not_started()

    def test_an_unknown_replacement_outcome_keeps_the_app_stopped(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="unknown_after_rename")

        self.assertEqual(2, result.returncode)
        self.assertIn("outcome is unknown", result.stderr)
        self.assert_not_started()

    def test_a_service_already_stopped_is_not_started_by_a_failure(self) -> None:
        self.fakes.set_state(service=False, process=False)
        result = self.run_script(FAKE_PAIR_RESULT="labeling_fail")

        self.assertEqual(1, result.returncode)
        self.assertIn("writing the phone configuration failed", result.stderr)
        self.assert_not_started()

    def test_a_verified_replacement_stops_the_app_and_starts_the_service(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="ok")

        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.fakes.calls()
        self.assertTrue(any("am force-stop" in call for call in calls))
        self.assertTrue(any("pidof" in call for call in calls))
        self.assertTrue(any("am start-foreground-service" in call for call in calls))

    def test_the_remote_script_owns_labels_renames_and_hashes_in_order(self) -> None:
        result = self.run_script(FAKE_PAIR_RESULT="ok")

        self.assertEqual(0, result.returncode, result.stderr)
        scripts = self.fakes.remote_scripts()
        self.assertEqual(1, len(scripts), "the remote script must be recorded once")
        lines = scripts[0].splitlines()

        def position(fragment: str) -> int:
            for index, line in enumerate(lines):
                if fragment in line:
                    return index
            self.fail(f"{fragment!r} is missing from the remote script:\n{scripts[0]}")

        chown = position("chown ")
        chmod_dir = position("chmod 700")
        chmod_file = position("chmod 600")
        chcon = position("chcon ")
        before_mv = position("echo PX_BEFORE_MV")
        mv = position("mv -f ")
        replaced = position("echo PX_REPLACED=1")
        hash_line = position("sha256sum ")
        self.assertLess(chown, chcon)
        self.assertLess(chmod_dir, chcon)
        self.assertLess(chmod_file, chcon)
        self.assertLess(chcon, mv)
        self.assertLess(before_mv, mv)
        self.assertLess(mv, replaced)
        self.assertLess(replaced, hash_line)
        self.assertLess(chmod_dir, mv)


if __name__ == "__main__":
    unittest.main()
