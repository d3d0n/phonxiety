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
import org.junit.Assert.assertTrue
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

        assertEquals(JetBrainsMonoFamily, coded.fontFamily)
        assertEquals("tnum", coded.fontFeatureSettings)
        assertEquals(base.fontSize, coded.fontSize)
        assertEquals(base.fontWeight, coded.fontWeight)
        assertEquals(base.color, coded.color)
    }

    @Test
    fun withoutTabularFiguresTheCallersFeaturesStay() {
        val base = TextStyle(fontSize = 13.sp)
        val coded = PhonxietyType().code(base)

        assertEquals(JetBrainsMonoFamily, coded.fontFamily)
        assertNull(coded.fontFeatureSettings)
    }

    @Test
    fun headingsUseChakraPetchAndTextUsesJetBrainsMono() {
        val type = PhonxietyTypography
        listOf(
            type.displayLarge,
            type.displayMedium,
            type.displaySmall,
            type.headlineLarge,
            type.headlineMedium,
            type.headlineSmall,
            type.titleLarge,
            type.titleMedium,
            type.titleSmall
        ).forEach { style ->
            assertEquals(BrandHeadingFamily, style.fontFamily)
            assertEquals(FontWeight.SemiBold, style.fontWeight)
        }
        listOf(
            type.bodyLarge,
            type.bodyMedium,
            type.bodySmall,
            type.labelLarge,
            type.labelMedium,
            type.labelSmall
        ).forEach { style ->
            assertEquals(JetBrainsMonoFamily, style.fontFamily)
        }
        assertEquals(15.sp, type.bodyLarge.fontSize)
        assertTrue(type.bodyLarge.lineHeight.value >= type.bodyLarge.fontSize.value * 1.6f)
    }

    @Test
    fun theCaptionIsTwelveSpWithWideTracking() {
        val caption = PhonxietyType().caption
        assertEquals(JetBrainsMonoFamily, caption.fontFamily)
        assertEquals(12.sp, caption.fontSize)
        assertTrue(caption.letterSpacing.value >= 2f)
    }

    @Test
    fun theProvidedTokenPointsCodeAtJetBrainsMono() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = true, dynamicColor = false) {
                assertEquals(JetBrainsMonoFamily, LocalPhonxietyType.current.codeFamily)
            }
        }
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
