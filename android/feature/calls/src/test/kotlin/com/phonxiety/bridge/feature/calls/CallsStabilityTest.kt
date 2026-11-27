package com.phonxiety.bridge.feature.calls

import com.phonxiety.bridge.designsystem.testing.assertStableHolders
import org.junit.Test

/**
 * The state this screen draws, annotated only where a Compose report run
 * (`-Pphonxiety.composeReports`) showed the annotation changes the compiler's verdict. A dropped
 * annotation leaves every one of these classes unstable again, and this check is what says so.
 */
class CallsStabilityTest {
    @Test
    fun `the screen state holders are stable`() {
        assertStableHolders(
            CallsUiState::class.java,
            CallsFailure::class.java,
            CallBrief::class.java,
            CallAudioUiState::class.java,
            CallDetailUiState::class.java
        )
    }
}
