# Mobile client: progress and evidence

What each phase of [`mobile-client-plan.md`](mobile-client-plan.md) produced on the Pixel 6a (Android 17, `bluejay`), with the commit it was checked on and the evidence. Screenshots live in `docs/mobile-client-progress/`.

| Phase | Outcome on the phone (plan §10) | Status | Commit | Evidence |
|---|---|---|---|---|
| 0 Foundations | Installable APK + module from the build; `mise run phone:install` still works | in progress | | |
| 1 Compose shell | App opens to the new dashboard, nothing lost from the old screen | pending | | |
| 2 Root layer and one-button setup | No module → "paired and linked" with one tap, one root grant, one reboot — on Magisk, KernelSU (+ metamodule), APatch | pending | | |
| 3 Reactive engine | A real call completes; dashboard updates without polling | pending | | |
| 4 App API | Link test and probes from the phone match `phonxiety link-test` | pending | | |
| 5 Live call | A call placed from the phone is supervised live, instructions reach the agent, summary before hang-up | pending | | |
| 6 Calls and detail | A call started from the Mac is listed, transcript shown, `.m4a` plays | pending | | |
| 7 Test lab | Smoke run from the phone: probe → link test → loopback → 30 s test call → export bundle | pending | | |
| 8 Settings and auto-tune | Codec/bitrate changed on the phone changes the next call's negotiated codec | pending | | |
| 9 Updates and ecosystem | Test build installs from the app (incl. privileged update + reboot + rollback); manager offers the module update | pending | | |
| 10 Connections | Dashboard shows measured LAN, Tailscale paths; Tailscale-only link connects | pending | | |
| 11 Doctor and surfaces | Deliberately broken install repaired from the phone; tile hangs up a call | pending | | |
| 12 Hardening | Signed release passes the smoke run, survives 24 h idle plus three calls | pending | | |

## Setup kept on the Mac

- Boot images before any root change: `~/.local/share/phonxiety/boot-backup/20261126-*/` — `boot_a/b`, `vendor_boot_a/b` (Magisk-patched, slot `_b` active), `stock_boot.img` (Magisk's own backup, SHA-1 `9199bf97…`), build fingerprint `google/bluejay_beta/bluejay:17/DP11.261030.003/16847878`.
- Root kits: `~/.local/share/phonxiety/root-kits/` — KernelSU v3.3.1 manager, `ksud` (macOS + Android), LKM `android14-6.1`; APatch 11282 manager.
