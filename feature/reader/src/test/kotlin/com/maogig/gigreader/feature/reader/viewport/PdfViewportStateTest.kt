package com.maogig.gigreader.feature.reader.viewport

import androidx.compose.runtime.saveable.SaverScope
import com.maogig.gigreader.core.common.render.DocumentLayout
import com.maogig.gigreader.core.common.render.PagePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfViewportStateTest {
    // A phone in portrait: at zoom 1 an A4 page is ~1528 px tall in a 2400 px viewport.
    private val width = 1080f
    private val height = 2400f

    private fun layout(pages: Int, viewportWidth: Float = width): DocumentLayout = DocumentLayout.build(
        FloatArray(pages) { 595f },
        FloatArray(pages) { 842f },
        viewportWidth,
        horizontalPadding = 16f,
        pageGap = 16f,
    )

    private fun laidOut(
        pages: Int,
        position: PagePosition = PagePosition(0, 0f),
        zoom: Float = 1f,
        offsetXFraction: Float = 0f,
    ): PdfViewportState = PdfViewportState(position, zoom, offsetXFraction).apply {
        updateLayout(layout(pages), width, height)
    }

    @Test
    fun lastVisiblePageIsTheLastPageAtTheDocumentEnd() {
        val state = laidOut(pages = 10)
        state.scrollToOffsetY(Float.MAX_VALUE) // clamped at the end

        val position = state.position()
        // The top edge is pages earlier: progress computed from it could never reach 100 %.
        assertTrue(position.top.page < 9, "top page ${position.top.page}")
        assertEquals(9, position.lastVisiblePage)
        assertEquals(9, state.lastVisiblePage())
        assertTrue(position.currentPage <= position.lastVisiblePage)
    }

    @Test
    fun lastVisiblePageOfATwoPageDocumentAtTheEnd() {
        val state = laidOut(pages = 2)
        state.scrollToOffsetY(Float.MAX_VALUE)

        assertEquals(1, state.lastVisiblePage())
    }

    @Test
    fun lastVisiblePageMidDocumentIsThePageAtTheBottomEdge() {
        val state = laidOut(pages = 10)
        // At the top: page 0 (1528 px) and part of page 1 fill the 2400 px viewport.
        assertEquals(0, state.topPosition().page)
        assertEquals(1, state.lastVisiblePage())

        // Page 4 at the top edge, page 5 partly visible at the bottom edge, page 6 below it.
        state.scrollToOffsetY(assertNotNull(state.layout).pageTop(4) + 100f)
        assertEquals(4, state.topPosition().page)
        assertEquals(5, state.lastVisiblePage())
    }

    @Test
    fun lastVisiblePageWhenTheWholeDocumentFits() {
        val state = laidOut(pages = 1)

        assertEquals(0, state.lastVisiblePage())
        assertEquals(0, state.currentPage)
    }

    @Test
    fun zoomedInLastVisiblePageFollowsTheVisibleArea() {
        val state = laidOut(pages = 10, position = PagePosition(3, 0.5f), zoom = 4f)

        // 2400 / 4 = 600 document px are visible: all inside page 3.
        assertEquals(3, state.topPosition().page)
        assertEquals(3, state.lastVisiblePage())
    }

    @Test
    fun restoresTheHorizontalOffsetWithTheFirstLayout() {
        val state = PdfViewportState(PagePosition(3, 0.25f), initialZoom = 2.5f, initialOffsetXFraction = 0.3f)
        // Before the first layout the pending value is reported (and saved).
        assertEquals(0.3f, state.offsetXFraction())

        state.updateLayout(layout(10), width, height)

        assertEquals(0.3f * width, state.transform.offsetX, 0.01f)
        assertEquals(0.3f, state.offsetXFraction(), 1e-4f)
        assertEquals(3, state.topPosition().page)
        assertEquals(0.25f, state.topPosition().pageOffset, 1e-3f)
        assertEquals(2.5f, state.transform.zoom)
    }

    @Test
    fun restoredHorizontalOffsetIsClampedToTheZoom() {
        // At zoom 2 only half the width is visible: the offset cannot exceed half the document width.
        val state = laidOut(pages = 10, zoom = 2f, offsetXFraction = 0.9f)

        assertEquals(width / 2f, state.transform.offsetX, 0.01f)
        assertEquals(0.5f, state.offsetXFraction(), 1e-4f)
    }

    @Test
    fun horizontalOffsetIsZeroAtFitWidth() {
        val state = laidOut(pages = 10, zoom = 1f, offsetXFraction = 0.4f)

        assertEquals(0f, state.transform.offsetX)
        assertEquals(0f, state.offsetXFraction())
    }

    @Test
    fun corruptStoredHorizontalOffsetIsIgnored() {
        val state = laidOut(pages = 10, zoom = 2f, offsetXFraction = Float.NaN)

        assertEquals(0f, state.transform.offsetX)
    }

    @Test
    fun horizontalFractionSurvivesAResize() {
        val state = laidOut(pages = 10, zoom = 2f, offsetXFraction = 0.25f)

        // Rotation to landscape: a new layout at another width keeps the same fraction.
        state.updateLayout(layout(10, viewportWidth = 2400f), 2400f, 1080f)

        assertEquals(0.25f, state.offsetXFraction(), 1e-4f)
    }

    @Test
    fun saverRoundTripKeepsPositionZoomAndHorizontalOffset() {
        val state = laidOut(pages = 10, position = PagePosition(6, 0.4f), zoom = 3f, offsetXFraction = 0.2f)
        val before = state.position()

        val saved = with(PdfViewportState.Saver) { SaverScope { true }.save(state) }
        val restored = PdfViewportState.Saver.restore(assertNotNull(saved))
        assertNotNull(restored)
        restored.updateLayout(layout(10), width, height)

        val after = restored.position()
        assertEquals(before.top.page, after.top.page)
        assertEquals(before.top.pageOffset, after.top.pageOffset, 1e-3f)
        assertEquals(before.zoom, after.zoom)
        assertEquals(before.offsetXFraction, after.offsetXFraction, 1e-4f)
        assertEquals(before.currentPage, after.currentPage)
        assertEquals(before.lastVisiblePage, after.lastVisiblePage)
    }

    @Test
    fun saverRestoresStateSavedWithoutHorizontalOffset() {
        // Saved by the previous version (page, fraction, zoom).
        val restored = PdfViewportState.Saver.restore(floatArrayOf(2f, 0.5f, 2f))
        assertNotNull(restored)
        restored.updateLayout(layout(10), width, height)

        assertEquals(2, restored.topPosition().page)
        assertEquals(0f, restored.transform.offsetX)
    }
}
