package com.phonxiety.bridge.designsystem

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType.ERROR
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType.WARNING
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import com.phonxiety.bridge.designsystem.testing.accessibilityResults
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gate keeps every contrast failure except a text sliver: a sampled ratio of an element with
 * less than half of its height on screen at a scrollable edge. A translucent bar sits at the
 * bottom of the scrolling fixtures so text cut there is genuinely misread; the bar cannot hide
 * text that is fully visible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h400dp-xxhdpi")
class AccessibilityGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun enableAccessibility() {
        val manager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
    }

    @Test
    fun aFullyVisibleLowContrastComposeLineFailsTheGate() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = false, dynamicColor = false) {
                Text(
                    text = "Low contrast Compose",
                    color = LOW_CONTRAST,
                    fontSize = 16.sp,
                    modifier = Modifier.background(Color.White)
                )
            }
        }

        val thrown = assertThrows(AccessibilityViewCheckException::class.java) {
            composeRule.onRoot().assertAccessible()
        }
        assertTrue(
            "the fully visible Compose text keeps its contrast result",
            thrown.results.any { result -> result.isContrastOf("Low contrast Compose") }
        )
    }

    @Test
    fun aFullyVisibleLowContrastNativeLineFailsTheGate() {
        showNativeLines(lines = 1, lineHeight = 60.dp, shade = LOW_CONTRAST_TEXT)

        val thrown = assertThrows(AccessibilityViewCheckException::class.java) {
            composeRule.onRoot().assertAccessible()
        }
        assertTrue(
            "the fully visible native line keeps its contrast result",
            thrown.results.any { result -> result.isContrastOf("Line 0") }
        )
    }

    @Test
    fun aComposeLineClippedAtTheEdgeButAtLeastHalfVisibleFailsTheGate() {
        showComposeLines(
            lines = 7,
            lineHeight = 60.dp,
            shade = READABLE,
            edgeShade = LOW_CONTRAST,
            wash = true
        )

        val thrown = assertThrows(AccessibilityViewCheckException::class.java) {
            composeRule.onRoot().assertAccessible()
        }
        assertTrue(
            "40 of 60 dp on screen is readable, so the clipped Compose line keeps its result",
            thrown.results.any { result -> result.isContrastOf("Line 6") }
        )
    }

    @Test
    fun aNativeLineClippedAtTheEdgeButAtLeastHalfVisibleFailsTheGate() {
        showNativeLines(
            lines = 7,
            lineHeight = 60.dp,
            shade = READABLE_TEXT,
            edgeShade = LOW_CONTRAST_TEXT,
            wash = true
        )

        val thrown = assertThrows(AccessibilityViewCheckException::class.java) {
            composeRule.onRoot().assertAccessible()
        }
        assertTrue(
            "40 of 60 dp on screen is readable, so the clipped native line keeps its result",
            thrown.results.any { result -> result.isContrastOf("Line 6") }
        )
    }

    @Test
    fun aComposeLineClippedToASliverIsSuppressed() {
        showComposeLines(
            lines = 7,
            lineHeight = 60.dp,
            lastLineHeight = 100.dp,
            shade = READABLE,
            edgeShade = LOW_CONTRAST,
            wash = true
        )
        val washedSliver = composeRule.onRoot()
        assertTrue(
            "the framework reported the Compose sliver before the rule dropped it",
            washedSliver.accessibilityResults().any { result -> result.isContrastOf("Line 6") }
        )

        composeRule.onRoot().assertAccessible()
    }

    @Test
    fun aClippedMergingContainerDoesNotHideItsFullyVisibleTextChild() {
        // A 100 dp container is clipped to its top 40 dp; the 40 dp label filling that top is on
        // screen whole. The container sits inside the scroller's width, so the framework marks the
        // label as sitting at the scrollable edge. The container merges the label's text, so
        // matching the merged tree would take the container's own 100 dp as the text's height and
        // drop the real contrast failure as a sliver.
        composeRule.setContent {
            PhonxietyTheme(darkTheme = false, dynamicColor = false) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(360.dp))
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .height(100.dp)
                            .background(Color.White)
                            .semantics(mergeDescendants = true) {}
                    ) {
                        Text(
                            text = "Low contrast child",
                            color = LOW_CONTRAST,
                            fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        )
                    }
                }
            }
        }

        val thrown = assertThrows(AccessibilityViewCheckException::class.java) {
            composeRule.onRoot().assertAccessible()
        }
        assertTrue(
            "the label is fully visible, so its contrast result is kept",
            thrown.results.any { result -> result.isContrastOf("Low contrast child") }
        )
    }

    @Test
    fun aNativeLineClippedToASliverIsSuppressed() {
        showNativeLines(
            lines = 7,
            lineHeight = 60.dp,
            lastLineHeight = 100.dp,
            shade = READABLE_TEXT,
            edgeShade = LOW_CONTRAST_TEXT,
            wash = true
        )
        val washedSliver = composeRule.onRoot()
        assertTrue(
            "the framework reported the native sliver before the rule dropped it",
            washedSliver.accessibilityResults().any { result -> result.isContrastOf("Line 6") }
        )

        composeRule.onRoot().assertAccessible()
    }

    @Test
    fun aDeclaredColourContrastFailureIsNeverSuppressed() {
        // A plain colour background is read from the view, so the ratio comes from declared
        // colours; the element is still a sliver at the scroll edge.
        showNativeLines(
            lines = 7,
            lineHeight = 60.dp,
            lastLineHeight = 100.dp,
            shade = READABLE_TEXT,
            edgeShade = LOW_CONTRAST_TEXT,
            declaredBackground = true
        )

        val thrown = assertThrows(AccessibilityViewCheckException::class.java) {
            composeRule.onRoot().assertAccessible()
        }
        assertTrue(
            "the declared-colour ratio of the sliver is kept",
            thrown.results.any { result ->
                result.resultId == TextContrastCheck.RESULT_ID_TEXTVIEW_CONTRAST_NOT_SUFFICIENT
            }
        )
    }

    private fun AccessibilityViewCheckResult.isContrastOf(expected: String): Boolean = (type == ERROR || type == WARNING) &&
        accessibilityHierarchyCheck == TextContrastCheck::class.java &&
        element?.text?.toString() == expected

    /**
     * Seven lines in a 400 dp tall scroller; the last one can be taller, so it is cut to a sliver
     * while the text at its top stays inside the viewport.
     */
    private fun showNativeLines(
        lines: Int,
        lineHeight: Dp,
        lastLineHeight: Dp = lineHeight,
        shade: Int,
        edgeShade: Int = shade,
        wash: Boolean = false,
        declaredBackground: Boolean = false
    ) {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = false, dynamicColor = false) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        FrameLayout(context).apply {
                            addView(
                                ScrollView(context).apply {
                                    addView(
                                        LinearLayout(context).apply {
                                            orientation = LinearLayout.VERTICAL
                                            repeat(lines) { index ->
                                                val atEdge = index == lines - 1
                                                addView(
                                                    nativeLine(
                                                        context = context,
                                                        index = index,
                                                        shade = if (atEdge) edgeShade else shade,
                                                        lineHeight = if (atEdge) lastLineHeight else lineHeight,
                                                        declaredBackground = declaredBackground
                                                    )
                                                )
                                            }
                                        }
                                    )
                                },
                                matchParent()
                            )
                            if (wash) addView(washBar(context))
                        }
                    }
                )
            }
        }
    }

    private fun nativeLine(
        context: Context,
        index: Int,
        shade: Int,
        lineHeight: Dp,
        declaredBackground: Boolean
    ): TextView = TextView(context).apply {
        text = "Line $index"
        textSize = 16f
        gravity = Gravity.TOP
        setPadding(6, 4, 6, 4)
        setTextColor(shade)
        if (declaredBackground) {
            setBackgroundColor(0xFFFFFFFF.toInt())
        } else {
            background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()) }
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (lineHeight.value * resources.displayMetrics.density).toInt()
        )
    }

    private fun showComposeLines(
        lines: Int,
        lineHeight: Dp,
        lastLineHeight: Dp = lineHeight,
        shade: Color,
        edgeShade: Color = shade,
        wash: Boolean
    ) {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = false, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        repeat(lines) { index ->
                            val atEdge = index == lines - 1
                            Text(
                                text = "Line $index",
                                color = if (atEdge) edgeShade else shade,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (atEdge) lastLineHeight else lineHeight)
                                    .background(Color.White)
                                    .padding(4.dp)
                            )
                        }
                    }
                    if (wash) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(33.dp)
                                .background(Color(0x99FFFFFF))
                        )
                    }
                }
            }
        }
    }

    private fun washBar(context: Context): View = View(context).apply {
        setBackgroundColor(0x99FFFFFF.toInt())
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (33 * resources.displayMetrics.density).toInt(),
            Gravity.BOTTOM
        )
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    private companion object {
        val READABLE = Color(0xFF212121)
        val LOW_CONTRAST = Color(0xFF9E9E9E)
        val READABLE_TEXT = 0xFF212121.toInt()
        val LOW_CONTRAST_TEXT = 0xFF9E9E9E.toInt()
    }
}
