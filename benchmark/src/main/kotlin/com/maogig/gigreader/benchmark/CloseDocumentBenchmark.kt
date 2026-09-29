package com.maogig.gigreader.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scenario 10 (docs/PERFORMANCE_AND_POWER.md §9.2): leave the reader back to the library.
 * `GigReader.closeDocument` spans the ViewModel's onCleared until the final position write and the
 * document close have finished on a background thread.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class CloseDocumentBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun closeSmallDocument() = close(Docs.SMALL)

    @Test
    fun closeLargeDocument() = close(Docs.LARGE)

    private fun close(doc: SeedDoc) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                TraceSectionMetric(SECTION_CLOSE_DOCUMENT, TraceSectionMetric.Mode.First, targetPackageOnly = false),
            ),
            iterations = OPEN_CLOSE_ITERATIONS,
            setupBlock = { openDocumentFresh(doc) },
        ) {
            closeReader()
            // The section ends on a background thread after the library is back; keep tracing
            // until the app is idle so it is inside the trace.
            device.waitForIdle()
        }
}
