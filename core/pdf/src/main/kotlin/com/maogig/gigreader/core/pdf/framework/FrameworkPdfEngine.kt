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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

    override suspend fun open(file: File, password: String?): PdfDocument {
        val descriptor = withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: FileNotFoundException) {
                throw PdfOpenException.Unreadable(e)
            }
        }
        return open(descriptor, password)
    }

    override suspend fun open(descriptor: ParcelFileDescriptor, password: String?): PdfDocument = withContext(dispatcher) {
        val renderer = try {
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
        FrameworkPdfDocument(renderer, dispatcher)
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
        for (i in range) {
            if (closed || isCancelled()) break
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

    override suspend fun close() = withContext(dispatcher) {
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

    fun render(page: PdfRenderer.Page, job: RenderJob, matrix: Matrix) {
        val params = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY)
            .setRenderFlags(RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS)
            .build()
        page.render(job.target, null, matrix, params)
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
