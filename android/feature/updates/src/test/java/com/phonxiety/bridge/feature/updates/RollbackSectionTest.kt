package com.phonxiety.bridge.feature.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.phonxiety.bridge.data.updates.RollbackPendingReason
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A pending rollback owns the next action even when its notice was dismissed: the fresh plan the
 * phone would compute now is never offered, because retrying finishes the recorded rollback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
class RollbackSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: UpdatesUiState,
        onConfirmRollback: () -> Unit = {},
        onRollback: () -> Unit = {}
    ) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    UpdatesScreen(
                        state = state,
                        onBack = {},
                        onRefresh = {},
                        onInstallApp = {},
                        onInstallModule = {},
                        onConfirmUpdate = {},
                        onDismissUpdate = {},
                        onRollback = onRollback,
                        onConfirmRollback = onConfirmRollback,
                        onDismissRollback = {},
                        onDismissRollbackRecovery = {},
                        onDismiss = {}
                    )
                }
            }
        }
    }

    /** The phone would now plan AppOnly: mounted and kept APKs are both 3, the data app is 4. */
    private fun pendingWithAppOnlyPlan(dismissed: Boolean): UpdatesUiState = UpdatesSamples.rollbackAppOnly().copy(
        rollbackRecovery = RollbackRecovery.Pending(RollbackPendingReason.REMOVAL_FAILED, null),
        rollbackRecoveryDismissed = dismissed
    )

    @Test
    fun `dismissing a pending recovery never exposes a fresh app only plan`() {
        var retries = 0
        render(pendingWithAppOnlyPlan(dismissed = true)) { retries++ }

        composeRule.onNodeWithText("Reinstall the previous app").assertDoesNotExist()
        composeRule.onNodeWithText("The APK replaced by the last update is kept in app files.", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("Finish the rollback").assertIsDisplayed().performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `a visible pending recovery hides the fresh plan as well`() {
        render(pendingWithAppOnlyPlan(dismissed = false))

        composeRule.onNodeWithText("Rollback unfinished").assertIsDisplayed()
        composeRule.onNodeWithText("Reinstall the previous app").assertDoesNotExist()
        composeRule.onNodeWithText("The APK replaced by the last update is kept in app files.", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `the app only action only asks, it never starts the downgrade itself`() {
        var asks = 0
        var confirmations = 0
        render(
            UpdatesSamples.rollbackAppOnly(),
            onConfirmRollback = { confirmations++ },
            onRollback = { asks++ }
        )

        composeRule.onNodeWithText("Reinstall the previous app").performScrollTo().performClick()

        assertEquals(1, asks)
        assertEquals(0, confirmations)
    }
}
