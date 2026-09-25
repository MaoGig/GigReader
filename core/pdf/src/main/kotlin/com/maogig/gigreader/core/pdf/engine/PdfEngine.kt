package com.maogig.gigreader.core.pdf.engine

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.maogig.gigreader.core.common.io.PageSizes
import java.io.File

/**
 * Engine-agnostic PDF access. The reader, thumbnails, import validation and (later) search and
 * annotation export only talk to this interface, so the backend (framework PdfRenderer today, a
 * bundled PDFium binding next; see docs/PDF_ENGINE_COMPARISON.md) can change without touching UI.
 *
 * Threading contract: every method is `suspend` and main-safe. Implementations serialize access to
 * the native document internally and never block the caller's thread.
 */
interface PdfEngine {
    /** Stable identifier used in diagnostics ("framework", "pdfium"). */
    val id: String

    /** Opens a local file. Throws [PdfOpenException]. */
    suspend fun open(file: File, password: String? = null): PdfDocument

    /**
     * Opens a descriptor (must be seekable; the engine takes ownership and closes it).
     * Throws [PdfOpenException].
     */
    suspend fun open(descriptor: ParcelFileDescriptor, password: String? = null): PdfDocument
}

/** Features differ per backend and per device; the UI hides what is unavailable. */
data class EngineCapabilities(
    val textSearch: Boolean = false,
    val textSelection: Boolean = false,
    val textExtraction: Boolean = false,
    val outline: Boolean = false,
    val links: Boolean = false,
    val annotationWrite: Boolean = false,
    /** Page sizes can be read without loading pages (otherwise they are measured in background). */
    val cheapPageSizes: Boolean = false,
)

/** Size of a page in PDF points (1/72 inch), rotation applied. */
data class PageSize(val width: Float, val height: Float)

/**
 * One region of a page to rasterize into [target]. The page is scaled to
 * [pageWidthPx] × [pageHeightPx] and the pixel at ([offsetX], [offsetY]) of that scaled page lands on
 * the top-left pixel of [target]. [isStale] is checked right before rendering so jobs that left the
 * viewport while queued are skipped (renders themselves cannot be interrupted).
 */
class RenderJob(
    val target: Bitmap,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val isStale: () -> Boolean = { false },
)

/** A text match on one page; rectangles are normalized (0..1) page coordinates, one per line. */
data class TextMatch(val page: Int, val rects: List<android.graphics.RectF>, val textStartIndex: Int)

data class OutlineItem(val title: String, val page: Int, val children: List<OutlineItem>)

interface PdfDocument {
    val pageCount: Int
    val capabilities: EngineCapabilities

    suspend fun pageSize(page: Int): PageSize

    /**
     * Measures the sizes of [range] into [out] (one page load per page on backends without
     * [EngineCapabilities.cheapPageSizes]). Checks [isCancelled] between pages.
     */
    suspend fun measurePages(range: IntRange, out: PageSizes, isCancelled: () -> Boolean = { false })

    /**
     * Renders [jobs] for one [page], loading the page once. Returns, per job, `true` if it was
     * rendered and `false` if it was skipped because it became stale.
     */
    suspend fun render(page: Int, jobs: List<RenderJob>): BooleanArray

    /** Case- and accent-insensitive search on one page; empty if [EngineCapabilities.textSearch] is false. */
    suspend fun searchPage(page: Int, query: String): List<TextMatch> = emptyList()

    suspend fun outline(): List<OutlineItem> = emptyList()

    /** Releases native resources. Further calls throw [IllegalStateException]. Idempotent. */
    suspend fun close()
}

/** Reasons a document cannot be opened, mapped to user-facing messages by the UI. */
sealed class PdfOpenException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class PasswordRequired(cause: Throwable? = null) : PdfOpenException("password required", cause)

    class Corrupted(cause: Throwable? = null) : PdfOpenException("corrupted or unsupported PDF", cause)

    /** The source is not a seekable file (e.g. a streaming provider); the caller should copy it first. */
    class NotSeekable(cause: Throwable? = null) : PdfOpenException("source is not seekable", cause)

    class Unreadable(cause: Throwable? = null) : PdfOpenException("file cannot be read", cause)

    class OutOfMemory(cause: Throwable? = null) : PdfOpenException("not enough memory", cause)
}
