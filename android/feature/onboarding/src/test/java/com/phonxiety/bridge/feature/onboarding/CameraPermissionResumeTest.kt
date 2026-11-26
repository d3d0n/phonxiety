package com.phonxiety.bridge.feature.onboarding

import android.Manifest
import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The camera dialog stays open while the person allows the camera in Settings; coming back has to
 * re-read the grant, or the dialog keeps showing the explanation instead of starting the scan.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CameraPermissionResumeTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The dialog's window: a lifecycle the test drives the way the system would. */
    private class TestWindow : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun theGrantMadeInSettingsStartsTheScanOnReturn() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val owner = TestWindow()
        val explanation = application.getString(R.string.onboarding_scanner_permission)

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PhonxietyTheme(dynamicColor = false) {
                    // Robolectric has no ML Kit context or camera, so the preview is a stand-in;
                    // which branch of the dialog is composed is what this test is about.
                    QrScanDialog(
                        onCode = {},
                        onDismiss = {},
                        onPasteRequested = {},
                        preview = { Text("Camera preview") }
                    )
                }
            }
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.waitForIdle()
        // The camera is denied, so the dialog explains how to allow it.
        composeRule.onNodeWithText(explanation).assertExists()

        // "Open settings" leaves this dialog behind; the person grants the camera there and returns.
        owner.registry.currentState = Lifecycle.State.CREATED
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        owner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.waitForIdle()

        composeRule.onNodeWithText(explanation).assertDoesNotExist()
        composeRule.onNodeWithText("Camera preview").assertExists()
    }
}
