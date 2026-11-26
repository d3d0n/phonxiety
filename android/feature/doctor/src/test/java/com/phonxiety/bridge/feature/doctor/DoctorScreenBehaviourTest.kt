package com.phonxiety.bridge.feature.doctor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the phone has to do, not only look like: a tapped check opens its evidence, and a repair
 * that would drop a live call stays blocked with the reason next to it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DoctorScreenBehaviourTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingACheckShowsItsDetailBelowTheGroups() {
        var state by mutableStateOf(DoctorSamples.severalFailures().copy(selected = null))
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DoctorScreen(
                        state = state,
                        callbacks = DoctorCallbacks(onSelect = { state = state.copy(selected = it) })
                    )
                }
            }
        }

        composeRule.onNodeWithText("System overlay applied").performClick()

        composeRule.onNodeWithText("Rollback").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reinstall the module: System overlay applied")
            .assertIsDisplayed()
    }

    @Test
    fun aRepairBlockedByALiveCallStaysBlocked() {
        val fixes = mutableListOf<String>()
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DoctorScreen(
                        state = DoctorSamples.duringCall(),
                        callbacks = DoctorCallbacks(onFix = { fixes += it })
                    )
                }
            }
        }

        composeRule.onAllNodesWithText("Reinstall the module").onFirst().assertIsNotEnabled()
        composeRule.onAllNodesWithText("Reinstall the module").onFirst().performClick()
        assertEquals(emptyList<String>(), fixes)
        composeRule.onAllNodesWithText("Not during a call: this would drop it.").onFirst().assertIsDisplayed()
    }

    @Test
    fun theConfirmDialogNamesTheActionAndItsRollback() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DoctorScreen(state = DoctorSamples.pendingFix(), callbacks = DoctorCallbacks())
                }
            }
        }

        composeRule.onNodeWithText("Reinstall the module?").assertIsDisplayed()
        composeRule.onNodeWithText("Reinstall the module and reboot").assertIsDisplayed()
        composeRule.onNode(isDialog()).assertAccessible()
        composeRule.onNode(isDialog())
            .captureRoboImage("src/test/screenshots/doctor_confirm_dialog.png")
    }
}
