package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.WizardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wizard's acceptance checks that a screenshot cannot prove: a rejected paste stays editable,
 * one action per screen, whole action labels at 200 %, the reboot copy that stays true when
 * notifications are denied, and a canceled scanner that starts nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val state = mutableStateOf(OnboardingUiState())

    private fun host(
        callbacks: OnboardingCallbacks = OnboardingCallbacks(),
        fontScale: Float = 1f,
        widthDp: Int = 411
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale)
            ) {
                PhonxietyTheme(dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .testTag(CONTENT_TAG)
                            .width(widthDp.dp)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        OnboardingScreen(state = state.value, callbacks = callbacks)
                    }
                }
            }
        }
    }

    private fun show(next: OnboardingUiState) {
        state.value = next
        composeRule.waitForIdle()
    }

    private fun sample(name: String): WizardState = requireNotNull(OnboardingSamples.states[name])

    @Test
    fun aRejectedPasteStaysEditable() {
        host(
            OnboardingCallbacks(
                onPairLink = { state.value = state.value.copy(pairingError = "pair_link_invalid") },
                onDismissPairingError = { state.value = state.value.copy(pairingError = null) }
            )
        )
        show(OnboardingUiState(wizard = WizardState.NeedsPairing(OnboardingSamples.magisk)))

        composeRule.onNodeWithText("Paste pairing link").performClick()
        composeRule.onNodeWithText("Pairing link").performTextInput("phonxiety://pair?broken")
        composeRule.onNodeWithText("Pair").performClick()

        // The sheet's field and the card behind it both carry the sentence; either is enough.
        composeRule
            .onAllNodesWithText("That is not a pairing link. Copy it again from phonxiety pair.")
            .onFirst()
            .assertExists()
        composeRule.onAllNodesWithText("phonxiety://pair?broken").onFirst().assertExists()

        // Editing clears the rejection and keeps what the person typed.
        composeRule.onAllNodesWithText("phonxiety://pair?broken").onFirst().performTextInput("2")
        composeRule.onAllNodesWithText("phonxiety://pair?broken2").onFirst().assertExists()
        composeRule
            .onAllNodesWithText("That is not a pairing link. Copy it again from phonxiety pair.")
            .assertCountEquals(0)
    }

    @Test
    fun aRejectedLinkKeepsItsRawCodeForDetails() {
        host()
        show(
            OnboardingUiState(
                wizard = WizardState.NeedsPairing(OnboardingSamples.magisk),
                pairingError = "pair_link_invalid"
            )
        )
        composeRule.onNodeWithText("The pairing was rejected").assertExists()
        composeRule.onNodeWithText("Show details").performClick()
        composeRule.onNodeWithText("pair_link_invalid").assertExists()
    }

    @Test
    fun everyScreenOffersOneActionPerVerb() {
        host()
        val expected = listOf(
            "install_failed" to "Retry",
            "doctor" to "Open Doctor",
            "verify_failed" to "Open Doctor",
            "indeterminate" to "Check again",
            "conflict" to "Check again",
            "reboot_pending" to "Reboot now",
            "reboot_pending" to "Later",
            "needs_pairing" to "Scan QR code",
            "needs_pairing" to "Paste pairing link"
        )
        expected.forEach { (stateName, label) ->
            show(OnboardingUiState(wizard = sample(stateName)))
            val nodes = composeRule.onAllNodesWithText(label).fetchSemanticsNodes()
            assertEquals("$stateName must show one \"$label\"", 1, nodes.size)
        }
    }

    @Test
    fun aPassingVerifyOffersNoDoctorAction() {
        host()
        show(OnboardingUiState(wizard = sample("verify")))
        composeRule.onNodeWithText("Open Doctor").assertDoesNotExist()
    }

    @Test
    fun actionLabelsAreWholeAtTwoHundredPercent() {
        host(fontScale = 2f, widthDp = 320)
        val container = contentContainer()
        val labels = linkedMapOf(
            "ready_to_install" to "Install module",
            "provider_prep" to "Install metamodule",
            "root_denied_kernelsu" to "Open root manager",
            "needs_pairing" to "Scan QR code",
            "reboot_pending" to "Reboot now",
            "needs_runtime" to "Grant permissions",
            "ready" to "Open dashboard"
        )
        labels.forEach { (stateName, label) ->
            show(OnboardingUiState(wizard = sample(stateName)))
            assertWholeLabel(
                label,
                composeRule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode(),
                container
            )
        }
    }

    /**
     * The check above is only worth its false. A label laid out with room it does not have keeps its
     * text, has no ellipsis and a layout no taller than the node, so nothing but the glyph extents
     * can tell it apart from a whole one.
     */
    @Test
    fun aHorizontallyClippedLabelIsRejected() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .testTag(CONTENT_TAG)
                        .width(320.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Text(
                        text = "Scan QR code or paste the pairing link",
                        modifier = Modifier.width(120.dp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
        val failure = runCatching {
            assertWholeLabel(
                "clipped fixture",
                composeRule
                    .onNodeWithText("Scan QR code or paste the pairing link", useUnmergedTree = true)
                    .fetchSemanticsNode(),
                contentContainer()
            )
        }.exceptionOrNull()
        assertTrue("a horizontally clipped label must fail the check, got $failure", failure is AssertionError)
    }

    @Test
    fun aPastedLinkSurvivesAResumeProbe() {
        host()
        show(OnboardingUiState(wizard = sample("needs_pairing")))
        composeRule.onNodeWithText("Paste pairing link").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction())
            .performTextInput("phonxiety://pair?token=abc")
        composeRule.waitForIdle()

        // Coming back from another app probes the device: "Checking" is transient and is not proof
        // that the link was accepted, so the text stays where the person left it.
        show(OnboardingUiState(wizard = WizardState.Checking))
        composeRule.onNodeWithText("phonxiety://pair?token=abc").assertExists()
        show(OnboardingUiState(wizard = sample("needs_pairing")))
        composeRule.onNodeWithText("phonxiety://pair?token=abc").assertExists()

        // A stored pairing does close the sheet and clear the field.
        show(OnboardingUiState(wizard = sample("first_link_test"), pairingConfirmed = true))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("phonxiety://pair?token=abc").assertDoesNotExist()
    }

    @Test
    fun aRejectionAtAnotherStepKeepsItsWords() {
        var dismissed = 0
        host(OnboardingCallbacks(onDismissPairingError = { dismissed++ }))
        // A pairing deep link opened while the wizard is at "No root": the pairing step's inline
        // error is not on screen, so the note is what keeps the rejection in the person's words.
        show(OnboardingUiState(wizard = sample("no_root"), pairingError = "pair_link_invalid"))
        composeRule.onNodeWithText("The pairing was rejected").assertExists()
        composeRule
            .onNodeWithText("That is not a pairing link. Copy it again from phonxiety pair.")
            .assertExists()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun theFirstLinkTestCopyCoversBothDeadlines() {
        host()
        show(OnboardingUiState(wizard = sample("first_link_test")))
        // Waiting for the link and the request that follows are separate deadlines.
        composeRule.onNodeWithText("30 s", substring = true).assertExists()
        composeRule.onNodeWithText("25 s", substring = true).assertExists()
        composeRule.onNodeWithText("up to 25 seconds", substring = true).assertDoesNotExist()
    }

    @Test
    fun theRebootCopyNeverPromisesAnAutomaticResume() {
        host()
        listOf("reboot_pending", "reboot_pending_metamodule").forEach { stateName ->
            show(OnboardingUiState(wizard = sample(stateName)))
            composeRule
                .onNodeWithText("After the reboot, open Phonxiety", substring = true)
                .assertExists()
            composeRule.onNodeWithText("by itself", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun aCanceledScannerStartsNoCameraFallback() {
        var scans = 0
        var cameras = 0
        host(OnboardingCallbacks(onScanQr = { scans++ }, onCameraScan = { cameras++ }))
        show(OnboardingUiState(wizard = WizardState.NeedsPairing(OnboardingSamples.magisk)))
        // The page's own scan action: a scanner that returns nothing must start nothing else.
        composeRule.onNodeWithText("Scan QR code").performClick()

        assertEquals(1, scans)
        assertEquals(0, cameras)
    }

    @Test
    fun theMetamoduleIsNamedAndItsDownloadShowsProgress() {
        host()
        show(
            OnboardingUiState(
                wizard = WizardState.Installing(
                    provider = OnboardingSamples.kernelSu,
                    operation = InstallOperation.PREPARE_METAMODULE,
                    module = ModuleState.Absent
                ),
                installPhase = SetupInstallPhase.Downloading(bytes = 512_000, total = 1_048_576)
            )
        )
        composeRule.onNodeWithText("Downloading meta-overlayfs").assertExists()
        composeRule.onNodeWithText("Installing the metamodule").assertExists()
        composeRule.onNodeWithText("Installing Phonxiety").assertDoesNotExist()
    }

    @Test
    fun theProviderVersionDropsAPatchsBuildHash() {
        host()
        val apatch = OnboardingSamples.apatch.copy(version = "e8e4651 (11282)")
        show(OnboardingUiState(wizard = WizardState.ReadyToInstall(apatch)))
        // The subtitle and the provider row both name it; the hash must be gone from both.
        composeRule.onAllNodesWithText("APatch 11282").onFirst().assertExists()
        composeRule.onNodeWithText("e8e4651", substring = true).assertDoesNotExist()
    }

    @Test
    fun aProviderWithoutAVersionIsNamedWithoutANumber() {
        host()
        val apatch = OnboardingSamples.apatch.copy(version = "e8e4651")
        show(OnboardingUiState(wizard = WizardState.ReadyToInstall(apatch)))
        composeRule.onAllNodesWithText("APatch").onFirst().assertExists()
    }

    @Test
    fun thePairingFieldAsksForNoCorrectionsAndSubmitsFromTheKeyboard() {
        val pairs = mutableListOf<String>()
        host(OnboardingCallbacks(onPairLink = { pairs += it }))
        show(OnboardingUiState(wizard = sample("needs_pairing")))
        composeRule.onNodeWithText("Paste pairing link").performClick()
        composeRule.waitForIdle()

        val field = composeRule.onNode(hasSetTextAction())
        field.assert(hasImeAction(ImeAction.Done))
        field.performTextInput("phonxiety://pair?token=abc")
        field.performImeAction()

        assertEquals(listOf("phonxiety://pair?token=abc"), pairs)
        // The IME action and the field's own options are one decision, so both are asserted here.
        assertEquals(KeyboardCapitalization.None, PAIRING_FIELD_KEYBOARD.capitalization)
        assertEquals(false, PAIRING_FIELD_KEYBOARD.autoCorrectEnabled)
        assertEquals(KeyboardType.Uri, PAIRING_FIELD_KEYBOARD.keyboardType)
    }

    @Test
    fun thePermissionConstantsSplitOnlyAfterTheirUnderscores() {
        val segments = constantSegments("CAPTURE_AUDIO_OUTPUT")

        assertEquals(listOf("CAPTURE_", "AUDIO_", "OUTPUT"), segments)
        assertEquals("no character is inserted or lost", "CAPTURE_AUDIO_OUTPUT", segments.joinToString(""))
    }

    /**
     * At 320 dp with 200 % text the longest constant needs three lines. A break inside a word is
     * what the old screen did (`CAPTURE_AUDIO_OU` / `TPUT`); each `_`-terminated piece is its own
     * text run, so the only boundary is an underscore.
     */
    @Test
    fun thePermissionConstantsBreakOnlyAfterTheirUnderscores() {
        host(fontScale = 2f, widthDp = 320)
        show(OnboardingUiState(wizard = sample("ready_to_install")))
        val constant = "START_FOREGROUND_SERVICES_FROM_BACKGROUND"
        val node = composeRule.onNodeWithText(constant).performScrollTo().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)

        val nodeText = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals(constant, nodeText)
        // The published layout is the rendered runs themselves: no display-only characters, so
        // character bounds and text navigation stay on the node's own offsets.
        assertEquals("the layout has to describe exactly the node's characters", nodeText, results.joinToString("") { it.layoutInput.text.text })

        val lines = results.flatMap { result ->
            (0 until result.lineCount).map { line ->
                result.layoutInput.text.text.substring(result.getLineStart(line), result.getLineEnd(line))
            }
        }
        assertTrue("the constant has to wrap at this size", lines.size > 1)
        lines.dropLast(1).forEach { line ->
            assertTrue("line breaks mid-word: '$line'", line.trimEnd().endsWith("_"))
        }
    }

    private fun contentContainer(): Rect = composeRule.onNodeWithTag(CONTENT_TAG).fetchSemanticsNode().boundsInRoot

    /**
     * Proves the label's glyphs sit inside the label's own text bounds and inside the content
     * container. `TextLayoutResult.size` cannot: it is the constrained size, and Compose lays a
     * centred button's paragraph out at the full available width, so a clipped line still fills it
     * while its glyphs run past the edge. What carries the evidence is where each line's glyphs
     * actually start and end: a line laid out with room it does not have is wider than the label's
     * visible bounds, and its right edge crosses the paragraph it was laid out in.
     */
    private fun assertWholeLabel(label: String, node: SemanticsNode, container: Rect) {
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action) {
            "$label has no text layout"
        }.invoke(layouts)
        val layout = layouts.single()
        assertFalse("$label overflows its height at 200%", layout.didOverflowHeight)
        assertTrue(
            "$label is taller than its visible bounds: ${layout.size} vs ${node.size}",
            layout.size.height <= node.size.height
        )

        val visibleWidth = node.size.width.toFloat()
        val paragraphWidth = layout.multiParagraph.width
        var glyphLeft = Float.MAX_VALUE
        var glyphRight = -Float.MAX_VALUE
        layout.layoutInput.text.indices.forEach { offset ->
            val box = layout.getBoundingBox(offset)
            if (box.width > 0f && box.height > 0f) {
                glyphLeft = minOf(glyphLeft, box.left)
                glyphRight = maxOf(glyphRight, box.right)
            }
        }
        assertTrue(
            "$label is ${glyphRight - glyphLeft}px of glyphs, wider than its ${visibleWidth}px text bounds",
            glyphRight - glyphLeft <= visibleWidth + TOLERANCE
        )

        (0 until layout.lineCount).forEach { line ->
            assertFalse("$label is ellipsized on line $line", layout.isLineEllipsized(line))
            val left = layout.getLineLeft(line)
            val right = layout.getLineRight(line)
            val width = layout.multiParagraph.getLineWidth(line)
            assertTrue(
                "$label line $line is ${width}px wide, wider than its ${visibleWidth}px text bounds",
                width <= visibleWidth + TOLERANCE
            )
            assertTrue(
                "$label line $line starts at ${left}px, left of the $paragraphWidth px paragraph",
                left >= -TOLERANCE
            )
            assertTrue(
                "$label line $line ends at ${right}px, past the $paragraphWidth px paragraph",
                right <= paragraphWidth + TOLERANCE
            )
        }

        val bounds = node.boundsInRoot
        assertTrue(
            "$label sits outside the content container: $bounds in $container",
            bounds.left >= container.left && bounds.top >= container.top &&
                bounds.right <= container.right && bounds.bottom <= container.bottom
        )
    }

    private companion object {
        const val CONTENT_TAG = "onboarding_content"

        /** Sub-pixel rounding in glyph boxes is not a clipped label. */
        const val TOLERANCE = 0.5f
    }
}
