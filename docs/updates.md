# Updates: one document, three rows, two install paths

`update.json` is the release document. The root manager (Magisk, KernelSU, KernelSU-Next) reads the
four fields it knows; the Phonxiety app reads the extension fields and installs the APK itself. One
task writes the document and the artifacts:

```sh
mise run phone:publish                      # build, then write dist/update.json
mise run phone:publish --serve              # also put it where the daemon serves it
mise run phone:publish --no-build           # rewrite update.json from the current dist artifacts
```

`--url-base` (or `PHONXIETY_UPDATE_BASE_URL`) says where the artifacts would be hosted; it must be
`https://`. `--serve` makes the daemon the default source: it copies the APK and the module zip into
`<state dir>/updates/` and writes `updates/manifest.json` with `apk.url = "daemon:apk"`,
`module.url = "daemon:module"` and the served file names in `apk.file` / `module.file`, while
`module.zipUrl` (and the top-level `zipUrl`) stay https for the root managers' `updateJson`. The task refuses to write a document when the module and APK versions disagree, when an
artifact is missing, when the module zip's `updateJson` does not point at this document, or when the
APK has no readable signing certificate.

## The document

The copy written by `--serve` (the `dist/` copy keeps `https://` sources and no `file` fields):

```json
{
  "channel": "stable",
  "version": "0.4.0",
  "versionCode": 4,
  "zipUrl": "https://host/phonxiety-bridge-0.4.0.zip",
  "changelog": "https://host/CHANGELOG.md",
  "published_at": "2026-11-26T12:00:00+00:00",
  "updateJsonUrl": "https://host/update.json",
  "apk": {
    "version": "0.4.0",
    "versionCode": 4,
    "url": "daemon:apk",
    "file": "phonxiety-bridge-0.4.0.apk",
    "sha256": "<64 hex>",
    "size": 7340032,
    "signingCertSha256": "<64 hex>",
    "minSdk": 34
  },
  "module": {
    "version": "0.4.0",
    "versionCode": 4,
    "zipUrl": "https://host/phonxiety-bridge-0.4.0.zip",
    "url": "daemon:module",
    "file": "phonxiety-bridge-0.4.0.zip",
    "sha256": "<64 hex>",
    "size": 1048576,
    "schemaVersion": 1,
    "requiresReboot": true,
    "permissions": [
      "android.permission.CAPTURE_AUDIO_OUTPUT",
      "android.permission.MODIFY_PHONE_STATE",
      "android.permission.CONTROL_INCALL_EXPERIENCE",
      "android.permission.INSTALL_SELF_UPDATES",
      "android.permission.REBOOT",
      "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND"
    ]
  },
  "daemon": {
    "minVersion": "0.1.0",
    "protocol": 3,
    "appApi": 1,
    "moduleSchema": 1
  }
}
```

| Field | Who reads it | Meaning |
|---|---|---|
| `version`, `versionCode` | manager, app | The module release. The manager compares `versionCode` with the installed module. |
| `zipUrl`, `changelog` | manager | The module zip and the page the manager links. |
| `channel` | app | Written into the document; `phone:publish --channel` sets it. |
| `updateJsonUrl` | app | Where this document lives; the app embeds it as `updateJson` when it rebuilds the module zip. |
| `apk.url` | app | `daemon:apk` (read `apk.file` through `/app/v1/update/artifact`) or an `https://` URL. |
| `apk.file` | daemon | Basename under `<state dir>/updates/` that the artifact route resolves. Required when `url` is `daemon:apk`. |
| `apk.sha256` | app | The digest of exactly the bytes that are installed. `apk.size` is informational. |
| `apk.signingCertSha256` | app | SHA-256 of the signing certificate. The archive's signers must equal the installed app's and include this certificate. |
| `apk.minSdk` | app | A release above the phone's `Build.VERSION.SDK_INT` is shown as incompatible. |
| `module.url`, `module.file` | app | The app-facing source of the same zip: `daemon:module` plus the served basename, or an https URL. The phone currently rebuilds the zip from its running APK and does not download this copy. |
| `module.sha256` | people | Digest of the published module zip, for checking a download by hand. The app only checks that the value is a digest, and the root manager never reads it. |
| `module.schemaVersion` | app | Payload schema. The app applies a release only when `schemaVersion <= MODULE_SCHEMA_VERSION` (currently `1`). |
| `module.requiresReboot` | app | The release needs a boot. Every module release currently does. |
| `module.permissions` | app | Privileged grants in the allowlist. A grant the installed app does not hold forces a reboot even if `requiresReboot` were false, and makes the module install wait for the app update: the phone rebuilds the zip from the running APK. |
| `daemon.minVersion`, `protocol`, `appApi` | app | What the daemon on the computer must be. Checked against `GET /app/v1/compat`. |
| `daemon.moduleSchema` | app | Module schema the daemon understands (informational; the app applies its own gate). |

Magisk, KernelSU and KernelSU-Next ignore the extension fields. APatch does not document
`updateJson`; there the app is the only updater.

## Where the app gets the manifest

Only through `GET /app/v1/update/manifest` over the authenticated device link. The app never fetches
`updateJsonUrl` directly: that field is what the root manager reads from `module.prop`, and the app's
manifest is the daemon's copy of the same document. A daemon without `updates/manifest.json`
answers `manifest_unavailable`, which the Updates screen reports as "no update document is
published".

## How the daemon serves it

`GET /app/v1/update/manifest?channel=` returns the file at
`<state dir>/updates/manifest.json` unchanged (the daemon also understands a `channels` map and an
`entry` object; publish writes the flat document). `phone:publish --serve` writes that file; the
state directory is `$PHONXIETY_STATE_DIR` or `~/.local/share/phonxiety`. Without it the endpoint
answers `manifest_unavailable`, and the app's Updates screen says no document is published.

## App updates

The App row's action downloads from `apk.url` — `daemon:apk` through
`GET /app/v1/update/artifact?name=apk` on the link (paged JSON, or the attachment stream when the
transport offers one), or the https URL when the document names one — to app files, and then
verifies:

1. `sha256` is computed while streaming; a mismatch deletes the download and refuses the install.
2. `PackageManager.getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)` must report this package.
3. The archive's signer set must equal the installed app's signer set, and must contain
   `apk.signingCertSha256`.
4. An archive with a lower `versionCode` than the installed app is refused (a downgrade is what
   rollback is for).

The previous APK is copied to `filesDir/updates/previous.apk` (with a small metadata file) before
the installer runs, so a failed install can be undone by reinstalling that copy. If the copy cannot
be made, the install still runs and the rollback card later reports that no APK was kept.

Installation uses a `PackageInstaller` session with `setRequestUpdateOwnership(true)`. With the
module installed the app holds `INSTALL_SELF_UPDATES`, so there is no user prompt; the session
result arrives through `PackageInstallerResultReceiver` (success, failure, or a request to show the
platform's confirmation dialog). Without that permission — an `adb`-installed build before the
module exists — the app falls back to the system installer with a `FileProvider` URI, and
`REQUEST_INSTALL_PACKAGES` must be allowed for Phonxiety in the system settings.

The service restarts through `MY_PACKAGE_REPLACED`.

### Rollback

`filesDir/updates/previous.apk` is reinstalled as root: the APK is copied to `/data/local/tmp` with
the allowlisted `cp` command, then `pm install -r -d` runs through `RootShell`. Root is what allows
the version code to go backwards; without root the rollback action reports that instead of trying.

That install alone only works while the module has not mounted a newer APK on the system image.
Android compares a downgrade against the version mounted there, so once the module has put a newer
priv-app on the image, the previous module has to go back first. The app then runs the combined
rollback: it asks for the published previous-module archive, stages that archive as root, and hands
the rest to a detached root script, because removing this app's own update freezes and kills its
process. The next boot is checked against the rollback's module version, module digest and app
version; anything else is reported as a pending rollback with a retry.

A rollback is only available for an update the app performed itself. The previous APK is kept
before an app update, and the previous module archive is captured from the mounted APK before a
module update. A module update installed directly through the root manager replaces both without
either copy, so an in-app rollback afterwards is not guaranteed: the app offers what the copies on
the phone allow and otherwise names the blocker — no kept APK, no previous module archive, an
archive that is not older than the mounted module, an archive whose digest does not match its
metadata, a mounted version or root storage it cannot read, or a running app that is not newer than
the kept one.

For a module rollback without the app, install an older module zip:
`mise run phone:install --zip dist/<older>.zip`.

## Module updates

The Module row's action rebuilds the zip on the phone with `ModuleZipBuilder`: the APK comes from
`ApplicationInfo.sourceDir`, so the module's `/system/priv-app` copy is exactly the running build,
and `module.prop`, `customize.sh`, `action.sh`, the WebUI and the privapp allowlist come from the
assets copied out of `android/magisk/`. The rebuild embeds `updateJsonUrl` as `updateJson`.

The published `module.sha256` is the digest of the zip `zipUrl` serves, for checking a download by
hand. The root manager's update document carries only `version`, `versionCode`, `zipUrl` and
`changelog`, so it never reads the digest. The phone's own rebuild is deliberately different
because it embeds the APK that is running there.

The zip is then installed through the detected provider (`magisk --install-module`,
`ksud module install`, `apd module install`) and the phone is rebooted
(`PowerManager.reboot` with the module reason, else `su -c reboot`). A refused reboot leaves the
screen saying the update is installed and a manual reboot is needed.

A module update is required when the packaged APK, the allowlist or the scripts change. A new
privileged permission is the case that matters: the old allowlist does not grant it, the app runs
without it until the overlay is replaced by a boot, and the Updates screen says so next to the
reboot note.

That release is two steps, and the screen routes them in that order. The app row stays installable
and says a module install and a reboot follow. The Module row's install is disabled until this phone
runs the release's APK, because the rebuilt zip takes its APK from `ApplicationInfo.sourceDir`:
installing it from the older app would put the older payload back and reboot for nothing. Once the
app update has landed, the same row installs the module from the new APK and reboots.

A module version code that did not change still needs the install when the grant is missing: the
installed module was built from an app that did not request that permission. The Module row shows
it as an install rather than "Up to date", and the install rebuilds from the running APK and
reboots.

## Manager parity

`module.prop` carries `updateJson=<url-base>/update.json`, which `phone:build` writes when
`PHONXIETY_UPDATE_JSON` is set — `phone:publish` sets it before building, and verifies it in the
produced zip. The manager therefore offers the same module release the app offers. When no URL is
configured, the field is dropped and the manager shows no update; the app still checks
`/app/v1/update/manifest`.

## Versions this release speaks

`phone:publish` reads them from the source, so they cannot drift:

| Field | Source |
|---|---|
| `daemon.minVersion` | `__version__` in `src/phonxiety/__init__.py` (override with `--min-daemon`) |
| `daemon.protocol` | `PROTOCOL_VERSION` in `src/phonxiety/device.py` |
| `daemon.appApi` | `APP_API_VERSION` in `src/phonxiety/app_api.py` |
| `module.schemaVersion` | `MODULE_SCHEMA_VERSION` in `core/data/.../updates/UpdateManifest.kt`, matched by the task's constant |
