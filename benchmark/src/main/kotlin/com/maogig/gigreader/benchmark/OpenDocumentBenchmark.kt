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
 * Scenarios 2 and 3 (docs/PERFORMANCE_AND_POWER.md §9.2): open a 10-page and a 1000-page PDF from
 * the library screen of a running app.
 *
 * Setup seeds the document (generation/import happen outside the measurement and only on the first
 * iteration), then shows the library in a fresh process. The measured block asks SeedActivity to
 * open the document by title: SeedActivity knows the library id, the benchmark does not, and the
 * extra hop costs one database lookup outside `GigReader.openDocument`, which is the metric that
 * matters (from the reader's ViewModel starting to open the file to the session being ready).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class OpenDocumentBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun openSmallDocument() = open(Docs.SMALL)

    @Test
    fun openLargeDocument() = open(Docs.LARGE)

    private fun open(doc: SeedDoc) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                // targetPackageOnly = false: the section is async (process track), and the name is
                // unique to GigReader anyway.
                TraceSectionMetric(SECTION_OPEN_DOCUMENT, TraceSectionMetric.Mode.First, targetPackageOnly = false),
                TraceSectionMetric(SECTION_FIRST_PAGE, TraceSectionMetric.Mode.First, targetPackageOnly = false),
                FrameTimingMetric(),
            ),
            iterations = OPEN_CLOSE_ITERATIONS,
            setupBlock = {
                killProcess()
                seedDocuments(doc)
                startAtLibrary()
            },
        ) {
            openDocument(doc)
        }
}
