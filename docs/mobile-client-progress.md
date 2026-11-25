# Mobile client: progress and evidence

What each phase of [`mobile-client-plan.md`](mobile-client-plan.md) produced on the Pixel 6a (Android 17, `bluejay`), with the commit it was checked on and the evidence. Screenshots live in `docs/mobile-client-progress/`.

| Phase | Outcome on the phone (plan §10) | Status | Commit | Evidence |
|---|---|---|---|---|
| 0 Foundations | Installable APK + module from the build; `mise run phone:install` still works | done 2026-11-26 | ced079f | `./gradlew assembleDebug assembleRelease testDebugUnitTest lint ktlintCheck` exit 0, 111 unit tests; `mise run phone:build` → `dist/phonxiety-bridge-0.3.0.zip` (APK, allowlist with the five privileged permissions, `customize.sh`, `action.sh`, icon, webroot); `adb install -r` + `mise run phone:install` + reboot: `dumpsys package` shows `/system/priv-app/phonxiety-bridge` and `REBOOT`, `INSTALL_SELF_UPDATES`, `CAPTURE_AUDIO_OUTPUT` granted (`ro.control_privapp_permissions=enforce`); `phonxiety status` → `connected … direct, 35 ms`, `opus audio` |
| 1 Compose shell | App opens to the new dashboard, nothing lost from the old screen | done 2026-11-26 | dae53c0 | Release APK on the Pixel opens Home with link card (iroh, direct, RTT p50 13 ms, reconnects, endpoint id), call, audio, phone cards and Home/Calls/Lab/Settings navigation: `docs/evidence/dashboard-direct.png` |
| 2 Root layer and one-button setup | No module → "paired and linked" with one tap, one root grant, one reboot — on Magisk, KernelSU (+ metamodule), APatch | pending | | |
| 3 Reactive engine | A real call completes; dashboard updates without polling | done 2026-11-26 | dae53c0 | Call `fdae00e2` completed `answered/succeeded`, ended `LOCAL` on the reactive engine (typed protocol v3); dashboard state from `StateFlow<BridgeState>`; `:core:device` unit tests green |
| 4 App API | Link test and probes from the phone match `phonxiety link-test` | done 2026-11-26 | dae53c0 | Lab → Link test (opus, 5 s): p50 12 ms, p95 97 ms, max 164 ms, loss 0.0 %, 250/250 frames, route direct (`docs/evidence/lab-link-test.png`); `phonxiety link-test --seconds 5` right after: median 17 ms, p95 92 ms, 250/250 frames, verdict good. Path probe from the phone: iroh reachable 11 ms; lan and tailscale reported unreachable (`docs/evidence/lab-probe.png`) — LAN result under investigation |
| 5 Live call | A call placed from the phone is supervised live, instructions reach the agent, summary before hang-up | partial 2026-11-26 | dae53c0 | Call placed from the composer to +998901234567 (`fdae00e2`): transcript on the live call screen, agent hung up itself, `disconnect_cause LOCAL` (`docs/evidence/live-call-*.png`, `live-call-fdae00e2.txt`). Found and sent to a fix: all turns labelled "Far end", phase pill "idle", empty meters, system dialer covering the app, timer not frozen. Instruction and summarize-before-hang-up not yet exercised live |
| 6 Calls and detail | A call started from the computer is listed, transcript shown, `.m4a` plays | pending | | |
| 7 Test lab | Smoke run from the phone: probe → link test → loopback → 30 s test call → export bundle | pending | | |
| 8 Settings and auto-tune | Codec/bitrate changed on the phone changes the next call's negotiated codec | pending | | |
| 9 Updates and ecosystem | Test build installs from the app (incl. privileged update + reboot + rollback); manager offers the module update | pending | | |
| 10 Connections | Dashboard shows measured LAN, Tailscale paths; Tailscale-only link connects | pending | | |
| 11 Doctor and surfaces | Deliberately broken install repaired from the phone; tile hangs up a call | pending | | |
| 12 Hardening | Signed release passes the smoke run, survives 24 h idle plus three calls | pending | | |

## Setup kept on the daemon host

- Boot images before any root change: `~/.local/share/phonxiety/boot-backup/20261126-*/` — `boot_a/b`, `vendor_boot_a/b` (Magisk-patched, slot `_b` active), `stock_boot.img` (Magisk's own backup, SHA-1 `9199bf97…`), build fingerprint `google/bluejay_beta/bluejay:17/DP11.261030.003/16847878`.
- Root kits: `~/.local/share/phonxiety/root-kits/` — KernelSU v3.3.1 manager, `ksud` (macOS + Android), LKM `android14-6.1`; APatch 11282 manager.
