package com.maogig.gigreader.feature.reader.viewport

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.maogig.gigreader.core.common.render.DocumentLayout
import com.maogig.gigreader.core.common.render.RenderPlanner
import com.maogig.gigreader.core.common.render.TileKey
import com.maogig.gigreader.core.common.render.ViewportTransform
import com.maogig.gigreader.core.common.render.ZoomBuckets
import com.maogig.gigreader.core.model.ReaderPageBackground
import com.maogig.gigreader.core.pdf.render.RenderPipeline
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws visible pages straight onto the platform canvas with preallocated Paint/Rect objects:
 * a frame allocates nothing except the small cache keys used for lookups.
 *
 * Night and sepia reading are color filters applied while drawing, so switching the page
 * background never re-renders a PDF page.
 */
internal class PageDrawer {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val paperPaint = Paint()
    private val failedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val src = Rect()
    private val dst = RectF()
    private var background: ReaderPageBackground? = null

    fun setBackground(value: ReaderPageBackground) {
        if (value == background) return
        background = value
        when (value) {
            ReaderPageBackground.WHITE -> {
                paperPaint.color = 0xFFFFFFFF.toInt()
                bitmapPaint.colorFilter = null
            }
            ReaderPageBackground.SEPIA -> {
                paperPaint.color = 0xFFF5EBD1.toInt()
                // Scales channels: white paper becomes warm sepia, black text stays black.
                bitmapPaint.colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            0.96f, 0f, 0f, 0f, 0f,
                            0f, 0.92f, 0f, 0f, 0f,
                            0f, 0f, 0.82f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            ReaderPageBackground.DARK -> {
                paperPaint.color = 0xFF121212.toInt()
                // Inverts luminance: white (255) → 18 (#121212 paper), black (0) → 222 (soft white text).
                bitmapPaint.colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            -0.8f, 0f, 0f, 0f, 222f,
                            0f, -0.8f, 0f, 0f, 222f,
                            0f, 0f, -0.8f, 0f, 222f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
        }
        failedPaint.color = if (value == ReaderPageBackground.DARK) 0x55FFFFFF else 0x33000000
    }

    fun draw(
        canvas: Canvas,
        layout: DocumentLayout,
        t: ViewportTransform,
        viewportWidth: Float,
        viewportHeight: Float,
        pipeline: RenderPipeline,
        planner: RenderPlanner,
        tileBucket: Int,
    ) {
        val docTop = t.offsetY
        val docBottom = docTop + viewportHeight / t.zoom
        val pages = layout.pagesIn(docTop, docBottom)
        if (pages.isEmpty()) return
        for (page in pages) {
            val left = (layout.pageLeft - t.offsetX) * t.zoom
            val top = (layout.pageTop(page) - t.offsetY) * t.zoom
            val width = layout.contentWidth * t.zoom
            val height = layout.pageHeight(page) * t.zoom
            dst.set(left, top, left + width, top + height)
            canvas.drawRect(dst, paperPaint)

            val base = pipeline.bitmap(planner.baseKey(layout, page))
            if (base != null) {
                canvas.drawBitmap(base, null, dst, bitmapPaint)
            } else if (pipeline.isFailed(page)) {
                // Unrenderable page: a discreet cross instead of an empty sheet.
                canvas.drawLine(dst.left, dst.top, dst.right, dst.bottom, failedPaint)
                canvas.drawLine(dst.right, dst.top, dst.left, dst.bottom, failedPaint)
                continue
            }
            if (planner.needsTiles(layout, page, t.zoom)) drawTiles(canvas, layout, t, page, left, top, viewportWidth, viewportHeight, pipeline, planner.tileSize, tileBucket)
        }
    }

    private fun drawTiles(
        canvas: Canvas,
        layout: DocumentLayout,
        t: ViewportTransform,
        page: Int,
        pageScreenLeft: Float,
        pageScreenTop: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        pipeline: RenderPipeline,
        tileSize: Int,
        bucket: Int,
    ) {
        val scale = ZoomBuckets.scale(bucket)
        val pageWidthPx = max(1, (layout.contentWidth * scale).roundToInt())
        val pageHeightPx = max(1, (pageWidthPx * layout.pageAspect(page)).roundToInt())
        // Screen px per rendered-tile px.
        val k = t.zoom / scale
        // Visible part of the page, in rendered-page pixels.
        val visLeft = max(0f, -pageScreenLeft) / k
        val visTop = max(0f, -pageScreenTop) / k
        val visRight = min(pageWidthPx.toFloat(), (viewportWidth - pageScreenLeft) / k)
        val visBottom = min(pageHeightPx.toFloat(), (viewportHeight - pageScreenTop) / k)
        if (visRight <= visLeft || visBottom <= visTop) return
        val colStart = floor(visLeft / tileSize).toInt()
        val colEnd = min((pageWidthPx - 1) / tileSize, floor((visRight - 0.001f) / tileSize).toInt())
        val rowStart = floor(visTop / tileSize).toInt()
        val rowEnd = min((pageHeightPx - 1) / tileSize, floor((visBottom - 0.001f) / tileSize).toInt())
        for (row in rowStart..rowEnd) {
            for (col in colStart..colEnd) {
                val key = TileKey(page, bucket, col, row, tileSize, pageWidthPx, pageHeightPx)
                val bitmap = pipeline.bitmap(key) ?: continue
                src.set(0, 0, key.right - key.left, key.bottom - key.top)
                val x = pageScreenLeft + key.left * k
                val y = pageScreenTop + key.top * k
                dst.set(x, y, x + src.width() * k, y + src.height() * k)
                canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
            }
        }
    }
}
