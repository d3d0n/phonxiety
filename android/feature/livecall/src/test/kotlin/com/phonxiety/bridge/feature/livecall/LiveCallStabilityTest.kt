package com.phonxiety.bridge.feature.livecall

import com.phonxiety.bridge.designsystem.testing.assertStableHolders
import org.junit.Test

/**
 * The state this screen draws, annotated only where a Compose report run
 * (`-Pphonxiety.composeReports`) showed the annotation changes the compiler's verdict. A dropped
 * annotation leaves every one of these classes unstable again, and this check is what says so.
 */
class LiveCallStabilityTest {
    @Test
    fun `the screen state holders are stable`() {
        assertStableHolders(
            ComposerIssue::class.java
        )
    }
}
