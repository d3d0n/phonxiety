package com.phonxiety.bridge.designsystem

/**
 * A human title for a privileged permission the module's allowlist grants. A permission this app
 * does not know keeps its own identifier: inventing a friendly name for it would be a guess.
 */
fun privilegedTitle(permission: String): UiText = when (permission) {
    "android.permission.CAPTURE_AUDIO_OUTPUT" -> UiText.Res(R.string.designsystem_privileged_capture_audio)

    "android.permission.MODIFY_PHONE_STATE" -> UiText.Res(R.string.designsystem_privileged_modify_phone_state)

    "android.permission.CONTROL_INCALL_EXPERIENCE" -> UiText.Res(R.string.designsystem_privileged_control_in_call)

    "android.permission.INSTALL_SELF_UPDATES" -> UiText.Res(R.string.designsystem_privileged_install_updates)

    "android.permission.REBOOT" -> UiText.Res(R.string.designsystem_privileged_reboot)

    "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND" ->
        UiText.Res(R.string.designsystem_privileged_start_after_boot)

    else -> UiText.Literal(permission.substringAfterLast('.'))
}
