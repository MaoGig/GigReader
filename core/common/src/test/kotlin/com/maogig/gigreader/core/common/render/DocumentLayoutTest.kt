package com.maogig.gigreader.core.common.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentLayoutTest {
    // Three A4-ish pages of 100x200 pt and one landscape page 200x100 pt.
    private val widths = floatArrayOf(100f, 100f, 200f, 100f)
    private val heights = floatArrayOf(200f, 200f, 100f, 200f)
    private val layout = DocumentLayout.build(widths, heights, viewportWidth = 120f, horizontalPadding = 10f, pageGap = 8f)

    @Test
    fun pagesAreFittedToContentWidth() {
        assertEquals(100f, layout.contentWidth)
        assertEquals(200f, layout.pageHeight(0))
        assertEquals(50f, layout.pageHeight(2)) // landscape page fitted to width
        assertEquals(8f, layout.pageTop(0))
        assertEquals(8f + 200f + 8f, layout.pageTop(1))
        assertEquals(8f + 200f + 8f + 200f + 8f, layout.pageTop(2))
        assertEquals(layout.pageBottom(3) + 8f, layout.totalHeight)
    }

    @Test
    fun pageAtResolvesGapsToFollowingPage() {
        assertEquals(0, layout.pageAt(0f))
        assertEquals(0, layout.pageAt(100f))
        assertEquals(1, layout.pageAt(209f)) // inside the gap after page 0
        assertEquals(1, layout.pageAt(216f))
        assertEquals(3, layout.pageAt(layout.totalHeight + 1000f))
    }

    @Test
    fun pagesInReturnsIntersectingPages() {
        assertEquals(0..0, layout.pagesIn(0f, 100f))
        assertEquals(0..1, layout.pagesIn(100f, 300f))
        assertTrue(layout.pagesIn(209f, 215f).isEmpty(), "band entirely inside a gap")
        assertEquals(0..3, layout.pagesIn(-50f, layout.totalHeight + 50f))
        assertTrue(layout.pagesIn(10f, 10f).isEmpty())
    }

    @Test
    fun unknownPageSizesFallBackToA4() {
        val l = DocumentLayout.build(floatArrayOf(0f), floatArrayOf(-1f), 100f, 0f, 0f)
        assertEquals(100f * 842f / 595f, l.pageHeight(0), 0.001f)
    }

    @Test
    fun largeDocumentLookupsAreConsistent() {
        val n = 5000
        val l = DocumentLayout.build(FloatArray(n) { 595f }, FloatArray(n) { if (it % 7 == 0) 400f else 842f }, 1080f, 16f, 12f)
        for (i in 0 until n step 97) {
            assertEquals(i, l.pageAt(l.pageTop(i) + 1f))
            assertEquals(i, l.pageAt(l.pageBottom(i) - 1f))
        }
    }
}
