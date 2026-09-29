package com.maogig.gigreader.feature.reader.nav

import androidx.compose.runtime.Immutable
import com.maogig.gigreader.core.common.render.DocumentLayout
import com.maogig.gigreader.core.common.render.ViewportTransform
import com.maogig.gigreader.core.pdf.engine.PageLink

sealed interface LinkTarget {
    /** Jump inside the document to zero-based [page]; [yFraction] is the vertical target, if any. */
    data class Internal(val page: Int, val yFraction: Float?) : LinkTarget

    /** A web or mail address; opened only after the user confirms. */
    data class External(val uri: String) : LinkTarget
}

/** A tappable rectangle in normalized (0..1) page coordinates. Plain floats so it is JVM-testable. */
@Immutable
class LinkRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val target: LinkTarget,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

/** A point on a page: [x] and [y] are normalized to the page (values outside 0..1 are beside it). */
data class PageHit(val page: Int, val x: Float, val y: Float)

object LinkHits {
    /** Converts engine links into plain regions. */
    fun regionsOf(links: List<PageLink>): List<LinkRegion> = links.map { link ->
        val b = link.bounds
        val target = when (link) {
            is PageLink.Internal -> LinkTarget.Internal(link.page, link.yFraction)
            is PageLink.External -> LinkTarget.External(link.uri)
        }
        LinkRegion(b.left, b.top, b.right, b.bottom, target)
    }

    /** Maps a screen point to the page under it, or `null` on the gap between pages / outside the document. */
    fun pageHit(layout: DocumentLayout, t: ViewportTransform, screenX: Float, screenY: Float): PageHit? {
        if (layout.pageCount == 0 || layout.contentWidth <= 0f) return null
        val docY = t.toDocY(screenY)
        val page = layout.pageAt(docY)
        val top = layout.pageTop(page)
        val height = layout.pageHeight(page)
        if (docY < top || docY > top + height || height <= 0f) return null
        val x = (t.toDocX(screenX) - layout.pageLeft) / layout.contentWidth
        return PageHit(page, x, (docY - top) / height)
    }

    /**
     * The region hit by ([x], [y]): the smallest region containing the point, else the nearest one
     * within ([slopX], [slopY]) of it (fingers are bigger than link boxes), else `null`.
     */
    fun find(regions: List<LinkRegion>, x: Float, y: Float, slopX: Float = 0f, slopY: Float = 0f): LinkRegion? {
        var containing: LinkRegion? = null
        var near: LinkRegion? = null
        var nearDistance = Float.MAX_VALUE
        for (r in regions) {
            if (r.contains(x, y)) {
                if (containing == null || r.area < containing.area) containing = r
                continue
            }
            if (containing != null) continue
            // Distance to the rectangle in units of the slop, so an elongated tolerance stays fair.
            val dx = distanceOutside(x, r.left, r.right) / slopX.coerceAtLeast(EPSILON)
            val dy = distanceOutside(y, r.top, r.bottom) / slopY.coerceAtLeast(EPSILON)
            if (dx <= 1f && dy <= 1f) {
                val d = dx * dx + dy * dy
                if (d < nearDistance) {
                    nearDistance = d
                    near = r
                }
            }
        }
        return containing ?: near
    }

    private fun distanceOutside(v: Float, low: Float, high: Float): Float = when {
        v < low -> low - v
        v > high -> v - high
        else -> 0f
    }

    private const val EPSILON = 1e-6f
}
