package com.phonxiety.bridge.feature.livecall

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Opens the system's own in-call screen. The live call uses it when the daemon could not end the
 * call, so the platform's own in-call controls remain a way out. [show] reports whether the
 * screen appeared; a missing permission comes back as false, never as a crash.
 */
fun interface CallScreenOpener {
    fun show(): Boolean
}

/**
 * The platform implementation. `TelecomManager.showInCallScreen` requires `READ_PHONE_STATE`;
 * a revoked grant surfaces as a [SecurityException], which the seam reports as "not shown".
 */
@Module
@InstallIn(SingletonComponent::class)
object CallScreenModule {
    @Provides
    @Singleton
    @SuppressLint("MissingPermission") // checkSelfPermission above, SecurityException below.
    fun callScreenOpener(@ApplicationContext context: Context): CallScreenOpener = CallScreenOpener {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            false
        } else {
            val telecom = context.getSystemService(TelecomManager::class.java)
            if (telecom == null) {
                false
            } else {
                try {
                    telecom.showInCallScreen(false)
                    true
                } catch (_: SecurityException) {
                    false
                }
            }
        }
    }
}
