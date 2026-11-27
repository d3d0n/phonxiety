package com.phonxiety.bridge

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.feature.onboarding.SetupPreferences
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The re-tap contract through the real host and the real dashboard: the section root registers its
 * own scroll action with the registry the host installs, and tapping the active Home tab runs it.
 * The test holds no registry, so the list only moves if the host provided its own
 * [com.phonxiety.bridge.designsystem.LocalScrollToTop], the dashboard registered against it, and
 * the navigator dispatched the section's key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostScrollDispatchTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun reTappingTheActiveHomeTabBringsTheRealDashboardBackToTheTop() {
        SetupPreferences(context).ready = true
        val config = File(context.createDeviceProtectedStorageContext().filesDir, CONFIG_FILE)
        config.parentFile?.mkdirs()
        // A pairing file makes the real dashboard render its full set of cards, which is taller
        // than the viewport and therefore has somewhere to scroll back from.
        config.writeText(PAIRED_CONFIG)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.settleHost()

                composeRule.onAllNodes(hasScrollAction()).onFirst()
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 4_000f) }
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Bridge control").assertIsNotDisplayed()

                composeRule.onNode(hasText("Home") and hasClickAction()).performClick()

                composeRule.waitUntil(SCROLL_BACK_MS) {
                    runCatching { composeRule.onNodeWithText("Bridge control").assertIsDisplayed() }.isSuccess
                }
                composeRule.onNodeWithText("Bridge control").assertIsDisplayed()
            }
        } finally {
            config.delete()
        }
    }

    private companion object {
        const val CONFIG_FILE = "config.json"
        const val PAIRED_CONFIG = """{"url":"wss://127.0.0.1:18763/device","token":"dashboard-scroll-test"}"""
        const val SCROLL_BACK_MS = 5_000L
    }
}
