package com.maogig.gigreader.core.common.render

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How pages are arranged. Mirrors `ReaderScrollMode` in core/model, which needs a third value
 * (`TWO_PAGE`) before the reader can map settings onto this enum.
 */
enum class ReadingMode { CONTINUOUS, PAGED, TWO_PAGE }

/** Which axis a page (or spread) is fitted to in the paged modes. */
enum class PageFit {
    /** Whole page/spread visible at zoom 1 (fit width or fit height, whichever is smaller). */
    PAGE,

    /** Fit the width; a page taller than the viewport scrolls vertically inside its unit. */
    WIDTH,
}

/**
 * Geometry and viewport rules of one reading mode, in document coordinates (px at zoom 1). The
 * layout owns the viewport size it was built for, so it is rebuilt (O(n)) when that size changes.
 *
 * A *unit* is the thing a page turn moves by: a page in [ReadingMode.CONTINUOUS] and
 * [ReadingMode.PAGED], a spread in [ReadingMode.TWO_PAGE]. In the paged modes unit `u` owns a
 * viewport-sized cell starting at `x = u * (viewportWidth + gap)`, so neighbouring units sit
 * side by side and a swipe simply moves `offsetX` between cells. Pages are always sorted by
 * position, so every query is O(log n) (or O(1)) over primitive arrays.
 *
 * Implementations: [ContinuousReadingLayout] (wraps [DocumentLayout]) and [PagedReadingLayout].
 * Build with [ReadingLayout.build].
 */
sealed interface ReadingLayout {
    val mode: ReadingMode
    val pageCount: Int
    val unitCount: Int
    val viewportWidth: Float
    val viewportHeight: Float

    /** Extent of the whole document at zoom 1. */
    val totalWidth: Float
    val totalHeight: Float

    // --- Page geometry (document coordinates) ---
    fun pageLeft(page: Int): Float
    fun pageTop(page: Int): Float
    fun pageWidth(page: Int): Float
    fun pageHeight(page: Int): Float

    /** Height / width of the page in PDF space. */
    fun pageAspect(page: Int): Float

    /**
     * Pages whose rectangle intersects [area]. Paged layouts test the horizontal extent only (pages
     * are ordered left to right and share one vertical band), continuous layouts the vertical one;
     * callers that need exactness on the other axis intersect with the page rect themselves.
     */
    fun pagesIn(area: DocRect): IntRange

    /** Page whose rectangle contains the point, or -1 (gaps, margins, outside): for links and taps. */
    fun pageAtPoint(x: Float, y: Float): Int

    // --- Units ---

    /** Unit at a document position; margins and gaps belong to the nearest unit, -1 for an empty document. */
    fun unitAt(x: Float, y: Float): Int
    fun unitOfPage(page: Int): Int
    fun firstPageOfUnit(unit: Int): Int
    fun lastPageOfUnit(unit: Int): Int

    /** Left edge of the viewport-sized cell of [unit] (always 0 for the continuous strip). */
    fun unitLeft(unit: Int): Float

    /** Union of the page rectangles of [unit]. */
    fun unitBounds(unit: Int): DocRect

    /** Next unit, or -1 at the end. */
    fun nextUnit(unit: Int): Int

    /** Previous unit, or -1 at the start. */
    fun previousUnit(unit: Int): Int

    // --- Viewport rules ---

    /** Snap target: transform that shows [unit] fitted (zoom 1) or centred at [zoom]. */
    fun transformForUnit(unit: Int, zoom: Float = 1f): ViewportTransform

    /** Applies zoom limits and the pan bounds of the mode (paged: bounded to the current unit while zoomed). */
    fun clamp(t: ViewportTransform, minZoom: Float, maxZoom: Float): ViewportTransform

    /** Unit under the viewport centre. */
    fun currentUnit(t: ViewportTransform): Int

    /** Page shown in "page X / N": continuous = page at the centre, paged = first page of the current unit. */
    fun currentPage(t: ViewportTransform): Int

    /** Reading position independent of the mode: first page of the current unit plus vertical fraction scrolled. */
    fun positionOf(t: ViewportTransform): PagePosition

    /** Inverse of [positionOf]; the page selects the unit, so any mode can restore any position. */
    fun transformFor(position: PagePosition, zoom: Float = 1f): ViewportTransform

    /**
     * Unit to snap to when a swipe ends at [t]. [velocityX] is the finger velocity in screen px/s
     * (negative = towards the next unit); above [flingVelocity] the swipe turns the page even when
     * it did not cross the midpoint. Zoomed views never turn the page (pan is clamped to the unit).
     * Continuous layouts do not snap: they return [currentUnit].
     */
    fun settleUnit(t: ViewportTransform, velocityX: Float = 0f, flingVelocity: Float = DEFAULT_FLING_VELOCITY): Int

    /**
     * Whether the viewport touches the left ([direction] < 0) or right (> 0) edge of what can be
     * panned in the current unit; always true when the unit is narrower than the viewport. Lets the
     * reader offer a page turn on an over-scroll while zoomed.
     */
    fun isAtHorizontalEdge(t: ViewportTransform, direction: Int): Boolean

    companion object {
        const val DEFAULT_FLING_VELOCITY = 800f

        /**
         * @param pageWidths page widths in PDF points; unknown (<= 0) sizes fall back to A4.
         * @param pageGap continuous: vertical gap between pages; paged: horizontal gap between cells.
         * @param padding continuous: horizontal margin left and right; paged: margin around each unit.
         * @param coverAlone [ReadingMode.TWO_PAGE] only: page 0 is shown alone, so spreads are (1,2), (3,4)...
         * @param fit paged modes only.
         */
        fun build(
            mode: ReadingMode,
            pageWidths: FloatArray,
            pageHeights: FloatArray,
            viewportWidth: Float,
            viewportHeight: Float,
            pageGap: Float,
            padding: Float = 0f,
            coverAlone: Boolean = true,
            fit: PageFit = PageFit.PAGE,
        ): ReadingLayout = when (mode) {
            ReadingMode.CONTINUOUS -> ContinuousReadingLayout(
                DocumentLayout.build(pageWidths, pageHeights, viewportWidth, padding, pageGap),
                viewportHeight,
            )
            ReadingMode.PAGED, ReadingMode.TWO_PAGE -> PagedReadingLayout.build(
                mode, pageWidths, pageHeights, viewportWidth, viewportHeight, pageGap, padding, coverAlone, fit,
            )
        }
    }
}

/** [ReadingLayout] over the existing vertical strip; behaviour is exactly that of [DocumentLayout] and [ViewportMath]. */
class ContinuousReadingLayout(
    val document: DocumentLayout,
    override val viewportHeight: Float,
) : ReadingLayout {
    override val mode: ReadingMode get() = ReadingMode.CONTINUOUS
    override val pageCount: Int get() = document.pageCount
    override val unitCount: Int get() = document.pageCount
    override val viewportWidth: Float get() = document.viewportWidth
    override val totalWidth: Float get() = document.viewportWidth
    override val totalHeight: Float get() = document.totalHeight

    override fun pageLeft(page: Int): Float = document.pageLeft
    override fun pageTop(page: Int): Float = document.pageTop(page)
    override fun pageWidth(page: Int): Float = document.contentWidth
    override fun pageHeight(page: Int): Float = document.pageHeight(page)
    override fun pageAspect(page: Int): Float = document.pageAspect(page)

    override fun pagesIn(area: DocRect): IntRange = document.pagesIn(area.top, area.bottom)

    override fun pageAtPoint(x: Float, y: Float): Int {
        if (pageCount == 0 || x < document.pageLeft || x >= document.pageLeft + document.contentWidth) return -1
        val page = document.pageAt(y)
        return if (y >= document.pageTop(page) && y < document.pageBottom(page)) page else -1
    }

    override fun unitAt(x: Float, y: Float): Int = document.pageAt(y)
    override fun unitOfPage(page: Int): Int = page
    override fun unitLeft(unit: Int): Float = 0f
    override fun firstPageOfUnit(unit: Int): Int = unit
    override fun lastPageOfUnit(unit: Int): Int = unit

    override fun unitBounds(unit: Int): DocRect =
        DocRect(document.pageLeft, document.pageTop(unit), document.pageLeft + document.contentWidth, document.pageBottom(unit))

    override fun nextUnit(unit: Int): Int = if (unit + 1 < pageCount) unit + 1 else -1
    override fun previousUnit(unit: Int): Int = if (unit > 0) unit - 1 else -1

    override fun transformForUnit(unit: Int, zoom: Float): ViewportTransform =
        transformFor(PagePosition(unit, 0f), zoom)

    override fun clamp(t: ViewportTransform, minZoom: Float, maxZoom: Float): ViewportTransform =
        clampContinuous(t, minZoom, maxZoom, document.viewportWidth, document.totalHeight, viewportWidth, viewportHeight)

    override fun currentUnit(t: ViewportTransform): Int = currentPage(t)

    override fun currentPage(t: ViewportTransform): Int =
        if (pageCount == 0) 0 else document.pageAt(t.offsetY + viewportHeight / t.zoom / 2f)

    override fun positionOf(t: ViewportTransform): PagePosition {
        if (pageCount == 0) return PagePosition(0, 0f)
        val y = max(0f, t.offsetY)
        val page = document.pageAt(y)
        return PagePosition(page, ((y - document.pageTop(page)) / document.pageHeight(page)).coerceIn(0f, 1f))
    }

    override fun transformFor(position: PagePosition, zoom: Float): ViewportTransform =
        ViewportMath(min(1f, zoom), max(1f, zoom)).transformFor(position, zoom, document, viewportWidth, viewportHeight)

    override fun settleUnit(t: ViewportTransform, velocityX: Float, flingVelocity: Float): Int = currentUnit(t)

    override fun isAtHorizontalEdge(t: ViewportTransform, direction: Int): Boolean {
        val visibleW = viewportWidth / t.zoom
        if (visibleW >= totalWidth) return true
        val eps = EDGE_EPSILON_PX / t.zoom
        return if (direction < 0) t.offsetX <= eps else t.offsetX + visibleW >= totalWidth - eps
    }
}

/**
 * Horizontally paged layout: one page per unit ([ReadingMode.PAGED]) or a spread of two
 * ([ReadingMode.TWO_PAGE]). Each unit is fitted to the viewport minus [padding] and centred in its
 * cell; pages of a spread share one scale (physical sizes stay comparable), touch each other and
 * are centred vertically on each other. A lone cover or trailing odd page is centred alone.
 *
 * Storage is four float arrays over pages; unit boundaries follow from arithmetic, so building is
 * O(n) and every query O(log n) with no per-page objects.
 */
class PagedReadingLayout private constructor(
    override val mode: ReadingMode,
    override val viewportWidth: Float,
    override val viewportHeight: Float,
    val pageGap: Float,
    val padding: Float,
    val coverAlone: Boolean,
    private val lefts: FloatArray,
    private val tops: FloatArray,
    private val widths: FloatArray,
    private val heights: FloatArray,
    override val unitCount: Int,
    override val totalHeight: Float,
) : ReadingLayout {
    override val pageCount: Int get() = lefts.size

    private val stride: Float get() = viewportWidth + pageGap
    private val spreads: Boolean get() = mode == ReadingMode.TWO_PAGE

    override val totalWidth: Float = if (unitCount == 0) 0f else unitCount * (viewportWidth + pageGap) - pageGap

    override fun unitLeft(unit: Int): Float = unit * stride

    override fun pageLeft(page: Int): Float = lefts[page]
    override fun pageTop(page: Int): Float = tops[page]
    override fun pageWidth(page: Int): Float = widths[page]
    override fun pageHeight(page: Int): Float = heights[page]
    override fun pageAspect(page: Int): Float = heights[page] / widths[page]

    override fun pagesIn(area: DocRect): IntRange {
        if (pageCount == 0 || area.isEmpty) return IntRange.EMPTY
        // Smallest i with right(i) > area.left.
        var lo = 0
        var hi = pageCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lefts[mid] + widths[mid] > area.left) hi = mid else lo = mid + 1
        }
        val first = lo
        val last = lastPageStartingBefore(area.right)
        return if (first > last) IntRange.EMPTY else first..last
    }

    /** Largest `i` with `left(i) < x`, or -1. */
    private fun lastPageStartingBefore(x: Float): Int {
        var lo = -1
        var hi = pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lefts[mid] < x) lo = mid else hi = mid - 1
        }
        return lo
    }

    override fun pageAtPoint(x: Float, y: Float): Int {
        val i = lastPageStartingBefore(x + Math.ulp(x)) // largest i with left(i) <= x
        if (i < 0 || x >= lefts[i] + widths[i] || y < tops[i] || y >= tops[i] + heights[i]) return -1
        return i
    }

    override fun unitAt(x: Float, y: Float): Int {
        if (unitCount == 0) return -1
        return floor((x + pageGap / 2f) / stride).toInt().coerceIn(0, unitCount - 1)
    }

    override fun unitOfPage(page: Int): Int {
        val p = page.coerceIn(0, max(0, pageCount - 1))
        return when {
            !spreads -> p
            coverAlone -> (p + 1) / 2
            else -> p / 2
        }
    }

    override fun firstPageOfUnit(unit: Int): Int = when {
        !spreads -> unit
        coverAlone -> if (unit == 0) 0 else 2 * unit - 1
        else -> 2 * unit
    }

    override fun lastPageOfUnit(unit: Int): Int = min(pageCount - 1, firstPageOfUnit(unit) + pagesInUnit(unit) - 1)

    private fun pagesInUnit(unit: Int): Int = when {
        !spreads -> 1
        coverAlone && unit == 0 -> 1
        else -> min(2, pageCount - firstPageOfUnit(unit))
    }

    override fun unitBounds(unit: Int): DocRect {
        val first = firstPageOfUnit(unit)
        val last = lastPageOfUnit(unit)
        return DocRect(
            lefts[first],
            min(tops[first], tops[last]),
            lefts[last] + widths[last],
            max(tops[first] + heights[first], tops[last] + heights[last]),
        )
    }

    override fun nextUnit(unit: Int): Int = if (unit + 1 < unitCount) unit + 1 else -1
    override fun previousUnit(unit: Int): Int = if (unit > 0) unit - 1 else -1

    override fun transformForUnit(unit: Int, zoom: Float): ViewportTransform {
        if (unitCount == 0) return ViewportTransform(zoom)
        val u = unit.coerceIn(0, unitCount - 1)
        val visibleW = viewportWidth / zoom
        val x = unitLeft(u) + viewportWidth / 2f - visibleW / 2f
        return clampOffsets(ViewportTransform(zoom, x, regionTop(u)), u)
    }

    // Scroll region of a unit: its content inflated by the padding.
    private fun regionLeft(unit: Int) = lefts[firstPageOfUnit(unit)] - padding
    private fun regionRight(unit: Int): Float { val l = lastPageOfUnit(unit); return lefts[l] + widths[l] + padding }
    private fun regionTop(unit: Int): Float = unitBounds(unit).top - padding
    private fun regionBottom(unit: Int): Float = unitBounds(unit).bottom + padding

    override fun clamp(t: ViewportTransform, minZoom: Float, maxZoom: Float): ViewportTransform {
        val zoom = t.zoom.coerceIn(minZoom, maxZoom)
        if (unitCount == 0) return if (zoom == t.zoom) t else ViewportTransform(zoom, 0f, 0f)
        val u = unitAt(t.offsetX + viewportWidth / zoom / 2f, 0f)
        val r = clampOffsets(ViewportTransform(zoom, t.offsetX, t.offsetY), u)
        return if (r.zoom == t.zoom && r.offsetX == t.offsetX && r.offsetY == t.offsetY) t else r
    }

    /**
     * Pan bounds around unit [u]. Vertically: centred when the unit fits, else clamped to it.
     * Horizontally: at zoom 1 offsets roam freely across all cells (that is the swipe); zoomed, they
     * are bounded to the unit so a pan never reveals the neighbour.
     */
    private fun clampOffsets(t: ViewportTransform, u: Int): ViewportTransform {
        val zoom = t.zoom
        val visibleW = viewportWidth / zoom
        val visibleH = viewportHeight / zoom
        val top = regionTop(u)
        val bottom = regionBottom(u)
        val y = if (visibleH >= bottom - top) (top + bottom) / 2f - visibleH / 2f else t.offsetY.coerceIn(top, bottom - visibleH)
        val x = if (zoom <= UNZOOMED) {
            if (totalWidth <= visibleW) totalWidth / 2f - visibleW / 2f else t.offsetX.coerceIn(0f, totalWidth - visibleW)
        } else {
            val left = regionLeft(u)
            val right = regionRight(u)
            if (visibleW >= right - left) (left + right) / 2f - visibleW / 2f else t.offsetX.coerceIn(left, right - visibleW)
        }
        return ViewportTransform(zoom, x, y)
    }

    override fun currentUnit(t: ViewportTransform): Int =
        if (unitCount == 0) 0 else unitAt(t.offsetX + viewportWidth / t.zoom / 2f, 0f)

    override fun currentPage(t: ViewportTransform): Int =
        if (unitCount == 0) 0 else firstPageOfUnit(currentUnit(t))

    override fun positionOf(t: ViewportTransform): PagePosition {
        if (unitCount == 0) return PagePosition(0, 0f)
        val page = currentPage(t)
        val fraction = ((t.offsetY - tops[page]) / heights[page]).coerceIn(0f, 1f)
        return PagePosition(page, fraction)
    }

    override fun transformFor(position: PagePosition, zoom: Float): ViewportTransform {
        if (unitCount == 0) return ViewportTransform(zoom)
        val u = unitOfPage(position.page)
        val base = transformForUnit(u, zoom)
        if (position.pageOffset <= 0f) return base
        val page = firstPageOfUnit(u)
        val y = tops[page] + heights[page] * position.pageOffset.coerceIn(0f, 1f)
        return clampOffsets(ViewportTransform(zoom, base.offsetX, y), u)
    }

    override fun settleUnit(t: ViewportTransform, velocityX: Float, flingVelocity: Float): Int {
        if (unitCount == 0) return 0
        if (t.zoom > UNZOOMED) return currentUnit(t)
        val progress = t.offsetX / stride
        val target = when {
            velocityX <= -flingVelocity -> floor(progress + 1e-3f).toInt() + 1
            velocityX >= flingVelocity -> ceil(progress - 1e-3f).toInt() - 1
            else -> progress.roundToInt()
        }
        return target.coerceIn(0, unitCount - 1)
    }

    override fun isAtHorizontalEdge(t: ViewportTransform, direction: Int): Boolean {
        if (unitCount == 0) return true
        val u = currentUnit(t)
        val visibleW = viewportWidth / t.zoom
        val left = regionLeft(u)
        val right = regionRight(u)
        if (visibleW >= right - left) return true
        val eps = EDGE_EPSILON_PX / t.zoom
        return if (direction < 0) t.offsetX <= left + eps else t.offsetX + visibleW >= right - eps
    }

    companion object {
        /** Zoom at or below which the view counts as "fitted" (free horizontal swipe between units). */
        const val UNZOOMED = 1.001f

        fun build(
            mode: ReadingMode,
            pageWidths: FloatArray,
            pageHeights: FloatArray,
            viewportWidth: Float,
            viewportHeight: Float,
            pageGap: Float,
            padding: Float,
            coverAlone: Boolean,
            fit: PageFit,
        ): PagedReadingLayout {
            require(mode != ReadingMode.CONTINUOUS) { "use ContinuousReadingLayout" }
            require(pageWidths.size == pageHeights.size) { "page width/height arrays differ in size" }
            require(viewportWidth > 0f && viewportHeight > 0f) { "viewport must be > 0" }
            val n = pageWidths.size
            val spreads = mode == ReadingMode.TWO_PAGE
            val lefts = FloatArray(n)
            val tops = FloatArray(n)
            val widths = FloatArray(n)
            val heights = FloatArray(n)
            val availW = max(1f, viewportWidth - 2 * padding)
            val availH = max(1f, viewportHeight - 2 * padding)
            val stride = viewportWidth + pageGap
            var totalH = 0f
            var page = 0
            var unit = 0
            while (page < n) {
                val count = if (spreads && !(coverAlone && unit == 0)) min(2, n - page) else 1
                var sumW = 0f
                var maxH = 0f
                for (i in page until page + count) {
                    sumW += pageWidths[i].takeIf { it > 0f } ?: DocumentLayout.DEFAULT_PAGE_WIDTH_PT
                    maxH = max(maxH, pageHeights[i].takeIf { it > 0f } ?: DocumentLayout.DEFAULT_PAGE_HEIGHT_PT)
                }
                val scale = when (fit) {
                    PageFit.PAGE -> min(availW / sumW, availH / maxH)
                    PageFit.WIDTH -> availW / sumW
                }
                val contentW = sumW * scale
                val contentH = maxH * scale
                var x = unit * stride + (viewportWidth - contentW) / 2f
                val y0 = if (contentH + 2 * padding <= viewportHeight) (viewportHeight - contentH) / 2f else padding
                for (i in page until page + count) {
                    val w = pageWidths[i].takeIf { it > 0f } ?: DocumentLayout.DEFAULT_PAGE_WIDTH_PT
                    val h = pageHeights[i].takeIf { it > 0f } ?: DocumentLayout.DEFAULT_PAGE_HEIGHT_PT
                    widths[i] = w * scale
                    heights[i] = h * scale
                    lefts[i] = x
                    tops[i] = y0 + (contentH - heights[i]) / 2f
                    x += widths[i]
                }
                totalH = max(totalH, y0 + contentH + padding)
                page += count
                unit++
            }
            return PagedReadingLayout(
                mode, viewportWidth, viewportHeight, pageGap, padding, coverAlone,
                lefts, tops, widths, heights, unit, max(totalH, if (n == 0) 0f else viewportHeight),
            )
        }
    }
}

private const val EDGE_EPSILON_PX = 0.5f

/** Keeps the reading position when the user switches [ReadingMode]. */
object ReadingPositions {
    /**
     * Maps a position produced by [ReadingLayout.positionOf] of [from] onto [to]. The page is
     * clamped to the document. Leaving the continuous strip for a paged mode rounds to the page
     * that fills most of the screen (offset >= 0.5 selects the next page); the fraction only
     * survives when [to] is continuous, where it is the scroll offset within the page.
     */
    fun convert(position: PagePosition, from: ReadingLayout, to: ReadingLayout): PagePosition {
        if (to.pageCount == 0) return PagePosition(0, 0f)
        val last = to.pageCount - 1
        var page = position.page.coerceIn(0, last)
        if (to.mode == ReadingMode.CONTINUOUS) {
            return PagePosition(page, position.pageOffset.coerceIn(0f, 1f))
        }
        if (from.mode == ReadingMode.CONTINUOUS && position.pageOffset >= 0.5f && page < last) page++
        return PagePosition(page, 0f)
    }

    /** Transform of [to] showing the same place that [t] shows in [from]. */
    fun switchTransform(t: ViewportTransform, from: ReadingLayout, to: ReadingLayout, zoom: Float = 1f): ViewportTransform =
        to.transformFor(convert(from.positionOf(t), from, to), zoom)
}

