package com.phonxiety.bridge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.feature.widgets.daemonApiOrNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The widget and the tiles have no activity, so they reach the daemon through their own Hilt
 * entry point. It is only in the app's graph when this module's Hilt processing runs; without it
 * `daemonApiOrNull` returns null and the Hang up button never asks the daemon to end a call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetEntryPointGraphTest {
    @Test
    fun theAppGraphCarriesTheWidgetEntryPoint() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        assertNotNull(
            "WidgetEntryPoint is not in the app's Hilt graph, so the surfaces cannot reach the daemon",
            daemonApiOrNull(app)
        )
    }
}
