package com.phonxiety.bridge.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.ImeAction
import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.designsystem.LocalScrollToTop
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.ScrollToTopRegistry
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.FailureKind
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

    @Test
    fun theSectionsReTapBringsTheTopBack() {
        val registry = ScrollToTopRegistry()
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalScrollToTop provides registry) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        SettingsScreen(state = state())
                    }
                }
            }
        }
        val top = composeRule.onNodeWithText(CONNECTION_CARD_SUBTITLE)
        composeRule.onNodeWithText("Revert everything").performScrollTo()
        composeRule.waitForIdle()
        top.assertIsNotDisplayed()

        composeRule.runOnIdle { registry.dispatch("settings") }
        composeRule.waitForIdle()
        top.assertIsDisplayed()
    }

    @Test
    fun everyFormFieldDeclaresTheKeyboardActionTheFormNeeds() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(state = state())
                }
            }
        }
        field("Ring timeout").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
        field("Gemini model").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
        // A multiline field keeps Enter for its newline instead of jumping onward.
        field("Daemon relay URLs, one per line").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
    }

    @Test
    fun theLastFieldClosesTheFormWhenItsDoneIsPressed() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(state = state())
                }
            }
        }
        val last = field("Gemini model").performScrollTo()
        last.performClick()
        last.assertIsFocused()

        composeRule.runOnIdle {
            // The field's semantics action is the IME's own action; invoking it runs Done.
            last.fetchSemanticsNode().config[SemanticsActions.OnImeAction].action?.invoke()
        }
        composeRule.waitForIdle()
        last.assertIsNotFocused()
    }

    @Test
    fun retryingAFailedSaveSavesTheKeptEditsInsteadOfReloading() {
        var saves = 0
        var reloads = 0
        val edited = state().let {
            it.copy(
                daemon = it.daemon.copy(
                    form = it.daemon.form.copy(ringTimeout = "47"),
                    error = UiText.Literal("the daemon is unreachable"),
                    errorKind = FailureKind.TRANSPORT,
                    errorOnSave = true
                )
            )
        }
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        state = edited,
                        callbacks = SettingsCallbacks(
                            onSaveDaemon = { saves++ },
                            onRefreshDaemon = { reloads++ }
                        )
                    )
                }
            }
        }

        composeRule.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()

        assertEquals("a failed save is retried as a save", 1, saves)
        assertEquals("a failed save does not throw the edits away", 0, reloads)
        composeRule.onNodeWithText("47").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun retryingAFailedReadReloadsTheDaemonValues() {
        var saves = 0
        var reloads = 0
        val unread = state().let {
            it.copy(
                daemon = it.daemon.copy(
                    error = UiText.Literal("the daemon is unreachable"),
                    errorKind = FailureKind.TRANSPORT,
                    errorOnSave = false
                )
            )
        }
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        state = unread,
                        callbacks = SettingsCallbacks(
                            onSaveDaemon = { saves++ },
                            onRefreshDaemon = { reloads++ }
                        )
                    )
                }
            }
        }

        composeRule
            .onNode(
                hasText("Reload from the daemon") and
                    hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            )
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, reloads)
        assertEquals(0, saves)
    }

    private fun field(label: String) = composeRule.onNode(hasSetTextAction() and hasText(label))

    private companion object {
        const val CONNECTION_CARD_SUBTITLE =
            "Which transport reaches the daemon, and which relays the phone's iroh endpoint uses"
    }
}
