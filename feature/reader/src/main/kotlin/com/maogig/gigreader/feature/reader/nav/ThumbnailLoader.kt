package com.maogig.gigreader.feature.reader.nav

import android.graphics.Bitmap
import com.maogig.gigreader.core.pdf.engine.PdfDocument
import com.maogig.gigreader.core.pdf.engine.RenderJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Page thumbnails for the pages panel. Renders through the same [PdfDocument] (and so the same
 * serial engine lane) as the viewport, but through an [IdleGatedRenderQueue]: one thumbnail at a
 * time, and only while [viewportBusy] (the viewport pipeline's `isBusy`) is false, so a visible
 * page render is never queued behind more than one small thumbnail.
 *
 * Owns a byte-bounded cache of [budgetBytes]. [close] it when the panel closes; [pause] it when the
 * reader stops (bitmaps are released, nothing renders) and [resume] it afterwards.
 */
class ThumbnailLoader(
    private val document: PdfDocument,
    val pageCount: Int,
    private val widthPx: Int,
    private val aspectOf: (page: Int) -> Float,
    viewportBusy: StateFlow<Boolean>,
    scope: CoroutineScope,
    budgetBytes: Long,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val queue = IdleGatedRenderQueue<Int, Bitmap>(
        scope = scope,
        dispatcher = dispatcher,
        maxWeight = budgetBytes,
        weigher = { it.allocationByteCount.toLong() },
        awaitIdle = { viewportBusy.first { busy -> !busy } },
        produce = ::render,
    )

    /** Changes whenever a thumbnail became available. */
    val version: StateFlow<Long> get() = queue.version

    /** The rendered thumbnail of [page], or `null` while it is not ready. */
    fun bitmap(page: Int): Bitmap? = queue.peek(page)

    /** The grid shows [visible] pages: render them (then a few around them) as the engine allows. */
    fun showing(visible: IntRange) {
        queue.request(ThumbnailPlanner.order(visible, PREFETCH_PAGES, pageCount))
    }

    fun pause() = queue.pause()

    fun resume() = queue.resume()

    fun close() = queue.close()

    private suspend fun render(page: Int, isWanted: () -> Boolean): Bitmap? {
        val height = ThumbnailPlanner.heightFor(widthPx, aspectOf(page))
        val bitmap = try {
            Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        var rendered = false
        try {
            rendered = document.render(page, listOf(RenderJob(bitmap, widthPx, height, isStale = { !isWanted() })))[0]
        } catch (e: OutOfMemoryError) {
            // Skipped: the cell keeps its placeholder.
        } finally {
            // Never shown when it was not stored, so recycling is safe.
            if (!rendered) bitmap.recycle()
        }
        return if (rendered) bitmap else null
    }

    companion object {
        /** Pages rendered after / before the visible ones. */
        const val PREFETCH_PAGES = 6

        /** Default thumbnail width in px cap: 384 px is sharp on a 3x-density 128 dp cell. */
        const val MAX_WIDTH_PX = 384
    }
}
