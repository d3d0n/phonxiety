package com.phonxiety.bridge.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start timing with and without the baseline profile, so the effect of the
 * committed profile is a number rather than a belief.  The profile case fails
 * until `:app:generateBaselineProfile` has produced and the install carries one;
 * that is the honest result of asking for a profile that does not exist.
 *
 * The install is a minified release build, which is what users run, so a
 * regression in the R8 output shows up here as well.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithoutCompilation() = coldStartup(CompilationMode.None())

    @Test
    fun coldStartupWithBaselineProfile() = coldStartup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun coldStartup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD
    ) {
        pressHome()
        startActivityAndWait()
    }
}
