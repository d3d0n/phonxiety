package com.phonxiety.bridge.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Brand colour roles the arrow cannot take from the Material scheme: Signal is the mark on dark
 * backgrounds and its darkened twin on light ones, and the glow exists only on dark.
 */
@Immutable
internal class BrandTokens internal constructor(val dark: Boolean) {
    /** The mark: Signal on dark, the light-background Signal on Paper. */
    val mark: Color = if (dark) PhonxietyColors.Signal else PhonxietyColors.SignalOnLight

    /** Whether the mark may carry its glow. Light backgrounds never do. */
    val glowAllowed: Boolean = dark

    /** The wordmark colour: Trace on dark, the kit's ink on light. */
    internal val wordmark: Int =
        if (dark) R.drawable.brand_wordmark_trace else R.drawable.brand_wordmark_ink

    internal companion object {
        val Dark = BrandTokens(dark = true)
        val Light = BrandTokens(dark = false)
    }
}

/** The brand token set of the current theme; [PhonxietyTheme] provides it. */
internal val LocalBrand = staticCompositionLocalOf { BrandTokens.Light }

/** The mark's minimum drawn size, from the kit's rule for the sign. */
val BrandMarkMinSize: Dp = 16.dp

/** The wordmark's minimum height, from the kit's rule for the word. */
val BrandWordMinHeight: Dp = 18.dp

/**
 * The arrow, in the kit's 140-unit box: a diagonal from the lower left to the upper right and a
 * corner bracket, both with the mark's butt ends and miter join. Never recoloured, rotated or
 * stretched: only its size changes, and never below [BrandMarkMinSize].
 */
internal object BrandArrow {
    /** The kit's path, translated by the same (−30, −30) as the SVG group. */
    const val VIEWPORT = 140f
    const val STROKE = 24f
    private const val SHIFT = 30f

    private val diagonalStart = Offset(48f - SHIFT, 152f - SHIFT)
    private val diagonalEnd = Offset(150f - SHIFT, 50f - SHIFT)
    private val bracketStart = Offset(76f - SHIFT, 50f - SHIFT)
    private val bracketCorner = Offset(150f - SHIFT, 50f - SHIFT)
    private val bracketEnd = Offset(150f - SHIFT, 124f - SHIFT)

    /** Draws the mark into [side] square units at [origin], with an optional glow behind it. */
    fun DrawScope.drawMark(origin: Offset, side: Float, color: Color, glow: Boolean) {
        val scale = side / VIEWPORT
        val stroke = STROKE * scale
        val path = Path().apply {
            moveTo(origin.x + diagonalStart.x * scale, origin.y + diagonalStart.y * scale)
            lineTo(origin.x + diagonalEnd.x * scale, origin.y + diagonalEnd.y * scale)
            moveTo(origin.x + bracketStart.x * scale, origin.y + bracketStart.y * scale)
            lineTo(origin.x + bracketCorner.x * scale, origin.y + bracketCorner.y * scale)
            lineTo(origin.x + bracketEnd.x * scale, origin.y + bracketEnd.y * scale)
        }
        if (glow) {
            // The brand's glow: three widening passes of the mark itself, never a colour of its own.
            listOf(2.4f to 0.10f, 1.5f to 0.14f, 0.9f to 0.18f).forEach { (width, alpha) ->
                drawPath(
                    path = path,
                    color = color.copy(alpha = alpha),
                    style = Stroke(width = stroke * width, cap = StrokeCap.Butt, join = StrokeJoin.Miter)
                )
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Butt, join = StrokeJoin.Miter)
        )
    }
}
