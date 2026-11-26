package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.root.WizardState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pairing sheet always offers the in-app scanner as an explicit choice. A real failure of the
 * Google scanner must not prompt for the camera by itself, so this button is the way forward.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PairingCameraButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun thePairingSheetOpensTheInAppScannerOnPurpose() {
        var opened = 0
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    OnboardingScreen(
                        state = OnboardingUiState(WizardState.NeedsPairing(OnboardingSamples.magisk)),
                        callbacks = OnboardingCallbacks(onCameraScan = { opened++ })
                    )
                }
            }
        }

        composeRule.onNodeWithText("Paste the pairing link").performClick()
        composeRule.onNodeWithText("Use this phone's camera").performClick()

        assertEquals(1, opened)
    }
}
