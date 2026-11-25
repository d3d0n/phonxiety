# `:benchmark` — Macrobenchmark and baseline profiles

A `com.android.test` module that drives the release APK of `:app` on a connected
device. It is never part of the app; the APK it produces is a test APK.

## What it measures

| Class | Purpose |
|---|---|
| `BaselineProfileGenerator` | Records the profile: cold start, then dashboard → Calls → Lab, with a list scroll on each destination |
| `StartupBenchmark` | Cold-start `StartupTimingMetric` with no compilation and with the profile required |

The generator's journeys are `internal` extensions in `Journeys.kt` so the
benchmarks and the profile describe the same user path. Only the startup test is
marked `includeInStartupProfile`; the navigation journey stays out of the startup
profile.

A device with no stored pairing opens the setup wizard, not the dashboard. The
navigation journey then stops after the cold start and profiles the wizard
alone — pair the device first (`phonxiety pair`, or open a `phonxiety://pair`
link) if the profile is meant to cover Calls and the lab.

## Generating a profile

Prerequisites: one device on `adb devices` — a physical device on Android 13
(API 33) or newer, or a rooted device, or an AOSP emulator (the collector changes
the process's compilation mode, which needs one of those).

```sh
# from android/
./gradlew :app:generateBaselineProfile
```

The task builds `:app:release`, installs it with the generator test APK, runs
`BaselineProfileGenerator`, and copies the result to:

```
app/src/release/generated/baselineProfiles/baseline-prof.txt
```

That file is the source of truth: commit it with the change that produced it.
`saveInSrc = true` in `app/build.gradle.kts` keeps it in `src/` rather than in
`build/`.

A Gradle-managed device also works; declare it in `benchmark/build.gradle.kts`
under `testOptions.managedDevices.devices`, add it to `baselineProfile {
managedDevices += "…" }`, and set `useConnectedDevices = false`.

## Checking the profile

The plugin gives the test module one variant per release build type it creates:
`benchmarkRelease` (the minified release the profile comes from) and
`nonMinifiedRelease` (a debuggable-profile clone for reading stack traces).

```sh
# both cold-start cases; the profile case fails until a profile is installed
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonxiety.bridge.benchmark.StartupBenchmark

# the same measurements with readable frames
./gradlew :benchmark:connectedNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonxiety.bridge.benchmark.StartupBenchmark
```

Compare the two `timeToInitialDisplayMs` distributions: the profile case should
be faster and tighter. A profile that does not improve anything is a signal that
the recorded journey is not the expensive one.

With the profile committed, `:app:assembleRelease` packages it and
`androidx.profileinstaller` installs it on first run; `adb shell dumpsys package
com.phonxiety.bridge | grep -i profile` shows the installed profile.

## Signing

The benchmarks install the release APK, so the APK must carry the key the device
accepts. `PHONXIETY_KEYSTORE*` signs it; without those variables the debug key is
used, which is fine for a test device and a signature mismatch for a real phone.
`mise run phone:build --release` refuses to build without a key for that reason.
