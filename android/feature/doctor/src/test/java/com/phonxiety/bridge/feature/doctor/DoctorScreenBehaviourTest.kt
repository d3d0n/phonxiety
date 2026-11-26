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
import androidx.compose.ui.test.performScrollTo
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import java.io.File
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
    }

    /**
     * The persisted zero-output reboot failure used to lose its empty argument on reload, and
     * rendering the template with no value threw while the log card was composed.
     */
    @Test
    fun aRebootFailureSavedWithoutOutputStillRendersAfterAReload() {
        val file = File.createTempFile("doctor-log", ".log").also { it.deleteOnExit() }
        val log = FileDoctorChangeLog(file)
        log.record(DoctorChangeEntry(1_796_500_000_000, DoctorLogEvent.REBOOT_FAILED, listOf("")))
        log.record(
            DoctorChangeEntry(1_796_500_001_000, DoctorLogEvent.REBOOT_FAILED, listOf("su: not found"))
        )

        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DoctorScreen(
                        state = DoctorSamples.allGreen().copy(changeLog = log.entries()),
                        callbacks = DoctorCallbacks()
                    )
                }
            }
        }

        composeRule.onNodeWithText("The reboot request failed before the phone answered.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("The reboot request failed: su: not found", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
