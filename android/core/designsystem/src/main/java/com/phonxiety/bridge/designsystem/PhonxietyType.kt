package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Chakra Petch SemiBold: the name and every heading. */
internal val BrandHeadingFamily: FontFamily = FontFamily(
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold)
)

/** JetBrains Mono: body copy, captions and code. Medium carries the heavier labels. */
val JetBrainsMonoFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium)
)

/**
 * The code face: endpoint ids, numbers, timings and captured output. One token so no screen picks
 * a font family of its own. [code] keeps the caller's size, weight and colour and only swaps the
 * face; [tabular] pins the figures so a changing number never shifts its neighbours.
 */
@Immutable
class PhonxietyType(
    val codeFamily: FontFamily = JetBrainsMonoFamily,
    val tabularFigures: String = "tnum"
) {
    /** The style in the code face, optionally with tabular figures. */
    fun code(base: TextStyle, tabular: Boolean = false): TextStyle = base.copy(
        fontFamily = codeFamily,
        fontFeatureSettings = if (tabular) tabularFigures else base.fontFeatureSettings
    )

    /**
     * The caption face: 12 sp with the brand's wide tracking. The text is uppercased where it is
     * rendered, never stored that way, so a translated caption keeps its own casing rules.
     */
    val caption: TextStyle = TextStyle(
        fontFamily = codeFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.8.sp,
        letterSpacing = 2.5.sp
    )
}

/** The type token set of the current theme; [PhonxietyTheme] provides it. */
val LocalPhonxietyType = staticCompositionLocalOf { PhonxietyType() }

/**
 * Cut corners echo the arrow's butt ends and the glyph cuts of the wordmark: a surface reads as a
 * plate cut from the same sheet as the mark, not as a rounded system card.
 */
internal val PhonxietyShapes: Shapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(8.dp),
    medium = CutCornerShape(12.dp),
    large = CutCornerShape(18.dp),
    extraLarge = CutCornerShape(26.dp)
)

/** The name's tracking, applied to display and headline roles. */
private const val NAME_TRACKING = -0.016

/**
 * Branded type. Headings use Chakra Petch SemiBold with the name's −1.6 % tracking; body, labels
 * and code use JetBrains Mono. Sizes stay in sp throughout, so 200 % font scale still scales them.
 */
internal val PhonxietyTypography: Typography = Typography().let { base ->
    fun TextStyle.heading(tracking: Double): TextStyle = copy(
        fontFamily = BrandHeadingFamily,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = tracking.em
    )
    base.copy(
        displayLarge = base.displayLarge.heading(NAME_TRACKING),
        displayMedium = base.displayMedium.heading(NAME_TRACKING),
        displaySmall = base.displaySmall.heading(NAME_TRACKING),
        headlineLarge = base.headlineLarge.heading(NAME_TRACKING),
        headlineMedium = base.headlineMedium.heading(NAME_TRACKING),
        headlineSmall = base.headlineSmall.heading(NAME_TRACKING),
        titleLarge = base.titleLarge.heading(0.0),
        titleMedium = base.titleMedium.heading(0.0),
        titleSmall = base.titleSmall.heading(0.0),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 24.75.sp
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 21.45.sp
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 19.8.sp
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = JetBrainsMonoFamily,
            letterSpacing = 0.4.sp
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
            lineHeight = 19.8.sp,
            letterSpacing = 2.5.sp
        )
    )
}
