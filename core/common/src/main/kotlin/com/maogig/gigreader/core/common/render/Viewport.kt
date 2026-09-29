package com.maogig.gigreader.core.common.render

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned rectangle in document coordinates (px at zoom 1). */
data class DocRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun intersect(other: DocRect): DocRect = DocRect(
        max(left, other.left),
        max(top, other.top),
        min(right, other.right),
        min(bottom, other.bottom),
    )
}

/**
 * Position of the viewport over the document. A document point `(x, y)` is drawn on screen at
 * `((x - offsetX) * zoom, (y - offsetY) * zoom)`; `zoom == 1` means "fit width".
 */
data class ViewportTransform(val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    fun toScreenX(docX: Float): Float = (docX - offsetX) * zoom
    fun toScreenY(docY: Float): Float = (docY - offsetY) * zoom
    fun toDocX(screenX: Float): Float = screenX / zoom + offsetX
    fun toDocY(screenY: Float): Float = screenY / zoom + offsetY
}

/** Reading position expressed independently of the viewport size (survives rotation/resizes). */
data class PagePosition(val page: Int, val pageOffset: Float)

/**
 * Pure viewport arithmetic: clamping, zooming around a focal point, panning and conversions between
 * scroll offsets and page positions. All functions are allocation-light and side-effect free, so the
 * UI layer can call them on every gesture event.
 */
class ViewportMath(
    val minZoom: Float = 1f,
    val maxZoom: Float = 8f,
) {
    init {
        require(minZoom > 0f && maxZoom >= minZoom)
    }

    fun visibleRect(t: ViewportTransform, viewportWidth: Float, viewportHeight: Float): DocRect =
        DocRect(t.offsetX, t.offsetY, t.offsetX + viewportWidth / t.zoom, t.offsetY + viewportHeight / t.zoom)

    fun clamp(t: ViewportTransform, layout: DocumentLayout, viewportWidth: Float, viewportHeight: Float): ViewportTransform =
        clampContinuous(t, minZoom, maxZoom, layout.viewportWidth, layout.totalHeight, viewportWidth, viewportHeight)

    /** Zooms to [newZoom] keeping the document point under the screen point ([focusX], [focusY]) fixed. */
    fun zoomAround(
        t: ViewportTransform,
        newZoom: Float,
        focusX: Float,
        focusY: Float,
        layout: DocumentLayout,
        viewportWidth: Float,
        viewportHeight: Float,
    ): ViewportTransform {
        val z = newZoom.coerceIn(minZoom, maxZoom)
        val docX = t.toDocX(focusX)
        val docY = t.toDocY(focusY)
        return clamp(ViewportTransform(z, docX - focusX / z, docY - focusY / z), layout, viewportWidth, viewportHeight)
    }

    /** Moves the content by a screen-space delta (positive dy = content follows the finger downwards). */
    fun panBy(
        t: ViewportTransform,
        dxScreen: Float,
        dyScreen: Float,
        layout: DocumentLayout,
        viewportWidth: Float,
        viewportHeight: Float,
    ): ViewportTransform = clamp(
        ViewportTransform(t.zoom, t.offsetX - dxScreen / t.zoom, t.offsetY - dyScreen / t.zoom),
        layout, viewportWidth, viewportHeight,
    )

    /** Page at the top edge of the viewport and how far (0..1) it is scrolled past. */
    fun topPosition(t: ViewportTransform, layout: DocumentLayout): PagePosition {
        if (layout.pageCount == 0) return PagePosition(0, 0f)
        val y = max(0f, t.offsetY)
        val page = layout.pageAt(y)
        val fraction = ((y - layout.pageTop(page)) / layout.pageHeight(page)).coerceIn(0f, 1f)
        return PagePosition(page, fraction)
    }

    /** Page crossing the vertical center of the viewport: the page shown in "page X / N". */
    fun currentPage(t: ViewportTransform, layout: DocumentLayout, viewportHeight: Float): Int {
        if (layout.pageCount == 0) return 0
        return layout.pageAt(t.offsetY + viewportHeight / t.zoom / 2f)
    }

    /** Transform that puts [position] at the top edge of the viewport at [zoom]. */
    fun transformFor(
        position: PagePosition,
        zoom: Float,
        layout: DocumentLayout,
        viewportWidth: Float,
        viewportHeight: Float,
    ): ViewportTransform {
        if (layout.pageCount == 0) return ViewportTransform(zoom.coerceIn(minZoom, maxZoom))
        val page = position.page.coerceIn(0, layout.pageCount - 1)
        val y = layout.pageTop(page) + layout.pageHeight(page) * position.pageOffset.coerceIn(0f, 1f)
        // Keep a sliver of the gap visible when jumping to the very top of a page.
        val offsetY = if (position.pageOffset == 0f) y - layout.pageGap else y
        return clamp(ViewportTransform(zoom, 0f, offsetY), layout, viewportWidth, viewportHeight)
    }

    // --- Mode-aware overloads: the layout owns its viewport size, see [ReadingLayout]. ---

    fun clamp(t: ViewportTransform, layout: ReadingLayout): ViewportTransform = layout.clamp(t, minZoom, maxZoom)

    fun zoomAround(t: ViewportTransform, newZoom: Float, focusX: Float, focusY: Float, layout: ReadingLayout): ViewportTransform {
        val z = newZoom.coerceIn(minZoom, maxZoom)
        val docX = t.toDocX(focusX)
        val docY = t.toDocY(focusY)
        return layout.clamp(ViewportTransform(z, docX - focusX / z, docY - focusY / z), minZoom, maxZoom)
    }

    fun panBy(t: ViewportTransform, dxScreen: Float, dyScreen: Float, layout: ReadingLayout): ViewportTransform =
        layout.clamp(ViewportTransform(t.zoom, t.offsetX - dxScreen / t.zoom, t.offsetY - dyScreen / t.zoom), minZoom, maxZoom)
}

/** Shared clamp of the continuous strip: centres an axis that is smaller than the viewport. */
internal fun clampContinuous(
    t: ViewportTransform,
    minZoom: Float,
    maxZoom: Float,
    docW: Float,
    docH: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): ViewportTransform {
    val zoom = t.zoom.coerceIn(minZoom, maxZoom)
    val visibleW = viewportWidth / zoom
    val visibleH = viewportHeight / zoom
    val x = if (visibleW >= docW) (docW - visibleW) / 2f else t.offsetX.coerceIn(0f, docW - visibleW)
    val y = if (visibleH >= docH) (docH - visibleH) / 2f else t.offsetY.coerceIn(0f, docH - visibleH)
    return if (zoom == t.zoom && x == t.offsetX && y == t.offsetY) t else ViewportTransform(zoom, x, y)
}
