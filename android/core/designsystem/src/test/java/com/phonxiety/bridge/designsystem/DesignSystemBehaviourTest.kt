package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LinkPath
import com.phonxiety.bridge.designsystem.component.LinkPathPill
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.designsystem.component.ToggleRow
import com.phonxiety.bridge.model.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Semantics and actions, not only pixels: these are the promises the components make. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DesignSystemBehaviourTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theWholeToggleRowIsOneSwitchTarget() {
        var checked by mutableStateOf(true)
        var changes = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ToggleRow(
                    title = "Allow background running",
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        changes++
                    },
                    detail = "Keeps the bridge alive when the screen is off."
                )
            }
        }
        val row = composeRule.onNodeWithText("Allow background running")
        row.assertIsOn()
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        row.performClick()
        composeRule.runOnIdle {
            assertFalse(checked)
            assertEquals(1, changes)
        }
    }

    @Test
    fun anUnmeasuredPathReadsUnknownAndAnnouncesNoColour() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                LinkPathPill(LinkPath.UNKNOWN)
            }
        }
        composeRule.onNodeWithText("Unknown").assertTextEquals("Unknown")
        composeRule.onNodeWithText("Unknown")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun errorCardOffersItsActionAndOptionalDismiss() {
        var action = 0
        var dismiss = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ErrorCard(
                    title = "The update failed",
                    message = "The installer refused the package.",
                    kind = FailureKind.TRANSPORT,
                    actionLabel = "Try again",
                    onAction = { action++ },
                    dismissLabel = "Dismiss",
                    onDismiss = { dismiss++ }
                )
            }
        }
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.runOnIdle {
            assertEquals(1, action)
            assertEquals(1, dismiss)
        }
    }

    @Test
    fun aTransportFailureOffersARetry() {
        var retries = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ErrorCard(
                    title = "The bridge is not answering",
                    message = "The request did not reach the daemon.",
                    kind = FailureKind.TRANSPORT,
                    onRetry = { retries++ },
                    onOpenDoctor = {}
                )
            }
        }
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun aDaemonFailureOffersTheDoctor() {
        var doctors = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ErrorCard(
                    title = "The daemon failed",
                    message = "The daemon could not answer.",
                    kind = FailureKind.DAEMON,
                    onRetry = {},
                    onOpenDoctor = { doctors++ }
                )
            }
        }
        composeRule.onNodeWithText("Open Doctor").performClick()
        composeRule.runOnIdle { assertEquals(1, doctors) }
    }

    @Test
    fun aRefusedRequestOnlyExplainsItself() {
        var actions = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ErrorCard(
                    title = "The request was refused",
                    message = "The daemon declined it.",
                    kind = FailureKind.REFUSED,
                    onRetry = { actions++ },
                    onOpenDoctor = { actions++ }
                )
            }
        }
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
        composeRule.onNodeWithText("Open Doctor").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, actions) }
    }

    @Test
    fun screenScaffoldShowsOneTitleAndALabelledBackButton() {
        var backs = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ScreenScaffold(title = "Settings", onBack = { backs++ }) {
                    Text("Body")
                }
            }
        }
        composeRule.onNodeWithText("Settings").assertTextEquals("Settings")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun busyActionExposesWorkingAndItsProgress() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                PrimaryActionButton(
                    label = "Updating…",
                    onClick = {},
                    busy = true,
                    progress = 0.4f,
                    emphasis = ActionEmphasis.PRIMARY
                )
            }
        }
        composeRule.onNodeWithText("Updating…")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Working"))
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.4f, 0f..1f)
            )
        ).assertExists()
    }

    @Test
    fun legacyErrorCardTrailingLambdaIsTheAction() {
        var retries = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ErrorCard(
                    title = "The update failed",
                    message = "The installer refused the package.",
                    kind = FailureKind.TRANSPORT,
                    actionLabel = "Retry",
                    onAction = { retries++ }
                )
            }
        }
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
        }
    }

    /** The original shapes compile and show no action: a label without a callback, or a null one. */
    @Test
    fun legacyErrorCardWithoutACallbackShowsNoAction() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Column {
                    ErrorCard("Label only", "No callback.", kind = FailureKind.TRANSPORT, actionLabel = "Retry")
                    ErrorCard("Null callback", "Explicitly none.", kind = FailureKind.TRANSPORT, onAction = null)
                }
            }
        }
        composeRule.onNodeWithText("Label only").assertExists()
        composeRule.onNodeWithText("Null callback").assertExists()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun legacyEmptyStateTrailingLambdaIsItsAction() {
        var starts = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                EmptyState("No calls yet") {
                    PrimaryActionButton(label = "Start call", onClick = { starts++ })
                }
            }
        }
        composeRule.onNodeWithText("Start call").performClick()
        composeRule.runOnIdle {
            assertEquals(1, starts)
        }
    }

    @Test
    fun lowerEmphasisActionsStillRun() {
        var clicks = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                PrimaryActionButton(
                    label = "Details",
                    onClick = { clicks++ },
                    emphasis = ActionEmphasis.TEXT
                )
            }
        }
        composeRule.onNodeWithText("Details").performClick()
        composeRule.runOnIdle {
            assertEquals(1, clicks)
        }
    }
}
