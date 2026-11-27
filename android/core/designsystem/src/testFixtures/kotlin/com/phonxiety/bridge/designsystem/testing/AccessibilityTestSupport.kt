package com.phonxiety.bridge.designsystem.testing

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType.ERROR
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType.WARNING
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheck
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.ViewChecker
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import com.google.android.apps.common.testing.accessibility.framework.replacements.Rect as AtfRect
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElementOrigin
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The shared accessibility gate. Warning is the failure level because insufficient contrast is
 * reported as a warning by the Accessibility Test Framework, and hidden contrast failures are
 * exactly what this pass exists to catch.
 *
 * A contrast result is suppressed only when it cannot tell the truth about what a reader sees. All
 * of these must hold:
 *
 *  - the ratio was estimated by sampling screenshot pixels, not compared from declared colours,
 *    whose pair clipping cannot change;
 *  - the framework marked the element as sitting at a scrollable ancestor's edge;
 *  - less than half of the element's own height is on screen, so it is a sliver nobody can read
 *    and the few remaining antialiased pixels say nothing about the colours.
 *
 * Every other contrast result is kept: fully visible text, text at least half visible (a scroll
 * edge alone says nothing), and any declared-colour result. WCAG 1.4.3 also exempts inactive
 * controls, and the framework misreads a disabled Button's centred label as its container colour,
 * so the contrast result of a disabled control is dropped as well.
 */
internal object AccessibilityGate {
    fun check(interaction: SemanticsNodeInteraction) {
        val node = interaction.fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val failures = evaluate(view)
            .filterNot { it.isSuppressed(interaction, view) }
            .filter { it.type == ERROR || it.type == WARNING }
        if (failures.isNotEmpty()) throw AccessibilityViewCheckException(failures)
    }

    /** What one framework run reported, before the rule drops anything. */
    fun results(interaction: SemanticsNodeInteraction): List<AccessibilityViewCheckResult> {
        val node = interaction.fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        return evaluate(view)
    }

    private fun evaluate(view: View): List<AccessibilityViewCheckResult> {
        val parameters = Parameters().apply {
            putScreenCapture(BitmapImage(windowCapture(view)))
        }
        return AtfRunner.run(view, PRESET_CHECKS, parameters)
    }

    private fun AccessibilityViewCheckResult.isSuppressed(
        interaction: SemanticsNodeInteraction,
        root: View
    ): Boolean {
        if (!isFailingContrast()) return false
        if (element.insideDisabledControl()) return true
        if (resultId !in SAMPLE_DERIVED_CONTRAST_IDS) return false
        // The framework marks every line in a scroller, including the ones the reader sees whole,
        // so the mark alone does not make a clipped sample.
        if (metadata?.getBoolean(TextContrastCheck.KEY_IS_AGAINST_SCROLLABLE_EDGE, false) != true) {
            return false
        }
        val flagged = element ?: return false
        val visibleHeight = flagged.boundsInScreen.height
        val fullHeight = flagged.nonclippedHeight
            ?: interaction.composeHeight(flagged, root)
            ?: return false
        return visibleHeight > 0 && fullHeight > 0 && visibleHeight * 2 < fullHeight
    }

    private fun AccessibilityViewCheckResult.isFailingContrast(): Boolean = isContrast() &&
        (type == ERROR || type == WARNING)

    private fun AccessibilityViewCheckResult.isContrast(): Boolean = accessibilityHierarchyCheck ==
        TextContrastCheck::class.java

    /** The flagged node is the label inside the Button, so the disabled state sits on an ancestor. */
    private fun ViewHierarchyElement?.insideDisabledControl(): Boolean {
        var node = this
        while (node != null) {
            if (!node.isEnabled) return true
            node = node.parentView
        }
        return false
    }

    /**
     * The full height of a Compose text node. The framework builds Compose's virtual nodes from
     * accessibility nodes alone, where the bounds are already clipped to the scroll viewport, so it
     * has no unclipped height for them; the node's own layout size still does. The unmerged tree is
     * walked because a merged node inherits its descendants' text while its own size stays the
     * ancestor's: a container clipped to its top can share the sampled rectangle with a fully
     * visible label inside it, and the container's taller size would then hide the label's real
     * contrast failure. The node is the one that lays out this text itself in exactly the clipped
     * rectangle the framework sampled. Two such nodes, or none, leave the height unknown and the
     * result kept.
     */
    private fun SemanticsNodeInteraction.composeHeight(flagged: ViewHierarchyElement, root: View): Int? {
        if (flagged.origin != ViewHierarchyElementOrigin.COMPOSE) return null
        val text = flagged.text?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        var height: Int? = null
        val pending = ArrayDeque<SemanticsNode>()
        pending.add(
            fetchSemanticsNode().root?.semanticsOwner?.unmergedRootSemanticsNode ?: return null
        )
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node.mentions(text) && node.boundsInWindow.matches(flagged.boundsInScreen, location)) {
                if (height != null) return null
                height = node.size.height
            }
            pending.addAll(node.children)
        }
        return height
    }

    private fun SemanticsNode.mentions(text: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true

    /** The node's clipped bounds are the sampled rectangle, in screen pixels within a pixel. */
    private fun Rect.matches(sampled: AtfRect, location: IntArray): Boolean = abs(left.roundToInt() + location[0] - sampled.left) <= 1 &&
        abs(top.roundToInt() + location[1] - sampled.top) <= 1 &&
        abs(right.roundToInt() + location[0] - sampled.right) <= 1 &&
        abs(bottom.roundToInt() + location[1] - sampled.bottom) <= 1

    /**
     * The window the framework samples, drawn where it sits on the screen so that pixel lookups
     * and screen-space element bounds describe the same coordinates. A dialog window is therefore
     * captured at its own offset rather than at the top-left corner.
     */
    @SuppressLint("UseKtx") // core-ktx is not on this source set's classpath.
    private fun windowCapture(anchor: View): Bitmap {
        val root = anchor.rootView
        check(root.width > 0 && root.height > 0) { "the gate samples a laid-out window" }
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val bitmap = Bitmap.createBitmap(
            location[0] + root.width,
            location[1] + root.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.translate(location[0].toFloat(), location[1].toFloat())
        root.draw(canvas)
        return bitmap
    }

    private val PRESET_CHECKS: Collection<AccessibilityHierarchyCheck> = AtfRunner.presetChecks()

    /**
     * Result ids whose ratio is estimated by sampling the screenshot, so a clipped sample can
     * mislead. The upper- and lower-bound ids are absent: those compare declared colour ranges and
     * do not depend on any pixel.
     */
    private val SAMPLE_DERIVED_CONTRAST_IDS = setOf(
        TextContrastCheck.RESULT_ID_TEXTVIEW_HEURISTIC_CONTRAST_NOT_SUFFICIENT,
        TextContrastCheck.RESULT_ID_TEXTVIEW_HEURISTIC_CONTRAST_BORDERLINE,
        TextContrastCheck.RESULT_ID_CUSTOMIZED_TEXTVIEW_HEURISTIC_CONTRAST_NOT_SUFFICIENT
    )
}

/**
 * The Accessibility Test Framework exposes its runner only through Guava's immutable collections,
 * which this fixture source set does not see at compile time. The calls are reflected so the gate
 * stays on one dependency set; results are ordinary lists at runtime. Every lookup resolves when
 * this object is first used, so a moved framework API fails the test run loudly.
 *
 * Under Robolectric's own fingerprint the framework builds its hierarchy from Views alone, which
 * leaves out Compose's virtual text nodes entirely, so visible low-contrast Compose text would
 * never be examined. The fingerprint is swapped for the duration of each run and restored
 * afterwards, the same way Roborazzi's own accessibility checker does it.
 */
private object AtfRunner {
    private val presetMethod = AccessibilityCheckPreset::class.java.getMethod(
        "getAccessibilityHierarchyChecksForPreset",
        AccessibilityCheckPreset::class.java
    )
    private val runChecksMethod = ViewChecker::class.java.methods.first {
        it.name == "runChecksOnView" && it.parameterCount == 3
    }
    private val immutableCopyOf = runChecksMethod
        .parameterTypes[0]
        .getMethod("copyOf", Collection::class.java)
    private val setFingerprint = Class.forName("org.robolectric.shadows.ShadowBuild")
        .getMethod("setFingerprint", String::class.java)

    fun presetChecks(): Collection<AccessibilityHierarchyCheck> {
        @Suppress("UNCHECKED_CAST")
        return presetMethod.invoke(null, AccessibilityCheckPreset.LATEST) as Collection<AccessibilityHierarchyCheck>
    }

    fun run(
        view: View,
        checks: Collection<AccessibilityHierarchyCheck>,
        parameters: Parameters
    ): List<AccessibilityViewCheckResult> = whileAvoidingRobolectricFingerprint {
        val checker = ViewChecker().setObtainCharacterLocations(true)
        val immutableChecks = immutableCopyOf.invoke(null, checks)
        @Suppress("UNCHECKED_CAST")
        runChecksMethod.invoke(checker, immutableChecks, view, parameters)
            as List<AccessibilityViewCheckResult>
    }

    private fun <T> whileAvoidingRobolectricFingerprint(block: () -> T): T {
        val original = Build.FINGERPRINT
        if (original == ROBOLECTRIC_FINGERPRINT) {
            setFingerprint.invoke(null, ESCAPE_FINGERPRINT)
        }
        return try {
            block()
        } finally {
            if (original == ROBOLECTRIC_FINGERPRINT) {
                setFingerprint.invoke(null, original)
            }
        }
    }

    private const val ROBOLECTRIC_FINGERPRINT = "robolectric"

    /** Any value other than the Robolectric one makes the framework read the accessibility tree. */
    private const val ESCAPE_FINGERPRINT = "roborazzi"
}

/** Runs the gate on one captured state. */
fun SemanticsNodeInteraction.assertAccessible() = AccessibilityGate.check(this)

/** What one framework run reported for this state, before the suppression rule drops anything. */
fun SemanticsNodeInteraction.accessibilityResults(): List<AccessibilityViewCheckResult> = AccessibilityGate.results(this)
