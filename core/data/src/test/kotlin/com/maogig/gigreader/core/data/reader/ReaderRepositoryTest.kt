package com.maogig.gigreader.core.data.reader

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.data.SequentialIds
import com.maogig.gigreader.core.data.TestClock
import com.maogig.gigreader.core.data.insertDocument
import com.maogig.gigreader.core.data.library.CoverStore
import com.maogig.gigreader.core.data.library.LocalLibraryRepository
import com.maogig.gigreader.core.database.GigReaderDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class ReaderRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: GigReaderDatabase
    private lateinit var files: DocumentFileStore
    private lateinit var reader: LocalReaderRepository
    private val clock = TestClock()

    @Before
    fun setUp() {
        db = GigReaderDatabase.inMemory(ApplicationProvider.getApplicationContext())
        files = DocumentFileStore(File(tmp.root, "library"))
        reader = LocalReaderRepository(db, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun position(page: Int, current: Int, lastVisible: Int, zoom: Float = 1f, offsetX: Float = 0f) =
        SavedReadingPosition(
            page = page, pageOffset = 0.5f, zoom = zoom, offsetXFraction = offsetX, currentPage = current,
            lastVisiblePage = lastVisible,
        )

    @Test
    fun readingToTheEndReachesOneHundredPercent() = runTest {
        db.insertDocument(files, "d", pageCount = 10)
        assertNull(reader.position("d"))

        // At the end the viewport is clamped: the top page is 7, the indicator shows 9 of 10 (index 8)
        // and the last page is visible at the bottom edge.
        reader.savePosition("d", position(page = 7, current = 8, lastVisible = 9))
        val end = assertNotNull(reader.position("d"))
        assertEquals(7, end.page)
        assertEquals(8, end.currentPage)
        assertEquals(9, end.maxPageReached)
        assertEquals(1f, end.progress(10))
        assertEquals(1, end.version)

        // Going back never lowers the maximum.
        reader.savePosition("d", position(page = 0, current = 0, lastVisible = 1))
        val back = assertNotNull(reader.position("d"))
        assertEquals(0, back.page)
        assertEquals(0, back.currentPage)
        assertEquals(9, back.maxPageReached)
        assertEquals(2, back.version)
    }

    @Test
    fun zoomAndHorizontalOffsetAreRestored() = runTest {
        db.insertDocument(files, "d")
        reader.savePosition("d", position(page = 3, current = 3, lastVisible = 3, zoom = 2.5f, offsetX = 0.375f))
        val saved = assertNotNull(reader.position("d"))
        assertEquals(2.5f, saved.zoom)
        assertEquals(0.375f, saved.offsetXFraction)
        assertEquals(0.5f, saved.pageOffset)
    }

    @Test
    fun continueReadingShowsThePageOfTheReaderIndicator() = runTest {
        db.insertDocument(files, "d", pageCount = 10)
        reader.markOpened("d")
        reader.savePosition("d", position(page = 4, current = 5, lastVisible = 6))
        val library = LocalLibraryRepository(db, files, CoverStore(File(tmp.root, "covers")), clock, SequentialIds())

        val entry = library.observeContinueReading().first().single()
        assertEquals(5, entry.lastPage)
        assertEquals(6, entry.maxPageReached)
        assertEquals(0.7f, entry.progress)
    }

    @Test
    fun pageMetricsAndPageCountRoundTrip() = runTest {
        db.insertDocument(files, "d", pageCount = 0)
        reader.updatePageCount("d", 3)
        assertEquals(3, db.documentDao().getById("d")!!.pageCount)

        val sizes = PageSizes.uniform(3, 612f, 792f)
        reader.savePageMetrics("d", PageMetrics(sizes, measuredCount = 2))
        val cached = assertNotNull(reader.pageMetrics("d", pageCount = 3))
        assertEquals(2, cached.measuredCount)
        assertEquals(612f, cached.sizes.widths[2])
        assertNull(reader.pageMetrics("d", pageCount = 4), "stale metrics are ignored")
    }
}
