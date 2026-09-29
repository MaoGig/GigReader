package com.maogig.gigreader.core.common.render

/**
 * Geometry of a continuous vertical document at zoom 1 ("fit width"), in pixels.
 *
 * Every page is scaled to fill the content width (pages of different widths are each fitted), so
 * page `i` occupies `[pageTop(i), pageTop(i) + pageHeight(i))` vertically and
 * `[pageLeft, pageLeft + contentWidth)` horizontally. Lookups are O(log n) via binary search over a
 * prefix-sum array, so a 5 000-page document costs two float arrays and nothing per frame.
 *
 * Instances are immutable; build a new one when the viewport width or the page sizes change.
 */
class DocumentLayout private constructor(
    val viewportWidth: Float,
    val pageLeft: Float,
    val contentWidth: Float,
    val pageGap: Float,
    private val tops: FloatArray,
    private val heights: FloatArray,
    private val aspect: FloatArray,
) {
    val pageCount: Int get() = heights.size

    /** Total scrollable height at zoom 1, including a gap above the first and below the last page. */
    val totalHeight: Float =
        if (heights.isEmpty()) 0f else tops[heights.size - 1] + heights[heights.size - 1] + pageGap

    fun pageTop(page: Int): Float = tops[page]

    fun pageHeight(page: Int): Float = heights[page]

    fun pageBottom(page: Int): Float = tops[page] + heights[page]

    /** Height / width of the page in PDF space. */
    fun pageAspect(page: Int): Float = aspect[page]

    /**
     * Index of the page at vertical position [y] (document px at zoom 1). Positions inside a gap
     * belong to the page below the gap; positions past the end clamp to the last page.
     */
    fun pageAt(y: Float): Int {
        if (heights.isEmpty()) return -1
        return firstPageEndingAfter(y).coerceAtMost(heights.size - 1)
    }

    /** Pages intersecting the vertical band `[top, bottom)`; empty when nothing intersects. */
    fun pagesIn(top: Float, bottom: Float): IntRange {
        if (heights.isEmpty() || bottom <= top) return IntRange.EMPTY
        val first = firstPageEndingAfter(top)
        val last = lastPageStartingBefore(bottom)
        return if (first > last) IntRange.EMPTY else first..last
    }

    /** Smallest `i` with `pageBottom(i) > y`, or [pageCount] if none. */
    private fun firstPageEndingAfter(y: Float): Int {
        var lo = 0
        var hi = heights.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tops[mid] + heights[mid] > y) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Largest `i` with `pageTop(i) < y`, or -1 if none. */
    private fun lastPageStartingBefore(y: Float): Int {
        var lo = -1
        var hi = heights.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] < y) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        /**
         * @param pageWidths page widths in PDF points (> 0).
         * @param pageHeights page heights in PDF points (> 0), same size as [pageWidths].
         * @param viewportWidth width of the viewport in px.
         * @param horizontalPadding space left and right of every page at zoom 1, in px.
         * @param pageGap vertical space between pages, in px.
         */
        fun build(
            pageWidths: FloatArray,
            pageHeights: FloatArray,
            viewportWidth: Float,
            horizontalPadding: Float,
            pageGap: Float,
        ): DocumentLayout {
            require(pageWidths.size == pageHeights.size) { "page width/height arrays differ in size" }
            require(viewportWidth > 0f) { "viewportWidth must be > 0" }
            val content = (viewportWidth - 2 * horizontalPadding).coerceAtLeast(1f)
            val n = pageWidths.size
            val tops = FloatArray(n)
            val heights = FloatArray(n)
            val aspect = FloatArray(n)
            var y = pageGap
            for (i in 0 until n) {
                val w = pageWidths[i].takeIf { it > 0f } ?: DEFAULT_PAGE_WIDTH_PT
                val h = pageHeights[i].takeIf { it > 0f } ?: DEFAULT_PAGE_HEIGHT_PT
                aspect[i] = h / w
                tops[i] = y
                heights[i] = content * aspect[i]
                y += heights[i] + pageGap
            }
            return DocumentLayout(viewportWidth, horizontalPadding, content, pageGap, tops, heights, aspect)
        }

        /** A4 portrait in points; used for pages whose size is not known yet. */
        const val DEFAULT_PAGE_WIDTH_PT = 595f
        const val DEFAULT_PAGE_HEIGHT_PT = 842f
    }
}
