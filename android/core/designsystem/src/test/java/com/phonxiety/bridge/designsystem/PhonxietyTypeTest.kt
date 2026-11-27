package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.phonxiety.bridge.designsystem.component.StatTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The code face is one token. [PhonxietyType.code] swaps only the family (and optionally the
 * figures) and leaves the caller's size, weight and colour alone; a component that renders a
 * technical value takes the family from [LocalPhonxietyType], so a host can change the face
 * without touching a component.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PhonxietyTypeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theCodeFaceKeepsTheCallersSizeWeightAndColour() {
        val base = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Red)
        val coded = PhonxietyType().code(base, tabular = true)

        assertEquals(FontFamily.Monospace, coded.fontFamily)
        assertEquals("tnum", coded.fontFeatureSettings)
        assertEquals(base.fontSize, coded.fontSize)
        assertEquals(base.fontWeight, coded.fontWeight)
        assertEquals(base.color, coded.color)
    }

    @Test
    fun withoutTabularFiguresTheCallersFeaturesStay() {
        val base = TextStyle(fontSize = 13.sp)
        val coded = PhonxietyType().code(base)

        assertEquals(FontFamily.Monospace, coded.fontFamily)
        assertNull(coded.fontFeatureSettings)
    }

    @Test
    fun aTechnicalValueTakesItsFaceFromTheProvidedToken() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Column {
                    CompositionLocalProvider(
                        LocalPhonxietyType provides PhonxietyType(codeFamily = FontFamily.Monospace)
                    ) {
                        StatTile(
                            label = "Value",
                            value = VALUE,
                            mono = true,
                            modifier = Modifier.testTag("monospace")
                        )
                    }
                    CompositionLocalProvider(
                        LocalPhonxietyType provides PhonxietyType(codeFamily = FontFamily.Serif)
                    ) {
                        StatTile(
                            label = "Value",
                            value = VALUE,
                            mono = true,
                            modifier = Modifier.testTag("serif")
                        )
                    }
                }
            }
        }
        val monospace = composeRule.onNodeWithTag("monospace").getUnclippedBoundsInRoot()
        val serif = composeRule.onNodeWithTag("serif").getUnclippedBoundsInRoot()
        // The same string in the same style lays out differently only if the token's family reached
        // the value: the component must read the provided face, not a hard-coded one.
        assertNotEquals((monospace.right - monospace.left).value, (serif.right - serif.left).value)
    }

    private companion object {
        const val VALUE = "123456789012345678"
    }
}
