package com.maogig.gigreader.core.pdf.framework

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.RenderParams
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.pdf.engine.EngineCapabilities
import com.maogig.gigreader.core.pdf.engine.PageSize
import com.maogig.gigreader.core.pdf.engine.PdfDocument
import com.maogig.gigreader.core.pdf.engine.PdfEngine
import com.maogig.gigreader.core.pdf.engine.PdfOpenException
import com.maogig.gigreader.core.pdf.engine.RenderJob
import com.maogig.gigreader.core.pdf.engine.TextMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Backend over the platform [PdfRenderer] (PDFium inside the OS). Zero APK cost and always
 * available, but render-only below API 35 and serialized process-wide by the platform itself.
 *
 * Because the platform holds a process-wide lock around every PDFium call (API 26+), all documents
 * share ONE serial dispatcher: parallel dispatch would only park extra threads on that lock.
 */
class FrameworkPdfEngine(
    private val dispatcher: CoroutineDispatcher = SharedDispatcher,
) : PdfEngine {
    override val id: String = "framework"

    // The file is opened on the engine lane too (one cheap syscall), so there is a single hop whose
    // result can be discarded by cancellation; see [openOnLane].
    override suspend fun open(file: File, password: String?): PdfDocument = openOnLane(onNotStarted = {}) {
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: FileNotFoundException) {
            throw PdfOpenException.Unreadable(e)
        } catch (e: SecurityException) {
            throw PdfOpenException.Unreadable(e)
        }
        createRenderer(descriptor, password)
    }

    override suspend fun open(descriptor: ParcelFileDescriptor, password: String?): PdfDocument =
        openOnLane(onNotStarted = { descriptor.closeQuietly() }) { createRenderer(descriptor, password) }

    /**
     * Runs [block] on the engine lane. `withContext` has a prompt-cancellation guarantee: if the
     * caller is cancelled, the block may never run (the descriptor we own is still open) or its
     * result may be thrown away (a live renderer nobody will ever close). Both would leak a file
     * descriptor and the native document until a finalizer runs, so they are released here.
     */
    private suspend fun openOnLane(onNotStarted: () -> Unit, block: () -> PdfRenderer): PdfDocument {
        var opened: PdfRenderer? = null
        try {
            return withContext(dispatcher) {
                val renderer = block()
                opened = renderer
                FrameworkPdfDocument(renderer, dispatcher)
            }
        } catch (e: CancellationException) {
            val renderer = opened
            if (renderer != null) {
                withContext(NonCancellable + dispatcher) { renderer.close() }
            } else {
                // Not started, or failed (the descriptor is then already closed; closing twice is a no-op).
                onNotStarted()
            }
            throw e
        }
    }

    /** Must run on [dispatcher]. On failure the descriptor is closed (the renderer never took it). */
    private fun createRenderer(descriptor: ParcelFileDescriptor, password: String?): PdfRenderer = try {
        if (Build.VERSION.SDK_INT >= 35 && password != null) {
            Api35.openWithPassword(descriptor, password)
        } else {
            PdfRenderer(descriptor)
        }
    } catch (e: SecurityException) {
        descriptor.closeQuietly()
        throw PdfOpenException.PasswordRequired(e)
    } catch (e: IllegalArgumentException) {
        // Thrown for non-seekable descriptors (pipes/sockets from streaming providers).
        descriptor.closeQuietly()
        throw PdfOpenException.NotSeekable(e)
    } catch (e: IOException) {
        descriptor.closeQuietly()
        throw PdfOpenException.Corrupted(e)
    } catch (e: OutOfMemoryError) {
        descriptor.closeQuietly()
        throw PdfOpenException.OutOfMemory(e)
    } catch (e: RuntimeException) {
        descriptor.closeQuietly()
        throw PdfOpenException.Corrupted(e)
    }

    companion object {
        /** One thread-confined lane for every framework document (see class docs). */
        val SharedDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}

private class FrameworkPdfDocument(
    private val renderer: PdfRenderer,
    private val dispatcher: CoroutineDispatcher,
) : PdfDocument {
    override val pageCount: Int = renderer.pageCount

    override val capabilities: EngineCapabilities = EngineCapabilities(
        textSearch = Build.VERSION.SDK_INT >= 35,
    )

    @Volatile
    private var closed = false

    // Confined to [dispatcher]; reused for every render to avoid per-tile allocations.
    private val matrix = Matrix()

    override suspend fun pageSize(page: Int): PageSize = withContext(dispatcher) {
        checkOpen()
        val p = renderer.openPage(page)
        try {
            PageSize(p.width.toFloat(), p.height.toFloat())
        } finally {
            p.close()
        }
    }

    override suspend fun measurePages(range: IntRange, out: PageSizes, isCancelled: () -> Boolean) = withContext(dispatcher) {
        // A closed document must fail loudly: returning normally would look like "measured".
        checkOpen()
        for (i in range) {
            if (isCancelled()) break
            val p = renderer.openPage(i)
            try {
                out.widths[i] = p.width.toFloat()
                out.heights[i] = p.height.toFloat()
            } finally {
                p.close()
            }
        }
    }

    override suspend fun render(page: Int, jobs: List<RenderJob>): BooleanArray = withContext(dispatcher) {
        checkOpen()
        val rendered = BooleanArray(jobs.size)
        if (jobs.all { it.isStale() }) return@withContext rendered
        val p = renderer.openPage(page)
        try {
            val widthPt = p.width.toFloat()
            val heightPt = p.height.toFloat()
            for (i in jobs.indices) {
                val job = jobs[i]
                if (job.isStale()) continue
                // PDFium does not paint a background and the bitmap may come from the pool.
                job.target.eraseColor(Color.WHITE)
                matrix.setScale(job.pageWidthPx / widthPt, job.pageHeightPx / heightPt)
                matrix.postTranslate(-job.offsetX.toFloat(), -job.offsetY.toFloat())
                if (Build.VERSION.SDK_INT >= 35) {
                    Api35.render(p, job, matrix)
                } else {
                    // Legacy renderer never draws annotations already present in the file.
                    p.render(job.target, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                rendered[i] = true
            }
        } finally {
            p.close()
        }
        rendered
    }

    override suspend fun searchPage(page: Int, query: String): List<TextMatch> {
        if (Build.VERSION.SDK_INT < 35 || query.isBlank()) return emptyList()
        return withContext(dispatcher) {
            checkOpen()
            val p = renderer.openPage(page)
            try {
                Api35.search(p, page, query)
            } finally {
                p.close()
            }
        }
    }

    // NonCancellable: callers close in `finally` blocks, often because they were just cancelled.
    // A cancellable withContext would then throw before running and leak the renderer and its fd.
    override suspend fun close() = withContext(NonCancellable + dispatcher) {
        if (!closed) {
            closed = true
            renderer.close()
        }
    }

    private fun checkOpen() = check(!closed) { "document is closed" }
}

@RequiresApi(35)
private object Api35 {
    fun openWithPassword(descriptor: ParcelFileDescriptor, password: String): PdfRenderer =
        PdfRenderer(descriptor, LoadParams.Builder().setPassword(password).build())

    // Immutable; built once instead of once per tile.
    private val displayParams: RenderParams = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY)
        .setRenderFlags(RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS)
        .build()

    fun render(page: PdfRenderer.Page, job: RenderJob, matrix: Matrix) {
        page.render(job.target, null, matrix, displayParams)
    }

    fun search(page: PdfRenderer.Page, index: Int, query: String): List<TextMatch> {
        val w = page.width.toFloat()
        val h = page.height.toFloat()
        return page.searchText(query).map { match ->
            TextMatch(
                page = index,
                rects = match.bounds.map { r -> RectF(r.left / w, r.top / h, r.right / w, r.bottom / h) },
                textStartIndex = match.textStartIndex,
            )
        }
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
