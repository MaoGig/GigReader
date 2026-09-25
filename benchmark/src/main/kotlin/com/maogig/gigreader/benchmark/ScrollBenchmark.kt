package com.maogig.gigreader.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scenario 4 (docs/PERFORMANCE_AND_POWER.md §9.2): continuous scrolling, ten flings through the
 * 1000-page document starting from page 1 at "fit width". Target: P90 < 12 ms, no frame > 33 ms.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun flingThroughLargeDocument() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = INTERACTION_ITERATIONS,
            setupBlock = { openDocumentFresh(Docs.LARGE) },
        ) {
            val viewport = waitForObject(TAG_READER_VIEWPORT)
            viewport.keepGesturesAwayFromEdges(device)
            repeat(FLINGS) { viewport.flingReader(device) }
        }

    private companion object {
        const val FLINGS = 10
    }
}
