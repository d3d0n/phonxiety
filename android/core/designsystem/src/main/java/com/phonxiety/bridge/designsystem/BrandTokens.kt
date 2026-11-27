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

    /** One widening pass of the glow: the stroke width factor and the alpha it carries. */
    private class GlowSpec(val widthFactor: Float, val alpha: Float)

    private val GLOW_SPECS = listOf(
        GlowSpec(2.4f, 0.10f),
        GlowSpec(1.5f, 0.14f),
        GlowSpec(0.9f, 0.18f)
    )

    internal class GlowStroke(val stroke: Stroke, val alpha: Float)

    /** The geometry of one mark at one size: the path and the strokes never change while drawn. */
    internal class Mark internal constructor(
        internal val path: Path,
        internal val body: Stroke,
        internal val glow: List<GlowStroke>
    )

    /**
     * Builds the mark for [side] square units at [origin]. Called from a draw cache, so the path,
     * the glow list and every stroke are allocated once per size, not once per frame.
     */
    fun mark(origin: Offset, side: Float): Mark {
        val scale = side / VIEWPORT
        val stroke = STROKE * scale
        val path = Path().apply {
            moveTo(origin.x + diagonalStart.x * scale, origin.y + diagonalStart.y * scale)
            lineTo(origin.x + diagonalEnd.x * scale, origin.y + diagonalEnd.y * scale)
            moveTo(origin.x + bracketStart.x * scale, origin.y + bracketStart.y * scale)
            lineTo(origin.x + bracketCorner.x * scale, origin.y + bracketCorner.y * scale)
            lineTo(origin.x + bracketEnd.x * scale, origin.y + bracketEnd.y * scale)
        }
        return Mark(
            path = path,
            body = Stroke(width = stroke, cap = StrokeCap.Butt, join = StrokeJoin.Miter),
            glow = GLOW_SPECS.map { spec ->
                GlowStroke(
                    stroke = Stroke(
                        width = stroke * spec.widthFactor,
                        cap = StrokeCap.Butt,
                        join = StrokeJoin.Miter
                    ),
                    alpha = spec.alpha
                )
            }
        )
    }

    /** Draws a cached [mark], with the glow passes behind it when [glow] is on. */
    fun DrawScope.drawMark(mark: Mark, color: Color, glow: Boolean) {
        if (glow) {
            // The brand's glow: three widening passes of the mark itself, never a colour of its own.
            mark.glow.forEach { pass ->
                drawPath(path = mark.path, color = color.copy(alpha = pass.alpha), style = pass.stroke)
            }
        }
        drawPath(path = mark.path, color = color, style = mark.body)
    }
}
