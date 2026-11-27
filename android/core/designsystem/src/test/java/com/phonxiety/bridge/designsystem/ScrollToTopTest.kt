package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scroll-to-top registry's promises: a dispatch reaches the screen registered for that
 * destination and nothing else; a screen that leaves takes its action with it; two hosts never see
 * each other's registrations; and a later registration of the same destination wins.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScrollToTopTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aDispatchRunsOnlyTheDestinationItNames() {
        val registry = ScrollToTopRegistry()
        var calls = 0
        var others = 0
        registry.register(DESTINATION) { calls++ }
        registry.register("other") { others++ }

        registry.dispatch(DESTINATION)
        registry.dispatch("nobody")
        composeRule.runOnIdle {
            assertEquals(1, calls)
            assertEquals(0, others)
        }
    }

    @Test
    fun aDisposedScreenTakesItsRegistrationWithIt() {
        val registry = ScrollToTopRegistry()
        var visible by mutableStateOf(true)
        var calls = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalScrollToTop provides registry) {
                if (visible) RegisterScrollToTop(DESTINATION) { calls++ }
            }
        }
        composeRule.runOnIdle { registry.dispatch(DESTINATION) }
        composeRule.runOnIdle { assertEquals(1, calls) }

        visible = false
        composeRule.waitForIdle()
        composeRule.runOnIdle { registry.dispatch(DESTINATION) }
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun twoHostsHaveSeparateRegistries() {
        val first = ScrollToTopRegistry()
        val second = ScrollToTopRegistry()
        var firstCalls = 0
        var secondCalls = 0
        composeRule.setContent {
            Column {
                CompositionLocalProvider(LocalScrollToTop provides first) {
                    RegisterScrollToTop(DESTINATION) { firstCalls++ }
                }
                CompositionLocalProvider(LocalScrollToTop provides second) {
                    RegisterScrollToTop(DESTINATION) { secondCalls++ }
                }
            }
        }
        composeRule.runOnIdle {
            first.dispatch(DESTINATION)
            assertEquals(1, firstCalls)
            assertEquals(0, secondCalls)
            second.dispatch(DESTINATION)
            assertEquals(1, firstCalls)
            assertEquals(1, secondCalls)
        }
    }

    @Test
    fun aSecondRegistrationOfTheSameDestinationReplacesTheFirst() {
        val registry = ScrollToTopRegistry()
        var staleCalls = 0
        var currentCalls = 0
        var staleVisible by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalScrollToTop provides registry) {
                Column {
                    if (staleVisible) RegisterScrollToTop(DESTINATION) { staleCalls++ }
                    RegisterScrollToTop(DESTINATION) { currentCalls++ }
                }
            }
        }
        composeRule.runOnIdle { registry.dispatch(DESTINATION) }
        composeRule.runOnIdle {
            assertEquals(0, staleCalls)
            assertEquals(1, currentCalls)
        }

        // The replaced screen leaving must not remove the action that replaced it.
        staleVisible = false
        composeRule.waitForIdle()
        composeRule.runOnIdle { registry.dispatch(DESTINATION) }
        composeRule.runOnIdle {
            assertEquals(0, staleCalls)
            assertEquals(2, currentCalls)
        }
    }

    @Test
    fun registrationsSharingOneCallbackUnregisterIndependently() {
        val registry = ScrollToTopRegistry()
        var calls = 0
        val shared: () -> Unit = { calls++ }
        val first = registry.register("one", shared)
        registry.register("two", shared)

        registry.unregister(first)
        registry.dispatch("two")

        assertEquals(1, calls)
    }

    /**
     * The same callback twice at one destination: disposing the first handle leaves the replacement
     * registered, and only the second handle removes it. An identity-based registry drops both on
     * the first unregister.
     */
    @Test
    fun theSameCallbackTwiceAtOneDestinationSurvivesTheFirstUnregister() {
        val registry = ScrollToTopRegistry()
        var calls = 0
        val shared: () -> Unit = { calls++ }
        val first = registry.register(DESTINATION, shared)
        val second = registry.register(DESTINATION, shared)

        registry.unregister(first)
        registry.dispatch(DESTINATION)
        assertEquals(1, calls)

        registry.unregister(second)
        registry.dispatch(DESTINATION)
        assertEquals(1, calls)
    }

    @Test
    fun aFreshRegistryHasNothingToDispatch() {
        val registry = ScrollToTopRegistry()
        var calls = 0

        registry.dispatch(DESTINATION)

        assertEquals(0, calls)
    }

    @Test
    fun aCallbackChangeInAReplacedScreenCannotReclaimTheDestination() {
        val registry = ScrollToTopRegistry()
        var version by mutableStateOf(0)
        var staleCalls = 0
        var currentCalls = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalScrollToTop provides registry) {
                Column {
                    val callback: () -> Unit = remember(version) {
                        { staleCalls++ }
                    }
                    RegisterScrollToTop(DESTINATION, callback)
                    RegisterScrollToTop(DESTINATION) { currentCalls++ }
                }
            }
        }
        version = 1
        composeRule.waitForIdle()
        composeRule.runOnIdle { registry.dispatch(DESTINATION) }
        composeRule.runOnIdle {
            assertEquals(0, staleCalls)
            assertEquals(1, currentCalls)
        }
    }

    @Test
    fun aCallbackChangeIsUsedWithoutReregistering() {
        val registry = ScrollToTopRegistry()
        var version by mutableStateOf(0)
        var seen = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalScrollToTop provides registry) {
                val callback: () -> Unit = remember(version) {
                    val capturedVersion = version
                    { seen = capturedVersion }
                }
                RegisterScrollToTop(DESTINATION, callback)
            }
        }
        version = 1
        composeRule.waitForIdle()
        composeRule.runOnIdle { registry.dispatch(DESTINATION) }
        composeRule.runOnIdle { assertEquals(1, seen) }
    }

    @Test
    fun withoutAProviderRegistrationIsASilentNoOp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val original = context.applicationInfo.flags
        context.applicationInfo.flags = original or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        try {
            var calls = 0
            composeRule.setContent { RegisterScrollToTop(DESTINATION) { calls++ } }
            composeRule.runOnIdle { assertEquals(0, calls) }
        } finally {
            context.applicationInfo.flags = original
        }
    }

    private companion object {
        const val DESTINATION = "phonxiety:screen:calls"
    }
}
