package com.maogig.gigreader.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scenario 8 (docs/PERFORMANCE_AND_POWER.md §9.2): "next page" twenty times with the reader's bottom
 * bar button, each followed by the page-jump animation and the render of the new page.
 */
@RunWith(AndroidJUnit4::class)
class PageTurnBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun nextPageTwentyTimes() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = INTERACTION_ITERATIONS,
            setupBlock = { openDocumentFresh(Docs.LARGE) },
        ) {
            repeat(PAGE_TURNS) {
                waitForObject(TAG_READER_NEXT_PAGE).click()
                device.waitForIdle()
            }
        }

    private companion object {
        const val PAGE_TURNS = 20
    }
}
