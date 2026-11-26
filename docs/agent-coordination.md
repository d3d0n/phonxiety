# Running many agents on this repo

The process is the global `parallel-agents` skill (isolated ovnfs checkouts, task cards,
fresh-context review, one integrator). This file lists only what is specific to phonxiety.

## Integrator-only resources

The Pixel (adb, root providers, reboots, flashing), the daemon process (`phonxiety start|stop`),
live calls, and the integration branch `main`.

## Hotspots (one owner per wave)

- `android/gradle/libs.versions.toml`, `android/settings.gradle.kts`, `android/build.gradle.kts`
- `android/app/**` navigation host and `Routes.kt`
- `src/phonxiety/app_api.py` route table, `src/phonxiety/settings.py`
- Shared contracts: `contracts/**` (`/app/v1` request/response files both suites run against;
  regenerate with `PHONXIETY_UPDATE_CONTRACTS=1 uv run pytest tests/test_app_api_contract.py`),
  `android/core/model/**` (`BridgeState`, `DeviceProtocol`, `ApiModels`),
  `android/core/data/.../Repositories.kt`

## Checks a worker runs in its checkout

- Android, scoped to owned modules: `mise exec -- ./gradlew -q :<module>:testDebugUnitTest
  :<module>:lint :<module>:verifyRoborazziDebug` from `android/`, plus `ktlintCheck`.
- Python: `uv run pytest -q`.
- Wording: `grep -rniE "phase [0-9]|roadmap|later stage|\bMac\b"` over changed files is empty
  (the daemon runs on any computer; leftovers are `TODO(topic): what is missing`).

The integrator runs the aggregate line `assembleDebug assembleRelease testDebugUnitTest lint
ktlintCheck verifyRoborazziDebug` once per wave after merging.
