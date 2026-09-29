package com.maogig.gigreader.feature.reader.nav

/** Decides which thumbnails to render and in which order. Pure. */
object ThumbnailPlanner {
    /**
     * Pages to render for a grid showing [visible]: the visible ones first (top to bottom), then
     * [prefetch] pages after them, then [prefetch] before them (nearest first). Empty when nothing
     * is visible; clamped to `0 until pageCount`.
     */
    fun order(visible: IntRange, prefetch: Int, pageCount: Int): List<Int> {
        if (pageCount <= 0 || visible.isEmpty()) return emptyList()
        val first = visible.first.coerceIn(0, pageCount - 1)
        val last = visible.last.coerceIn(first, pageCount - 1)
        val out = ArrayList<Int>(last - first + 1 + 2 * prefetch.coerceAtLeast(0))
        for (p in first..last) out += p
        for (i in 1..prefetch) if (last + i < pageCount) out += last + i
        for (i in 1..prefetch) if (first - i >= 0) out += first - i
        return out
    }

    /** Thumbnail bitmap size for a page of [aspect] (height / width) at [widthPx], height capped at 2 x width. */
    fun heightFor(widthPx: Int, aspect: Float): Int {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else DEFAULT_ASPECT
        return (widthPx * a).toInt().coerceIn(1, widthPx * 2)
    }

    const val DEFAULT_ASPECT = 842f / 595f
}
