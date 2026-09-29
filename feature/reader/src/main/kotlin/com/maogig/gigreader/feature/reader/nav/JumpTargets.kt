package com.maogig.gigreader.feature.reader.nav

import com.maogig.gigreader.core.common.render.PagePosition

/** Turns a destination (table of contents, link, thumbnail...) into a reading position. Pure. */
object JumpTargets {
    /** Part of a page kept visible above a destination that specifies a vertical position. */
    const val TOP_MARGIN_FRACTION = 0.03f

    /**
     * Position for zero-based [page] (clamped to the document) at [yFraction] from its top, if given;
     * a small margin stays visible above it so the target is not glued to the screen edge.
     */
    fun position(page: Int, yFraction: Float?, pageCount: Int): PagePosition {
        val clampedPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val y = yFraction?.takeIf { it.isFinite() } ?: return PagePosition(clampedPage, 0f)
        return PagePosition(clampedPage, (y - TOP_MARGIN_FRACTION).coerceIn(0f, 1f))
    }
}
