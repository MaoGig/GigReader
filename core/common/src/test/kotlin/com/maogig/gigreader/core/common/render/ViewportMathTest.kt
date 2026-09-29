package com.maogig.gigreader.core.common.render

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportMathTest {
    private val vw = 100f
    private val vh = 300f
    private val n = 10
    private val layout = DocumentLayout.build(FloatArray(n) { 100f }, FloatArray(n) { 150f }, vw, 0f, 10f)
    private val math = ViewportMath(minZoom = 1f, maxZoom = 8f)

    @Test
    fun clampKeepsViewportInsideDocument() {
        val t = math.clamp(ViewportTransform(1f, 50f, -40f), layout, vw, vh)
        assertEquals(0f, t.offsetX) // doc width == viewport width at zoom 1
        assertEquals(0f, t.offsetY)
        val bottom = math.clamp(ViewportTransform(1f, 0f, 1e9f), layout, vw, vh)
        assertEquals(layout.totalHeight - vh, bottom.offsetY)
    }

    @Test
    fun zoomAroundKeepsFocalPointFixed() {
        val start = ViewportTransform(1f, 0f, 200f)
        val focusX = 30f
        val focusY = 120f
        val docX = start.toDocX(focusX)
        val docY = start.toDocY(focusY)
        val zoomed = math.zoomAround(start, 2.5f, focusX, focusY, layout, vw, vh)
        assertEquals(2.5f, zoomed.zoom)
        assertEquals(focusX, zoomed.toScreenX(docX), 0.01f)
        assertEquals(focusY, zoomed.toScreenY(docY), 0.01f)
    }

    @Test
    fun zoomIsClampedToLimits() {
        val t = math.zoomAround(ViewportTransform(), 100f, 0f, 0f, layout, vw, vh)
        assertEquals(8f, t.zoom)
        val out = math.zoomAround(ViewportTransform(), 0.1f, 0f, 0f, layout, vw, vh)
        assertEquals(1f, out.zoom)
    }

    @Test
    fun panUsesScreenDeltasDividedByZoom() {
        val t = ViewportTransform(2f, 10f, 100f)
        val moved = math.panBy(t, dxScreen = -20f, dyScreen = -40f, layout, vw, vh)
        assertEquals(20f, moved.offsetX, 0.001f)
        assertEquals(120f, moved.offsetY, 0.001f)
    }

    @Test
    fun pagePositionRoundTrips() {
        val pos = PagePosition(page = 4, pageOffset = 0.25f)
        val t = math.transformFor(pos, 1f, layout, vw, vh)
        val back = math.topPosition(t, layout)
        assertEquals(4, back.page)
        assertEquals(0.25f, back.pageOffset, 0.001f)
    }

    @Test
    fun jumpToPageTopShowsTheGapAbove() {
        val t = math.transformFor(PagePosition(3, 0f), 1f, layout, vw, vh)
        assertEquals(layout.pageTop(3) - layout.pageGap, t.offsetY)
        assertEquals(3, math.topPosition(t, layout).page)
    }

    @Test
    fun currentPageIsThePageAtTheViewportCenter() {
        val t = ViewportTransform(1f, 0f, layout.pageTop(2) - 10f)
        assertEquals(2, math.currentPage(t, layout, vh))
    }

    @Test
    fun shortDocumentsAreCentered() {
        val single = DocumentLayout.build(floatArrayOf(100f), floatArrayOf(100f), vw, 0f, 0f)
        val t = math.clamp(ViewportTransform(1f, 0f, 50f), single, vw, vh)
        assertEquals(-100f, t.offsetY) // (100 - 300) / 2
    }
}
