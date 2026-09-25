package com.maogig.gigreader.feature.reader.viewport

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maogig.gigreader.core.common.render.DocumentLayout
import com.maogig.gigreader.core.common.render.PagePosition
import com.maogig.gigreader.core.common.render.ViewportMath
import com.maogig.gigreader.core.common.render.ViewportTransform
import com.maogig.gigreader.core.common.render.ZoomBuckets

/**
 * State of the document viewport: the transform (zoom + offsets) over a [DocumentLayout].
 *
 * All values are snapshot state but are meant to be read only in the draw phase or in
 * `snapshotFlow`s, never in composition, so scrolling and zooming redraw without recomposing.
 * Geometry is delegated to the pure, unit-tested [ViewportMath].
 */
@Stable
class PdfViewportState(
    initialPosition: PagePosition,
    initialZoom: Float,
    val math: ViewportMath = ViewportMath(minZoom = 1f, maxZoom = 8f),
) {
    var transform: ViewportTransform by mutableStateOf(ViewportTransform(initialZoom.coerceIn(math.minZoom, math.maxZoom)))
        private set

    var layout: DocumentLayout? by mutableStateOf(null)
        private set

    var viewportWidth: Float by mutableFloatStateOf(0f)
        private set

    var viewportHeight: Float by mutableFloatStateOf(0f)
        private set

    /** True while the zoom level is changing (pinch or zoom animation): tiles are not planned meanwhile. */
    var isZooming: Boolean by mutableStateOf(false)
        private set

    /** Zoom bucket whose tiles are drawn; follows the zoom only once it settles. */
    var tileBucket: Int by mutableIntStateOf(ZoomBuckets.bucketFor(initialZoom))
        private set

    /** Position to restore once the first layout arrives (or after the layout is rebuilt). */
    private var pendingPosition: PagePosition? = initialPosition

    val isReady: Boolean get() = layout != null && viewportWidth > 0f && viewportHeight > 0f

    /** Page shown in "page X / N" (the page crossing the vertical center). */
    val currentPage: Int
        get() {
            val l = layout ?: return pendingPosition?.page ?: 0
            return math.currentPage(transform, l, viewportHeight)
        }

    /** Position of the top edge, used for persistence (independent of viewport size). */
    fun topPosition(): PagePosition {
        val l = layout ?: return pendingPosition ?: PagePosition(0, 0f)
        return math.topPosition(transform, l)
    }

    /**
     * Installs a new layout (first open, measured page sizes, rotation, window resize, page-gap
     * change) while keeping the page under the top edge in place.
     */
    fun updateLayout(newLayout: DocumentLayout, width: Float, height: Float) {
        val old = layout
        if (old === newLayout && width == viewportWidth && height == viewportHeight) return
        val anchor = pendingPosition ?: old?.let { math.topPosition(transform, it) } ?: PagePosition(0, 0f)
        val horizontalFraction = if (old != null && old.viewportWidth > 0f) transform.offsetX / old.viewportWidth else 0f
        layout = newLayout
        viewportWidth = width
        viewportHeight = height
        pendingPosition = null
        val placed = math.transformFor(anchor, transform.zoom, newLayout, width, height)
        transform = math.clamp(placed.copy(offsetX = horizontalFraction * newLayout.viewportWidth), newLayout, width, height)
    }

    /** Moves by a screen-space delta. Returns `true` if the content actually moved. */
    fun panBy(dx: Float, dy: Float): Boolean {
        val l = layout ?: return false
        val before = transform
        transform = math.panBy(before, dx, dy, l, viewportWidth, viewportHeight)
        return transform != before
    }

    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val l = layout ?: return
        transform = math.zoomAround(transform, transform.zoom * factor, focusX, focusY, l, viewportWidth, viewportHeight)
    }

    /** Zoom relative to a fixed [start] transform (used by animations so the focal point never drifts). */
    fun zoomFrom(start: ViewportTransform, zoom: Float, focusX: Float, focusY: Float) {
        val l = layout ?: return
        transform = math.zoomAround(start, zoom, focusX, focusY, l, viewportWidth, viewportHeight)
    }

    fun scrollToOffsetY(offsetY: Float) {
        val l = layout ?: return
        transform = math.clamp(transform.copy(offsetY = offsetY), l, viewportWidth, viewportHeight)
    }

    /** Target vertical offset that shows [page] from its top at the current zoom. */
    fun offsetForPage(page: Int): Float? {
        val l = layout ?: return null
        return math.transformFor(PagePosition(page, 0f), transform.zoom, l, viewportWidth, viewportHeight).offsetY
    }

    fun goToPage(page: Int) {
        offsetForPage(page)?.let(::scrollToOffsetY)
    }

    fun onZoomStart() {
        isZooming = true
    }

    /** Called when a zoom gesture or animation ends: tiles for the final zoom get planned. */
    fun onZoomSettled() {
        isZooming = false
        tileBucket = ZoomBuckets.bucketFor(transform.zoom)
    }
}
