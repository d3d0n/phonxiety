package com.phonxiety.bridge

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The launch icon is masked to a circle two-thirds of its 140-unit box. The arrow's stroked
 * geometry has to fit that safe circle, or the platform clips a corner of the mark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SplashIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theArrowFitsInsideTheSplashSafeCircle() {
        val vector = readVector()
        assertEquals(140f, vector.viewport, 0f)
        val halfStroke = vector.strokeWidth / 2f
        val center = vector.viewport / 2f
        // The safe circle is two-thirds of the viewport across, so its radius is a third.
        val safeRadius = vector.viewport / 3f

        val points = vector.points
        val bounds = FloatBounds(
            left = points.minOf { it.x } - halfStroke,
            top = points.minOf { it.y } - halfStroke,
            right = points.maxOf { it.x } + halfStroke,
            bottom = points.maxOf { it.y } + halfStroke
        )

        assertEquals("the arrow is not centred", center, bounds.centerX, 0.5f)
        assertEquals("the arrow is not centred", center, bounds.centerY, 0.5f)
        listOf(
            bounds.left to bounds.top,
            bounds.right to bounds.top,
            bounds.left to bounds.bottom,
            bounds.right to bounds.bottom
        ).forEach { (x, y) ->
            val distance = kotlin.math.hypot(x - center, y - center)
            assertTrue(
                "a stroke corner at ($x, $y) is $distance units from the centre, outside $safeRadius",
                distance <= safeRadius
            )
        }
    }

    private fun readVector(): Vector {
        val parser = context.resources.getXml(R.drawable.ic_splash_arrow)
        var viewport = 0f
        var strokeWidth = 0f
        val points = mutableListOf<FloatPoint>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "vector" -> viewport = parser.floatAttribute("viewportWidth")

                        "path" -> {
                            strokeWidth = maxOf(strokeWidth, parser.floatAttribute("strokeWidth"))
                            parser.attribute("pathData")?.let { points += pathPoints(it) }
                        }
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return Vector(viewport, strokeWidth, points)
    }

    /** The subset of path data the icon uses: move, line, horizontal, vertical. */
    private fun pathPoints(data: String): List<FloatPoint> {
        val tokens = TOKENS.findAll(data).map { it.value }.toList()
        val points = mutableListOf<FloatPoint>()
        var x = 0f
        var y = 0f
        var index = 0
        while (index < tokens.size) {
            when (tokens[index++]) {
                "M", "L" -> {
                    x = tokens[index++].toFloat()
                    y = tokens[index++].toFloat()
                    points += FloatPoint(x, y)
                }

                "H" -> {
                    x = tokens[index++].toFloat()
                    points += FloatPoint(x, y)
                }

                "V" -> {
                    y = tokens[index++].toFloat()
                    points += FloatPoint(x, y)
                }
            }
        }
        return points
    }

    private fun XmlResourceParser.attribute(name: String): String? = getAttributeValue(ANDROID_NS, name)

    private fun XmlResourceParser.floatAttribute(name: String): Float = attribute(name)?.toFloat() ?: 0f

    private data class Vector(val viewport: Float, val strokeWidth: Float, val points: List<FloatPoint>)

    private data class FloatPoint(val x: Float, val y: Float)

    private data class FloatBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val TOKENS = Regex("[A-Za-z]|-?\\d+(?:\\.\\d+)?")
    }
}
