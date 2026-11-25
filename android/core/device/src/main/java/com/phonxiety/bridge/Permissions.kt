package com.phonxiety.bridge

import android.content.Context
import android.content.pm.PackageManager

/**
 * Permission names are string literals: several of these are not present in the
 * public Android SDK's Manifest.permission class even though they are granted
 * by the privileged allowlist.
 */
object Permissions {
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val CALL_PHONE = "android.permission.CALL_PHONE"
    const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    const val CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT"
    const val MODIFY_PHONE_STATE = "android.permission.MODIFY_PHONE_STATE"
    const val CONTROL_INCALL_EXPERIENCE = "android.permission.CONTROL_INCALL_EXPERIENCE"

    /** Requested from the user at runtime. */
    val RUNTIME = listOf(RECORD_AUDIO, CALL_PHONE, READ_PHONE_STATE, POST_NOTIFICATIONS, ACCESS_LOCAL_NETWORK)

    /** Granted only by the Magisk privapp allowlist. */
    val PRIVILEGED = listOf(CAPTURE_AUDIO_OUTPUT, MODIFY_PHONE_STATE, CONTROL_INCALL_EXPERIENCE)

    fun granted(context: Context, permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun runtimeMissing(context: Context): List<String> = RUNTIME.filterNot { granted(context, it) }

    fun privilegedMissing(context: Context): List<String> = PRIVILEGED.filterNot { granted(context, it) }
}
