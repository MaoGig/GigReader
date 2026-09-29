package com.maogig.gigreader.benchmark

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
 * Scenario 1 (docs/PERFORMANCE_AND_POWER.md §9.2): cold and warm startup until the library list is
 * shown. `CompilationMode.None` is the worst case (no AOT code at all); `Partial(Require)` is what a
 * Play install gets with the shipped Baseline Profile, and fails the run if the profile is missing.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithoutCompilation() = startup(StartupMode.COLD, CompilationMode.None())

    @Test
    fun coldStartupWithBaselineProfile() =
        startup(StartupMode.COLD, CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    @Test
    fun warmStartupWithoutCompilation() = startup(StartupMode.WARM, CompilationMode.None())

    @Test
    fun warmStartupWithBaselineProfile() =
        startup(StartupMode.WARM, CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun startup(startupMode: StartupMode, compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = startupMode,
            iterations = STARTUP_ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            waitForObject(TAG_LIBRARY_LIST)
        }
}
