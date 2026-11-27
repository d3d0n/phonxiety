package com.phonxiety.bridge.feature.livecall

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a failure card says and does. A failed hang-up describes a call that is still up, so the
 * card keeps that wording and offers the hang-up again; the generic retry, which only reloads the
 * record, belongs to every other failure. The phone's own call screen is offered while it is
 * available and never in place of the hang-up's own copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveCallErrorCopyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun text(id: Int): String = resources.getString(id)

    @Test
    fun aHangUpFailureWithoutTheCallScreenKeepsItsCopyAndHangsUpAgain() {
        var hangUps = 0
        var reloads = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box {
                    LiveCallError(
                        message = UiText.Literal("the daemon is not reachable"),
                        code = "link_down",
                        kind = FailureKind.TRANSPORT,
                        onReload = { reloads++ },
                        hangUpFailed = true,
                        onRetryHangUp = { hangUps++ },
                        onOpenCallScreen = null
                    )
                }
            }
        }

        composeRule.onNodeWithText(text(R.string.live_call_hang_up_failed_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.live_call_action_failed_title)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.live_call_retry)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.live_call_open_call_screen)).assertDoesNotExist()

        composeRule.onNodeWithText(text(R.string.live_call_hang_up)).performClick()
        assertEquals("the action retries the hang-up", 1, hangUps)
        assertEquals("and never the generic reload", 0, reloads)
    }

    @Test
    fun aHangUpFailureOffersTheCallScreenWhileItIsAvailable() {
        var hangUps = 0
        var screens = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box {
                    LiveCallError(
                        message = UiText.Literal("the daemon refused"),
                        code = "forbidden",
                        kind = FailureKind.REFUSED,
                        onReload = {},
                        hangUpFailed = true,
                        onRetryHangUp = { hangUps++ },
                        onOpenCallScreen = { screens++ }
                    )
                }
            }
        }

        composeRule.onNodeWithText(text(R.string.live_call_hang_up_failed_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.live_call_open_call_screen)).performClick()
        assertEquals(1, screens)
        assertEquals("the platform is the first offer", 0, hangUps)
    }

    @Test
    fun aTransportFailureKeepsTheGenericRetry() {
        var reloads = 0
        var hangUps = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box {
                    LiveCallError(
                        message = UiText.Literal("the link dropped"),
                        code = "link_down",
                        kind = FailureKind.TRANSPORT,
                        onReload = { reloads++ },
                        hangUpFailed = false,
                        onRetryHangUp = { hangUps++ },
                        onOpenCallScreen = null
                    )
                }
            }
        }

        composeRule.onNodeWithText(text(R.string.live_call_action_failed_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.live_call_hang_up_failed_title)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.live_call_retry)).performClick()
        assertEquals(1, reloads)
        assertEquals(0, hangUps)
    }

    @Test
    fun aRefusedActionIsExplainedWithoutARetry() {
        var reloads = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box {
                    LiveCallError(
                        message = UiText.Literal("the daemon refused"),
                        code = "bad_request",
                        kind = FailureKind.REFUSED,
                        onReload = { reloads++ },
                        hangUpFailed = false,
                        onRetryHangUp = {},
                        onOpenCallScreen = null
                    )
                }
            }
        }

        composeRule.onNodeWithText(text(R.string.live_call_action_failed_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.live_call_retry)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, reloads) }
    }
}
