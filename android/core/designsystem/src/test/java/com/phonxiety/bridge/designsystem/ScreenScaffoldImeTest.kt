package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scaffold is the screen's only keyboard owner. A synthetic inset of a known height moves the
 * content's bottom edge up by exactly that height once — a second `imePadding` in the frame would
 * move it twice — and a hidden keyboard leaves the content at the bottom of the window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenScaffoldImeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var contentBottomPx = 0f
    private var screenBottomPx = 0f
    private var insetPx = 0f

    @Test
    fun aRaisedKeyboardMovesTheContentUpExactlyOnce() {
        composeRule.setContent {
            insetPx = with(LocalDensity.current) { KEYBOARD_HEIGHT.toPx() }
            PhonxietyTheme(dynamicColor = false) {
                ScreenScaffold(
                    title = "Compose",
                    imeInsets = WindowInsets(bottom = KEYBOARD_HEIGHT)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { contentBottomPx = it.boundsInRoot().bottom }
                    )
                }
            }
        }
        composeRule.waitForIdle()
        screenBottomPx = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom

        assertEquals(screenBottomPx - insetPx, contentBottomPx, TOLERANCE_PX)
    }

    @Test
    fun aHiddenKeyboardLeavesTheContentAtTheBottomOfTheWindow() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ScreenScaffold(title = "Compose", imeInsets = WindowInsets(0)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { contentBottomPx = it.boundsInRoot().bottom }
                    )
                }
            }
        }
        composeRule.waitForIdle()
        screenBottomPx = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom

        assertEquals(screenBottomPx, contentBottomPx, TOLERANCE_PX)
    }

    @Test
    fun aRaisedKeyboardLiftsTheSnackbarAndPadsTheContentOnce() {
        var snackbarBottomPx = 0f
        composeRule.setContent {
            insetPx = with(LocalDensity.current) { KEYBOARD_HEIGHT.toPx() }
            PhonxietyTheme(dynamicColor = false) {
                ScreenScaffold(
                    title = "Compose",
                    imeInsets = WindowInsets(bottom = KEYBOARD_HEIGHT),
                    snackbarHost = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SNACKBAR_HEIGHT)
                                .testTag(SNACKBAR_TAG)
                                .onGloballyPositioned { snackbarBottomPx = it.boundsInRoot().bottom }
                        )
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { contentBottomPx = it.boundsInRoot().bottom }
                    )
                }
            }
        }
        composeRule.waitForIdle()
        screenBottomPx = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom

        // Both the snackbar and the content stop at the keyboard's top edge: one inset owner.
        assertEquals(screenBottomPx - insetPx, snackbarBottomPx, TOLERANCE_PX)
        assertEquals(screenBottomPx - insetPx, contentBottomPx, TOLERANCE_PX)
    }

    private companion object {
        val KEYBOARD_HEIGHT = 280.dp
        val SNACKBAR_HEIGHT = 24.dp
        const val SNACKBAR_TAG = "scaffold_snackbar"
        const val TOLERANCE_PX = 1f
    }
}
