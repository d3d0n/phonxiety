package com.phonxiety.bridge

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.phonxiety.bridge.designsystem.RegisterScrollToTop
import com.phonxiety.bridge.designsystem.ScrollToTopRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host's registry provision, which is the only way a section's root screen hands the suite its
 * scroll-to-top action. The fake action stands in for a section root: a re-tap at the root reaches
 * it through the registry the host provides. Without a provider a registration reaches nothing,
 * which is the design system's own contract and tested there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostScrollRegistryTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun aSectionRootsRegistrationIsReachedThroughTheHostsRegistry() {
        val registry = ScrollToTopRegistry()
        var calls = 0
        compose {
            HostScrollRegistry(registry) {
                RegisterScrollToTop(Section.CALLS.scrollToTopKey) { calls++ }
            }
        }

        composeRule.runOnIdle { registry.dispatch(Section.CALLS.scrollToTopKey) }

        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    /** Composes into a bare activity, which is all a phone with no manifest entry needs here. */
    private fun compose(content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContentView(ComposeView(activity).apply { setContent(content) })
        composeRule.waitForIdle()
    }
}
