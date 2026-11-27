package com.phonxiety.bridge.feature.updates

import com.phonxiety.bridge.designsystem.testing.assertStableHolders
import org.junit.Test

/**
 * The state this screen draws, annotated only where a Compose report run
 * (`-Pphonxiety.composeReports`) showed the annotation changes the compiler's verdict. A dropped
 * annotation leaves every one of these classes unstable again, and this check is what says so.
 */
class UpdatesStabilityTest {
    @Test
    fun `the screen state holders are stable`() {
        assertStableHolders(
            UpdatesUiState::class.java
        )
    }
}
