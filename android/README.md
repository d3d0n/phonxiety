# The phone side

The Android app and the root module that let the daemon on your computer place and run calls
through this phone. Build and install them from the repository root with `mise run phone:build`
and `mise run phone:install` (see the root README). Everything below runs from `android/`.

## Modules

`:app` depends on the features and the core modules. The features depend only on core modules,
never on each other. A binding that crosses features lives in `:app`: for example, the
notification's hang-up reaches `:feature:widgets` from there. Among the core modules,
`:core:model`, `:core:root` and `:core:designsystem` depend on nothing, `:core:device` only on
`:core:model`, and `:core:data` on `:core:model`, `:core:device` and `:core:root`.

| Module | Owns | Entry point | Tests |
|---|---|---|---|
| `:app` | The single activity, navigation between the sections, the connection strip, notification actions, the first destination behind the splash | `PhonxietyApp`, `MainActivity`, `PhonxietyNavigationHost` | `:app:testDebugUnitTest`, `:app:testReleaseUnitTest` |
| `:core:model` | Types every module shares: the bridge state, the device protocol, the app API's models, the `AppGateway` and `PairingGate` interfaces | `BridgeState`, `DeviceProtocol`, `AppGateway` | `:core:model:testDebugUnitTest` |
| `:core:device` | The bridge: the device protocol on the phone, call ownership, call audio, Telecom, the foreground service, start on boot, the link to the computer (iroh, LAN and Tailscale WebSocket, the adb tunnel), the pairing it stores | `BridgeService`, `BridgeInCallService`, `BootReceiver`, `BridgeRuntime` | `:core:device:testDebugUnitTest` |
| `:core:data` | The daemon's app API client and repositories, the call cache, app and module updates with rollback | `DaemonApi`, `LinkAppApi`, `CallsRepository`, `UpdatesService` | `:core:data:testDebugUnitTest` |
| `:core:root` | Root detection and module installation on Magisk, KernelSU, KernelSU-Next and APatch | `RootDetector`, `ModuleController`, `RootShell` | `:core:root:testDebugUnitTest` |
| `:core:designsystem` | The theme, brand tokens, shared components, and the screenshot test fixtures | `PhonxietyTheme`, `ScreenScaffold` | `:core:designsystem:testDebugUnitTest`, `:core:designsystem:verifyRoborazziDebug` |
| `:feature:onboarding` | The setup wizard: root, the module, permissions, pairing | `OnboardingRoute` | `:feature:onboarding:testDebugUnitTest`, `…:verifyRoborazziDebug` |
| `:feature:dashboard` | The home section: link, call, device, audio and pairing cards | `DashboardRoute` | `:feature:dashboard:testDebugUnitTest`, `…:verifyRoborazziDebug` |
| `:feature:livecall` | The live call screen, the composer for a new call, the return over the system call screen | `LiveCallRoute`, `ComposerRoute`, `CallFrontReturn` | `:feature:livecall:testDebugUnitTest`, `…:verifyRoborazziDebug` |
| `:feature:calls` | Call history and each call's transcript, result and recording | `CallsRoute`, `CallDetailRoute` | `:feature:calls:testDebugUnitTest`, `…:verifyRoborazziDebug` |
| `:feature:lab` | Link and audio tests, codec trials, the test call, diagnostics to share | `LabRoute` | `:feature:lab:testDebugUnitTest`, `…:verifyRoborazziDebug` |
| `:feature:settings` | Connection, audio, device and daemon settings, auto-tune with undo | `SettingsRoute` | `:feature:settings:testDebugUnitTest`, `…:verifyRoborazziDebug` |
| `:feature:updates` | App and module updates, rollback to the previous build | `UpdatesRoute` | `:feature:updates:testDebugUnitTest`, `…:verifyRoborazziDebug` |
| `:feature:doctor` | Health checks of the module, permissions, link and calling, and the repairs | `DoctorRoute` | `:feature:doctor:testDebugUnitTest`, `…:verifyRoborazziDebug` |
| `:feature:widgets` | Quick Settings tiles, the home screen widget, the hang-up they share with the notification | `LinkTileService`, `HangupTileService`, `PhonxietyWidget`, `CallEnd` | `:feature:widgets:testDebugUnitTest` |
| `:benchmark` | Startup benchmark and the baseline profile, on a connected device | see [`benchmark/README.md`](benchmark/README.md) | `:app:generateBaselineProfile` |

Outside the Gradle modules:

- `build-logic/` holds the convention plugins every module applies: `phonxiety.android.application`, `.library`, `.compose`, `.feature` and `.test`. A feature applies `phonxiety.android.feature` and lists only what is its own.
- `magisk/` is the root module: it installs the app into `/system/priv-app` with its privileged permissions, and its `updateJson` lets the root manager offer new versions.

## Invariants

**Whole app**

- A screen's state is one `UiState` held by its ViewModel.
- A destructive or irreversible action asks first. The question lives in that state, so it survives rotation, and the answer is consumed once: a second tap never repeats the action.
- Tapping the active tab returns to the section's first screen; tapped again there, it scrolls back to the top. Each section's first screen registers its key with `RegisterScrollToTop`: `"dashboard"`, `"calls"`, `"lab"`, `"settings"`.
- The interface is English. Strings live in resources.

**`:core:device`**

- Call audio is 48 kHz mono in 20 ms frames, and its timing matters more than anything else on the phone. A change to `PhoneAudio`, `OpusCodec`, pacing or buffers needs a real call on a phone: the unit tests cannot hear it.
- Long-lived work runs in the named scopes from `BridgeScope`, which report a crash by its class only, never its message.
- One link at a time, owned by `LinkSupervisor`. On iroh the control queue is bounded: when it is full, the link fails with `control_backlog` instead of dropping messages.
- The engine owns at most one call, and a lost link ends it.
- The frame tap records direction, size and kind, never payloads.
- `BridgeLog` builds a message only when it is kept.

**`:core:data`**

- One `DaemonJson` configuration encodes and decodes the app API's requests and responses. The exception is the update manifest: the root managers read the same document, so `UpdateManifestCodec` decodes it and validates every field the installer relies on.
- Every request goes through `AppGateway`. During a reconnect it waits for the link within its own deadline instead of failing at once.

**`:core:designsystem` and the screenshots**

- Colours and type come from the brand tokens.
- Screenshot tests render at fixed sizes, and a screenshot that shows a time pins the time zone to UTC, so a golden matches on every machine. Re-record a golden only for a pixel change you meant, and name it in the commit.

## Checks

Unit and screenshot tests run on the JVM, with no phone. The full gate, the same as CI plus ktlint
and the release-variant app tests:

```sh
mise exec -- ./gradlew assembleDebug assembleRelease testDebugUnitTest lint ktlintCheck \
    verifyRoborazziDebug :app:testReleaseUnitTest
```

One module: `./gradlew :feature:lab:testDebugUnitTest :feature:lab:verifyRoborazziDebug`. To
re-record screenshots for an intended change: `./gradlew :feature:lab:recordRoborazziDebug`.

## Changelog

`android/CHANGELOG.md` holds the notes of the next release, in the user's words.
`mise run phone:publish` copies it to `dist/CHANGELOG.md`, the file the release carries and the root
manager shows when it offers the update.

- Between releases it starts with `# Unreleased`. Add each user-visible change there in the same change that makes it.
- Start an entry with **Breaking:** when the phone needs a newer daemon, or the daemon a newer phone.
- Releasing renames the heading to `# Phonxiety bridge <version>`; `phone:publish` refuses notes whose heading does not name the version it publishes. After the release, the next change starts a new `# Unreleased`.
