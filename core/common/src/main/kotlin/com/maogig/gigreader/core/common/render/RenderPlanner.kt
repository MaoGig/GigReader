package com.maogig.gigreader.core.common.render

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Identifies a bitmap in the page caches. Keys are value types so lookups never allocate strings. */
sealed interface RenderKey {
    val page: Int
}

/**
 * Whole page rendered at base resolution ([widthPx] × [heightPx]): fit width, capped at
 * [RenderPlanner.maxBaseWidthPx] so that a tablet page never costs tens of megabytes.
 */
data class PageKey(override val page: Int, val widthPx: Int, val heightPx: Int) : RenderKey

/**
 * One square tile of a page rendered at `zoom == ZoomBuckets.scale(bucket)`. The tile covers
 * `[col * tileSize, (col + 1) * tileSize)` × `[row * tileSize, (row + 1) * tileSize)` of the page
 * rendered at `pageWidthPx × pageHeightPx`; edge tiles are clipped to the page.
 */
data class TileKey(
    override val page: Int,
    val bucket: Int,
    val col: Int,
    val row: Int,
    val tileSize: Int,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
) : RenderKey {
    val left: Int get() = col * tileSize
    val top: Int get() = row * tileSize
    val right: Int get() = min(left + tileSize, pageWidthPx)
    val bottom: Int get() = min(top + tileSize, pageHeightPx)
}

/**
 * Discrete zoom levels at which tiles are rendered: `2^(k/3)` (1, 1.26, 1.59, 2, 2.52, …). Rendering
 * at the next bucket at or above the current zoom keeps text crisp while bounding overdraw to ~26 %
 * per axis, and means small zoom changes reuse cached tiles instead of re-rendering.
 */
object ZoomBuckets {
    private const val STEPS_PER_DOUBLING = 3

    fun bucketFor(zoom: Float): Int = max(0, ceil(ln(zoom.toDouble()) / ln(2.0) * STEPS_PER_DOUBLING - 1e-4).toInt())

    fun scale(bucket: Int): Float = 2.0.pow(bucket.toDouble() / STEPS_PER_DOUBLING).toFloat()
}

/**
 * What the viewport needs right now. [keys] is the render priority: visible pages (closest to the
 * viewport center first), then high-resolution tiles of visible pages, then prefetch neighbours.
 */
data class RenderPlan(
    /** Base renders of visible pages, closest to the center first. */
    val pages: List<PageKey>,
    /** High-resolution tiles for visible pages; empty when the base resolution is sufficient. */
    val tiles: List<TileKey>,
    /** Base renders of neighbouring pages, so scrolling reveals rendered content. */
    val prefetch: List<PageKey> = emptyList(),
) {
    val keys: List<RenderKey> by lazy(LazyThreadSafetyMode.PUBLICATION) { pages + tiles + prefetch }

    companion object {
        val Empty = RenderPlan(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Decides which bitmaps the viewport needs. Rendering is strictly on demand: only visible pages
 * (plus [prefetchPages] neighbours on each side) get a base bitmap, and tiles are only planned for
 * visible parts of visible pages when the displayed resolution exceeds the base resolution by more
 * than [tileThreshold].
 */
class RenderPlanner(
    val tileSize: Int = 512,
    val prefetchPages: Int = 1,
    val tileThreshold: Float = 1.15f,
    val maxBaseWidthPx: Int = 1440,
) {
    init {
        require(tileSize >= 64) { "tileSize too small" }
        require(prefetchPages >= 0)
        require(maxBaseWidthPx >= 64)
    }

    fun baseWidthPx(layout: DocumentLayout): Int = min(maxBaseWidthPx, max(1, layout.contentWidth.roundToInt()))

    /**
     * Base bitmap for [page]. Unusually tall pages (scrolls, posters, fold-outs) are rendered at a
     * lower width so no base bitmap exceeds [MAX_BASE_HEIGHT_PX] or [MAX_BASE_BYTES]: the canvas
     * refuses to draw huge bitmaps, and tiles restore sharpness where the base is too coarse.
     */
    fun baseKey(layout: DocumentLayout, page: Int): PageKey =
        baseKey(page, baseWidthPx(layout), layout.pageAspect(page))

    private fun baseKey(page: Int, baseWidth: Int, aspect: Float): PageKey {
        var w = baseWidth.toFloat()
        if (w * aspect > MAX_BASE_HEIGHT_PX) w = MAX_BASE_HEIGHT_PX / aspect
        if (w * w * aspect * 4f > MAX_BASE_BYTES) w = sqrt(MAX_BASE_BYTES / 4f / aspect)
        val width = max(1, w.toInt())
        val height = max(1, (width * aspect).roundToInt())
        return PageKey(page, width, height)
    }

    /** Base bitmap for [page] of any reading mode: fitted page width, capped like the continuous one. */
    fun baseKey(layout: ReadingLayout, page: Int): PageKey =
        baseKey(page, min(maxBaseWidthPx, max(1, layout.pageWidth(page).roundToInt())), layout.pageAspect(page))

    /** Whether [page]'s base bitmap is sharp enough at [zoom] in any reading mode. */
    fun needsTiles(layout: ReadingLayout, page: Int, zoom: Float): Boolean =
        zoom * layout.pageWidth(page) > baseKey(layout, page).widthPx * tileThreshold

    /** Whether [page]'s base bitmap is sharp enough at [zoom], or tiles are needed on top of it. */
    fun needsTiles(layout: DocumentLayout, page: Int, zoom: Float): Boolean =
        zoom * layout.contentWidth > baseKey(layout, page).widthPx * tileThreshold

    /**
     * @param includeTiles pass `false` while a pinch gesture is in progress: tiles for intermediate
     * zoom levels would be thrown away, so only base pages are rendered until the gesture settles.
     */
    fun plan(
        layout: DocumentLayout,
        transform: ViewportTransform,
        viewportWidth: Float,
        viewportHeight: Float,
        includeTiles: Boolean = true,
    ): RenderPlan {
        if (layout.pageCount == 0 || viewportWidth <= 0f || viewportHeight <= 0f) return RenderPlan.Empty
        val visible = DocRect(
            transform.offsetX,
            transform.offsetY,
            transform.offsetX + viewportWidth / transform.zoom,
            transform.offsetY + viewportHeight / transform.zoom,
        )
        val visiblePages = layout.pagesIn(visible.top, visible.bottom)
        val centerY = (visible.top + visible.bottom) / 2f

        val visibleOrder = ArrayList<Int>()
        val prefetchOrder = ArrayList<Int>()
        if (!visiblePages.isEmpty()) {
            visiblePages.sortedBy { abs(pageCenter(layout, it) - centerY) }.forEach(visibleOrder::add)
            for (d in 1..prefetchPages) {
                val below = visiblePages.last + d
                val above = visiblePages.first - d
                if (below < layout.pageCount) prefetchOrder.add(below)
                if (above >= 0) prefetchOrder.add(above)
            }
        } else {
            // Viewport over a gap or past the end: still prefetch the nearest page.
            prefetchOrder.add(layout.pageAt(centerY))
        }
        val pages = visibleOrder.map { baseKey(layout, it) }
        val prefetch = prefetchOrder.map { baseKey(layout, it) }

        if (!includeTiles || visiblePages.isEmpty()) {
            return RenderPlan(pages, emptyList(), prefetch)
        }

        val bucket = ZoomBuckets.bucketFor(transform.zoom)
        val scale = ZoomBuckets.scale(bucket)
        val tiles = ArrayList<TileKey>()
        val centerX = (visible.left + visible.right) / 2f
        for (page in visiblePages) {
            if (!needsTiles(layout, page, transform.zoom)) continue
            val pageRect = DocRect(
                layout.pageLeft,
                layout.pageTop(page),
                layout.pageLeft + layout.contentWidth,
                layout.pageBottom(page),
            )
            val area = visible.intersect(pageRect)
            if (area.isEmpty) continue
            val pageWidthPx = max(1, (layout.contentWidth * scale).roundToInt())
            val pageHeightPx = max(1, (pageWidthPx * layout.pageAspect(page)).roundToInt())
            // Visible part of the page in rendered-page pixels.
            val left = (area.left - pageRect.left) * scale
            val top = (area.top - pageRect.top) * scale
            val right = (area.right - pageRect.left) * scale
            val bottom = (area.bottom - pageRect.top) * scale
            val colStart = max(0, floor(left / tileSize).toInt())
            val rowStart = max(0, floor(top / tileSize).toInt())
            val colEnd = min((pageWidthPx - 1) / tileSize, floor((right - 0.001f) / tileSize).toInt())
            val rowEnd = min((pageHeightPx - 1) / tileSize, floor((bottom - 0.001f) / tileSize).toInt())
            for (row in rowStart..rowEnd) {
                for (col in colStart..colEnd) {
                    tiles.add(TileKey(page, bucket, col, row, tileSize, pageWidthPx, pageHeightPx))
                }
            }
        }
        tiles.sortBy { tile ->
            val cx = layout.pageLeft + (tile.left + tile.right) / 2f / scale
            val cy = layout.pageTop(tile.page) + (tile.top + tile.bottom) / 2f / scale
            val dx = cx - centerX
            val dy = cy - centerY
            dx * dx + dy * dy
        }
        return RenderPlan(pages, tiles, prefetch)
    }

    /**
     * Plan for any [ReadingMode]. Continuous layouts delegate to the [DocumentLayout] overload
     * unchanged. In the paged modes the visible pages are those of the units on screen (two units
     * while a swipe is in flight), and [prefetchPages] counts *units*: the pages of the adjacent
     * units are prefetched, next before previous.
     */
    fun plan(
        layout: ReadingLayout,
        transform: ViewportTransform,
        includeTiles: Boolean = true,
    ): RenderPlan {
        if (layout is ContinuousReadingLayout) {
            return plan(layout.document, transform, layout.viewportWidth, layout.viewportHeight, includeTiles)
        }
        val viewportWidth = layout.viewportWidth
        val viewportHeight = layout.viewportHeight
        if (layout.pageCount == 0 || viewportWidth <= 0f || viewportHeight <= 0f) return RenderPlan.Empty
        val visible = DocRect(
            transform.offsetX,
            transform.offsetY,
            transform.offsetX + viewportWidth / transform.zoom,
            transform.offsetY + viewportHeight / transform.zoom,
        )
        val centerX = (visible.left + visible.right) / 2f
        val centerY = (visible.top + visible.bottom) / 2f
        val visiblePages = ArrayList<Int>()
        for (p in layout.pagesIn(visible)) {
            if (layout.pageTop(p) < visible.bottom && layout.pageTop(p) + layout.pageHeight(p) > visible.top) visiblePages.add(p)
        }
        val prefetchOrder = ArrayList<Int>()
        if (visiblePages.isNotEmpty()) {
            visiblePages.sortBy { p ->
                val dx = layout.pageLeft(p) + layout.pageWidth(p) / 2f - centerX
                val dy = layout.pageTop(p) + layout.pageHeight(p) / 2f - centerY
                dx * dx + dy * dy
            }
            val firstUnit = layout.unitOfPage(visiblePages.min())
            val lastUnit = layout.unitOfPage(visiblePages.max())
            for (d in 1..prefetchPages) {
                if (lastUnit + d < layout.unitCount) addUnitPages(layout, lastUnit + d, prefetchOrder)
                if (firstUnit - d >= 0) addUnitPages(layout, firstUnit - d, prefetchOrder)
            }
        } else {
            addUnitPages(layout, layout.unitAt(centerX, centerY), prefetchOrder)
        }
        val pages = visiblePages.map { baseKey(layout, it) }
        val prefetch = prefetchOrder.map { baseKey(layout, it) }
        if (!includeTiles || visiblePages.isEmpty()) return RenderPlan(pages, emptyList(), prefetch)

        val bucket = ZoomBuckets.bucketFor(transform.zoom)
        val scale = ZoomBuckets.scale(bucket)
        val tiles = ArrayList<TileKey>()
        for (page in visiblePages) {
            if (!needsTiles(layout, page, transform.zoom)) continue
            val pageLeft = layout.pageLeft(page)
            val pageTop = layout.pageTop(page)
            val pageRect = DocRect(pageLeft, pageTop, pageLeft + layout.pageWidth(page), pageTop + layout.pageHeight(page))
            val area = visible.intersect(pageRect)
            if (area.isEmpty) continue
            val pageWidthPx = max(1, (layout.pageWidth(page) * scale).roundToInt())
            val pageHeightPx = max(1, (pageWidthPx * layout.pageAspect(page)).roundToInt())
            val left = (area.left - pageLeft) * scale
            val top = (area.top - pageTop) * scale
            val right = (area.right - pageLeft) * scale
            val bottom = (area.bottom - pageTop) * scale
            val colStart = max(0, floor(left / tileSize).toInt())
            val rowStart = max(0, floor(top / tileSize).toInt())
            val colEnd = min((pageWidthPx - 1) / tileSize, floor((right - 0.001f) / tileSize).toInt())
            val rowEnd = min((pageHeightPx - 1) / tileSize, floor((bottom - 0.001f) / tileSize).toInt())
            for (row in rowStart..rowEnd) {
                for (col in colStart..colEnd) {
                    tiles.add(TileKey(page, bucket, col, row, tileSize, pageWidthPx, pageHeightPx))
                }
            }
        }
        tiles.sortBy { tile ->
            val dx = layout.pageLeft(tile.page) + (tile.left + tile.right) / 2f / scale - centerX
            val dy = layout.pageTop(tile.page) + (tile.top + tile.bottom) / 2f / scale - centerY
            dx * dx + dy * dy
        }
        return RenderPlan(pages, tiles, prefetch)
    }

    private fun addUnitPages(layout: ReadingLayout, unit: Int, out: MutableList<Int>) {
        if (unit < 0) return
        for (p in layout.firstPageOfUnit(unit)..layout.lastPageOfUnit(unit)) out.add(p)
    }

    private fun pageCenter(layout: DocumentLayout, page: Int): Float = layout.pageTop(page) + layout.pageHeight(page) / 2f

    companion object {
        const val MAX_BASE_HEIGHT_PX = 8192f
        const val MAX_BASE_BYTES = 32f * 1024f * 1024f
    }
}
