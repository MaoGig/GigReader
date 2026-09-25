package com.maogig.gigreader.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library grid scrolling with about sixty documents (covers loading while flinging). The filler
 * documents are seeded once per test process; SeedActivity skips titles that already exist, so
 * later runs only pay for a quick lookup.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun flingLibrary() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = INTERACTION_ITERATIONS,
            setupBlock = {
                if (!librarySeeded) {
                    seedDocuments(Docs.LIBRARY, count = Docs.LIBRARY_COUNT)
                    librarySeeded = true
                }
                killProcess()
                startAtLibrary()
            },
        ) {
            val list = waitForObject(TAG_LIBRARY_LIST)
            list.keepGesturesAwayFromEdges(device)
            // The grid publishes scroll events, so fling() returns as soon as the scroll settles.
            repeat(FLINGS) { list.fling(Direction.DOWN) }
            repeat(FLINGS) { list.fling(Direction.UP) }
        }

    private companion object {
        const val FLINGS = 3
        var librarySeeded = false
    }
}
