package com.phonxiety.bridge.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Produces the baseline profile. `./gradlew :app:generateBaselineProfile` runs
 * these tests on the connected device and copies the result to
 * `app/src/release/generated/baselineProfiles/baseline-prof.txt`.
 *
 * Startup is marked as a startup-profile journey; the navigation journey is not,
 * because only code on the cold-start path belongs in the startup profile.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val profileRule = BaselineProfileRule()

    @Test
    fun startup() = profileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        startActivityAndWait()
        awaitShell()
    }

    @Test
    fun dashboardToCallsToLab() = profileRule.collect(packageName = TARGET_PACKAGE) {
        startActivityAndWait()
        if (awaitShell()) {
            openSection(CALLS_LABEL)
            scrollCurrentList()
            openSection(LAB_LABEL)
            scrollCurrentList()
        } else {
            // Unpaired: the wizard is all this install can show.
            device.waitForIdle()
        }
    }
}
