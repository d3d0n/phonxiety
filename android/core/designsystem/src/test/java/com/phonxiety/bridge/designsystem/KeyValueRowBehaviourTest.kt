package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.component.KEY_VALUE_GAP
import com.phonxiety.bridge.designsystem.component.KEY_VALUE_MIN_VALUE_WIDTH
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the label and the value of a [KeyValueRow] sit: side by side when their own widths fit,
 * stacked when they do not, at any font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class KeyValueRowBehaviourTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setRow(
        label: String,
        value: String,
        width: Dp = 379.dp,
        fontScale: Float = 1f
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PhonxietyTheme(dynamicColor = false) {
                    Box(modifier = Modifier.width(width)) {
                        KeyValueRow(label = label, value = value)
                    }
                }
            }
        }
    }

    @Test
    fun aLongValueStacksUnderALabelThatKeepsItsWidth() {
        // The device sighting: an ordinary font size, and an answer long enough to take the row.
        setRow(label = "Menu options heard", value = LONG_ANSWER)

        val label = composeRule.onNodeWithText("Menu options heard").getUnclippedBoundsInRoot()
        val value = composeRule.onNodeWithText(LONG_ANSWER).getUnclippedBoundsInRoot()

        assertTrue(
            "the label keeps a readable width instead of one letter a line",
            (label.right - label.left).value > 100f
        )
        assertTrue("the label stays one line", (label.bottom - label.top).value < 40f)
        assertEquals("the value starts at the label's edge", label.left.value, value.left.value, 1f)
        assertTrue("the value sits under the label", value.top >= label.bottom)
    }

    @Test
    fun aLongValueStacksAtTwoHundredPercentToo() {
        // The same rule is measured, not read off a font-scale breakpoint.
        setRow(label = "Menu options heard", value = LONG_ANSWER, width = 288.dp, fontScale = 2f)

        // The label wraps at this width: its own two words still keep the row's whole width.
        val label = composeRule.onNodeWithText("Menu options heard").getUnclippedBoundsInRoot()
        val value = composeRule.onNodeWithText(LONG_ANSWER).getUnclippedBoundsInRoot()

        assertTrue((label.right - label.left).value > 200f)
        assertEquals(label.left.value, value.left.value, 1f)
        assertTrue(value.top >= label.bottom)
    }

    /**
     * The boundary between the two layouts is the value's floor, not its glyphs: a value short
     * enough to share the row's text width still stacks when the floor it renders with does not
     * fit. The probe width sits between the two rules, so the old calculation would have picked
     * the row.
     */
    @Test
    fun aShortValueStacksWhenItsMinimumWidthDoesNotFit() {
        val label = "Menu options heard"
        val value = "Yes"
        var textFit = 0f
        var floorFit = 0f
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.bodyMedium
                val labelPx = measurer.measure(text = label, style = style, softWrap = false).size.width
                val valuePx = measurer.measure(text = value, style = style, softWrap = false).size.width
                val gapPx = with(density) { KEY_VALUE_GAP.roundToPx() }.toFloat()
                val floorPx = with(density) { KEY_VALUE_MIN_VALUE_WIDTH.roundToPx() }.toFloat()
                textFit = labelPx + gapPx + valuePx
                floorFit = labelPx + gapPx + floorPx
                val probePx = textFit + (floorFit - textFit) / 2f
                Box(modifier = Modifier.width(with(density) { probePx.toDp() })) {
                    KeyValueRow(label = label, value = value)
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("the probe is a real boundary: ${floorFit}px floor over ${textFit}px text", floorFit > textFit)

        val labelBounds = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
        val valueBounds = composeRule.onNodeWithText(value).getUnclippedBoundsInRoot()
        assertEquals("the value starts at the label's edge", labelBounds.left.value, valueBounds.left.value, 1f)
        assertTrue("the short value sits under the label", valueBounds.top >= labelBounds.bottom)
    }

    @Test
    fun aShortValueStaysOnTheLabelsRow() {
        setRow(label = "Codec", value = "opus", width = 288.dp, fontScale = 2f)

        val label = composeRule.onNodeWithText("Codec").getUnclippedBoundsInRoot()
        val value = composeRule.onNodeWithText("opus").getUnclippedBoundsInRoot()

        assertTrue("the value is right of the label", value.left.value > label.right.value - 1f)
        assertTrue("the value shares the label's line", value.top < label.bottom)
    }

    private companion object {
        const val LONG_ANSWER =
            "Press one for reservations, two for the kitchen, and hold for the manager after six."
    }
}
