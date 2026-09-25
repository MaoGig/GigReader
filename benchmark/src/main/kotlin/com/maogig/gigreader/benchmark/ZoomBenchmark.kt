package com.maogig.gigreader.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scenario 5 (docs/PERFORMANCE_AND_POWER.md §9.2): pinch in/out, then double tap (fit ↔ 2.5×) and
 * back. Frame timing covers the gestures; `GigReader.renderPage` (summed) is the rendering work the
 * zoom changes trigger.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ZoomBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun pinchAndDoubleTap() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                FrameTimingMetric(),
                TraceSectionMetric(SECTION_RENDER_PAGE, TraceSectionMetric.Mode.Sum, targetPackageOnly = false),
            ),
            iterations = INTERACTION_ITERATIONS,
            setupBlock = { openDocumentFresh(Docs.SMALL) },
        ) {
            val viewport = waitForObject(TAG_READER_VIEWPORT)
            viewport.keepGesturesAwayFromEdges(device)
            repeat(CYCLES) {
                viewport.pinchOpen(PINCH_PERCENT)
                device.waitForIdle()
                viewport.pinchClose(PINCH_PERCENT)
                device.waitForIdle()
            }
            val center = viewport.visibleCenter
            repeat(CYCLES) {
                device.doubleTap(center) // fit width → zoomed
                device.waitForIdle()
                device.doubleTap(center) // zoomed → fit width
                device.waitForIdle()
            }
        }

    private companion object {
        const val CYCLES = 2
    }
}
