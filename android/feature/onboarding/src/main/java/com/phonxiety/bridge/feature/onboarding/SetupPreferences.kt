package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.core.content.edit

/**
 * Durable wizard facts that are not derivable from the device: whether the first link test passed
 * and whether setup finished. They survive process death and the reboot the wizard asks for, which
 * is what lets the app route itself back to the wizard until the phone is ready.
 */
class SetupPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var linkTestPassed: Boolean
        get() = prefs.getBoolean(KEY_LINK_TEST_PASSED, false)
        set(value) = prefs.edit { putBoolean(KEY_LINK_TEST_PASSED, value) }

    var ready: Boolean
        get() = prefs.getBoolean(KEY_READY, false)
        set(value) = prefs.edit { putBoolean(KEY_READY, value) }

    fun reset() {
        prefs.edit { clear() }
    }

    private companion object {
        const val FILE_NAME = "setup"
        const val KEY_LINK_TEST_PASSED = "linkTestPassed"
        const val KEY_READY = "ready"
    }
}
