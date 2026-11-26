package com.phonxiety.bridge.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the settings screen has to do: a whole row toggles, chips select, invalid numbers block saving. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenBehaviourTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun state(): SettingsUiState = previewSettingsState().let { state ->
        state.copy(
            phone = state.phone.copy(
                wifiLowLatency = false,
                thermalAwareness = false,
                batteryAwareness = false
            )
        )
    }

    @Test
    fun theWholeToggleRowTogglesItsSetting() {
        val toggles = mutableListOf<Boolean>()
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        state = state(),
                        callbacks = SettingsCallbacks(onBatteryAwareness = { toggles += it })
                    )
                }
            }
        }

        composeRule.onNodeWithText("Watch the battery").performScrollTo()
        composeRule.onNodeWithText("Watch the battery").assertIsOff()
        composeRule.onNodeWithText("Watch the battery").performClick()

        assertEquals(listOf(true), toggles)
    }

    @Test
    fun aSelectedChipShowsACheckAndSendsItsWireValue() {
        val codecs = mutableListOf<String>()
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(state = state(), callbacks = SettingsCallbacks(onCodec = { codecs += it }))
                }
            }
        }

        composeRule.onAllNodesWithText("PCM").onFirst().performScrollTo()
        composeRule.onAllNodesWithText("PCM").onFirst().performClick()

        assertEquals(listOf(PhoneCodec.PCM), codecs)
    }

    @Test
    fun anInvalidNumberBlocksBothSaveActionsAndSaysWhy() {
        val state = state().let {
            it.copy(daemon = it.daemon.copy(form = it.daemon.form.copy(ringTimeout = "soon")))
        }
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(state = state)
                }
            }
        }

        composeRule.onNodeWithText("Enter a whole number").performScrollTo()
        composeRule.onNodeWithText("Enter a whole number").assertIsDisplayed()
        composeRule.onNodeWithText("Not saved. Enter a whole number for the highlighted fields.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Save daemon settings").assertIsNotEnabled()
    }

    @Test
    fun anInvalidFieldCountsAsEditedAndBlocksTheSave() {
        val clean = SettingsUiState(daemon = DaemonSettingsUi())
        assertEquals(false, clean.daemon.dirty)
        assertEquals(true, clean.daemon.valid)

        val invalid = clean.copy(daemon = clean.daemon.copy(form = clean.daemon.form.copy(ringTimeout = "soon")))
        assertEquals(true, invalid.daemon.dirty)
        assertEquals(false, invalid.daemon.valid)
        assertEquals(null, invalid.daemon.form.toValuesOrNull())
    }
}
