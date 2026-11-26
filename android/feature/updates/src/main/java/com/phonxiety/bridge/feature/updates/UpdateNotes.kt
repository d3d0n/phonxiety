package com.phonxiety.bridge.feature.updates

import com.phonxiety.bridge.data.updates.CompatibilityNote
import com.phonxiety.bridge.designsystem.UiText

/**
 * The Updates feature's words for a compatibility note. The data layer names the fact and its
 * values; this file turns it into the sentence, so compatibility decisions stay unit-tested in
 * `core:data` and every sentence stays in the module's resources.
 */
internal fun CompatibilityNote.text(): UiText = when (this) {
    is CompatibilityNote.NeedsNewerAndroid -> {
        val required = androidName(requiredSdk)
        val phone = androidName(phoneSdk)
        if (required != null && phone != null) {
            UiText.Res(
                R.string.updates_note_android_version,
                listOf(required, requiredSdk, phone, phoneSdk)
            )
        } else {
            UiText.Res(R.string.updates_note_android_api, listOf(requiredSdk, phoneSdk))
        }
    }

    is CompatibilityNote.NewerModuleSchema ->
        UiText.Res(R.string.updates_note_module_schema, listOf(requiredSchema, supportedSchema))

    CompatibilityNote.ModuleStateUnknown -> UiText.Res(R.string.updates_note_module_unknown)

    CompatibilityNote.ModuleNotInstalled -> UiText.Res(R.string.updates_note_module_absent)

    CompatibilityNote.ModuleDisabled -> UiText.Res(R.string.updates_note_module_disabled)

    CompatibilityNote.ModuleRebootRequired -> UiText.Res(R.string.updates_note_module_reboot)

    is CompatibilityNote.NewPrivilegedGrants ->
        UiText.Plural(R.plurals.updates_note_privileged, permissions.size)

    is CompatibilityNote.NeedsNewerDaemon ->
        UiText.Res(R.string.updates_note_daemon_old, listOf(minVersion))

    is CompatibilityNote.DaemonApiOlder ->
        UiText.Res(R.string.updates_note_daemon_api, listOf(daemonAppApi, requiredAppApi))

    is CompatibilityNote.DaemonProtocolUnsupported -> UiText.Res(
        R.string.updates_note_daemon_protocol,
        listOf(daemonProtocols.joinToString(", "), appProtocol)
    )

    CompatibilityNote.DaemonUnreachable -> UiText.Res(R.string.updates_note_daemon_unreachable)

    is CompatibilityNote.Reported -> UiText.Literal(text)
}

/**
 * The Android release that carries [sdk]; null for a version this app does not know. Android names
 * its releases the same way in every language, so the name is not a translated resource.
 */
internal fun androidName(sdk: Int): String? = when (sdk) {
    29 -> "Android 10"
    30 -> "Android 11"
    31 -> "Android 12"
    32 -> "Android 12L"
    33 -> "Android 13"
    34 -> "Android 14"
    35 -> "Android 15"
    36 -> "Android 16"
    37 -> "Android 17"
    else -> null
}

/**
 * A human title for a privileged permission the module's allowlist grants. A permission this app
 * does not know keeps its own identifier: inventing a friendly name for it would be a guess.
 */
internal fun privilegedTitle(permission: String): UiText = when (permission) {
    "android.permission.CAPTURE_AUDIO_OUTPUT" -> UiText.Res(R.string.updates_permission_capture_audio)
    "android.permission.MODIFY_PHONE_STATE" -> UiText.Res(R.string.updates_permission_modify_phone_state)
    "android.permission.CONTROL_INCALL_EXPERIENCE" -> UiText.Res(R.string.updates_permission_control_in_call)
    "android.permission.INSTALL_SELF_UPDATES" -> UiText.Res(R.string.updates_permission_install_updates)
    "android.permission.REBOOT" -> UiText.Res(R.string.updates_permission_reboot)
    else -> UiText.Literal(permission.substringAfterLast('.'))
}
