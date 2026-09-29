package com.maogig.gigreader.core.pdf.mupdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.pdf.engine.EngineCapabilities
import com.maogig.gigreader.core.pdf.engine.OutlineItem
import com.maogig.gigreader.core.pdf.engine.PageLink
import com.maogig.gigreader.core.pdf.engine.PageSize
import com.maogig.gigreader.core.pdf.engine.PdfDocument
import com.maogig.gigreader.core.pdf.engine.RenderJob
import com.maogig.gigreader.core.pdf.engine.TextMatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One open MuPDF document. Every native call runs on [lane] (one serial lane per document, see
 * [MuPdfEngine]); every native object created for a call (page, structured text, device, cookie,
 * link, PDF object) is destroyed in a `finally` right after use instead of waiting for the GC's
 * finalizer, so native memory does not pile up while the reader scrolls.
 *
 * [release] frees what backs the document (descriptor, stream) and runs once, after the native
 * document is destroyed.
 */
internal class MuPdfDocument(
    private val document: Document,
    override val pageCount: Int,
    private val lane: CoroutineDispatcher,
    private val release: () -> Unit,
) : PdfDocument {
    /** Non-null for PDFs: page boxes can then be read from the page tree without loading pages. */
    private val pdf: PDFDocument? = document as? PDFDocument

    override val capabilities: EngineCapabilities = EngineCapabilities(
        textSearch = true,
        textSelection = false, // Phase 3
        textExtraction = true,
        outline = true,
        links = true,
        annotationWrite = false, // Phase 5
        cheapPageSizes = pdf != null,
    )

    @Volatile
    private var closed = false

    override suspend fun pageSize(page: Int): PageSize = withContext(lane) {
        checkOpen()
        checkPage(page)
        val (w, h) = sizeOf(page)
        PageSize(w, h)
    }

    override suspend fun measurePages(range: IntRange, out: PageSizes, isCancelled: () -> Boolean) = withContext(lane) {
        // A closed document must fail loudly: returning normally would look like "measured".
        checkOpen()
        for (i in range) {
            if (isCancelled()) break
            checkPage(i)
            val (w, h) = sizeOf(i)
            out.widths[i] = w
            out.heights[i] = h
        }
    }

    override suspend fun render(page: Int, jobs: List<RenderJob>): BooleanArray = withContext(lane) {
        checkOpen()
        checkPage(page)
        val rendered = BooleanArray(jobs.size)
        if (jobs.all { it.isStale() }) return@withContext rendered
        val p = document.loadPage(page)
        var cookie: Cookie? = null
        try {
            val bounds = p.getBounds().toBox()
            cookie = Cookie()
            for (i in jobs.indices) {
                val job = jobs[i]
                if (job.isStale()) continue
                drawTile(p, bounds, job, cookie)
                rendered[i] = true
            }
        } finally {
            cookie?.destroy()
            p.destroy()
        }
        rendered
    }

    /** Draws one tile. The page is annotated/widget-complete: `Page.run` paints content, annotations and form widgets. */
    private fun drawTile(page: Page, bounds: Box, job: RenderJob, cookie: Cookie) {
        val target = job.target
        require(target.config == Bitmap.Config.ARGB_8888) { "MuPDF draws into ARGB_8888 bitmaps only" }
        // The reader draws on paper and the bitmap may come from the pool: start from white.
        target.eraseColor(Color.WHITE)
        val m = PdfGeometry.tileMatrix(bounds, job.pageWidthPx, job.pageHeightPx, job.offsetX, job.offsetY)
        val ctm = Matrix(m[0], m[1], m[2], m[3], m[4], m[5])
        // The device origin is (0,0): the translation is already in the matrix. clear=false keeps the white above.
        val device = AndroidDrawDevice(target, 0, 0, 0, 0, target.width, target.height, false)
        try {
            page.run(device, ctm, cookie)
            device.close() // flushes into the bitmap
        } finally {
            device.destroy()
        }
    }

    override suspend fun searchPage(page: Int, query: String): List<TextMatch> {
        if (query.isBlank()) return emptyList()
        return withContext(lane) {
            checkOpen()
            checkPage(page)
            val p = document.loadPage(page)
            var text: StructuredText? = null
            try {
                val bounds = p.getBounds().toBox()
                text = p.toStructuredText()
                val builder = PageTextIndex.Builder(bounds.left, bounds.top, bounds.width, bounds.height)
                text.walk(IndexWalker(builder))
                builder.build().find(query).map { hit ->
                    TextMatch(
                        page = page,
                        rects = hit.rects.map { RectF(it.left, it.top, it.right, it.bottom) },
                        textStartIndex = hit.textStartIndex,
                    )
                }
            } finally {
                text?.destroy()
                p.destroy()
            }
        }
    }

    override suspend fun outline(): List<OutlineItem> = withContext(lane) {
        checkOpen()
        val roots = document.loadOutline() ?: return@withContext emptyList()
        val heights = HashMap<Int, Float>()
        val converted = OutlineConverter.convert(roots.map { it.toRaw(0) }) { uri -> resolve(uri, heights) }
        converted.map { it.toItem() }
    }

    override suspend fun links(page: Int): List<PageLink> = withContext(lane) {
        checkOpen()
        checkPage(page)
        val p = document.loadPage(page)
        try {
            val links = p.getLinks() ?: return@withContext emptyList()
            val bounds = p.getBounds().toBox()
            val heights = HashMap<Int, Float>()
            val result = ArrayList<PageLink>(links.size)
            for (link in links) {
                try {
                    val rect = PdfGeometry.normalize(link.getBounds().toBox(), bounds)
                    if (rect.right <= rect.left || rect.bottom <= rect.top) continue
                    val area = RectF(rect.left, rect.top, rect.right, rect.bottom)
                    when (val target = LinkClassifier.classify(link.getURI())) {
                        is LinkTarget.External -> result += PageLink.External(area, target.uri)
                        is LinkTarget.Internal -> {
                            val dest = resolve(target.uri, heights)
                            if (dest.page >= 0) result += PageLink.Internal(area, dest.page, dest.yFraction)
                        }
                        LinkTarget.Ignored -> Unit
                    }
                } finally {
                    link.destroy()
                }
            }
            result
        } finally {
            p.destroy()
        }
    }

    override suspend fun pageLabel(page: Int): String? = withContext(lane) {
        checkOpen()
        checkPage(page)
        val p = document.loadPage(page)
        try {
            PageLabels.clean(p.getLabel(), page)
        } finally {
            p.destroy()
        }
    }

    // NonCancellable: callers close in `finally` blocks, often because they were just cancelled.
    // A cancellable withContext would then throw before running and leak the native document and its fd.
    override suspend fun close() = withContext(NonCancellable + lane) {
        if (!closed) {
            closed = true
            try {
                document.destroy()
            } finally {
                release()
            }
        }
    }

    private fun checkOpen() = check(!closed) { "document is closed" }

    private fun checkPage(page: Int) = require(page in 0 until pageCount) { "page $page out of range 0..${pageCount - 1}" }

    /** Size in points, rotation applied. Must run on [lane]. */
    private fun sizeOf(page: Int): Pair<Float, Float> {
        val fromTree = pdf?.let { readPdfPageSize(it, page) }
        if (fromTree != null) return fromTree
        val p = document.loadPage(page)
        try {
            val b = p.getBounds()
            return (b.x1 - b.x0) to (b.y1 - b.y0)
        } finally {
            p.destroy()
        }
    }

    /**
     * Reads MediaBox/CropBox/Rotate (inheritable) and UserUnit from the page tree entry, without
     * parsing the page's content or resources. `null` if the entry is unusable (the caller then loads the page).
     */
    private fun readPdfPageSize(pdf: PDFDocument, page: Int): Pair<Float, Float>? {
        val node = pdf.findPage(page) ?: return null
        try {
            val media = node.inheritedBox("MediaBox")
            val crop = node.inheritedBox("CropBox")
            val rotate = node.inheritedInt("Rotate") ?: 0
            val unit = node.ownFloat("UserUnit")
            return PdfGeometry.pageSize(media, crop, rotate, unit)
        } finally {
            node.destroy()
        }
    }

    /** Page of [uri] (and vertical position) or page -1. [heights] caches page heights across one call. */
    private fun resolve(uri: String, heights: MutableMap<Int, Float>): ResolvedDestination {
        val location = try {
            document.resolveLink(uri)
        } catch (_: RuntimeException) {
            null
        }
        val page = document.pageNumberFromLocation(location)
        if (page < 0 || page >= pageCount) return ResolvedDestination(-1, null)
        val y = try {
            document.resolveLinkDestination(uri)?.takeIf { it.hasY() }?.y
        } catch (_: RuntimeException) {
            null
        } ?: return ResolvedDestination(page, null)
        val height = heights.getOrPut(page) { sizeOf(page).second }
        return ResolvedDestination(page, PdfGeometry.yFraction(y, height))
    }

    private fun Outline.toRaw(depth: Int): RawOutline = RawOutline(
        title = title,
        uri = uri,
        children = if (depth < OutlineConverter.MAX_DEPTH) down?.map { it.toRaw(depth + 1) }.orEmpty() else emptyList(),
    )

    private fun ConvertedOutline.toItem(): OutlineItem =
        OutlineItem(title = title, page = page, children = children.map { it.toItem() }, yFraction = yFraction)

    /** Feeds the characters of a [StructuredText] into a [PageTextIndex.Builder]. */
    private class IndexWalker(private val builder: PageTextIndex.Builder) : StructuredTextWalker {
        override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: com.artifex.mupdf.fitz.Image?) = Unit

        override fun beginTextBlock(bbox: Rect?, flags: Int) = Unit

        override fun endTextBlock() = Unit

        override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) = builder.beginLine()

        override fun endLine() = Unit

        override fun onChar(
            c: Int,
            origin: Point?,
            font: com.artifex.mupdf.fitz.Font?,
            size: Float,
            q: Quad?,
            argb: Int,
            flags: Int,
            bidi: Int,
        ) {
            if (q == null || !q.isValid) return
            val r = q.toRect()
            builder.addChar(c, r.x0, r.y0, r.x1, r.y1)
        }

        override fun beginStruct(standard: String?, raw: String?, index: Int) = Unit

        override fun endStruct() = Unit

        override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) = Unit
    }
}

private fun Rect.toBox(): Box = Box(x0, y0, x1, y1)

/** A number entry, resolved through references; `null` when absent or not a number. */
private fun PDFObject.inheritedInt(name: String): Int? {
    val o = getInheritable(name) ?: return null
    try {
        val r = o.resolve()
        try {
            return if (r.isNumber) r.asInteger() else null
        } finally {
            if (r !== o) r.destroy()
        }
    } finally {
        o.destroy()
    }
}

private fun PDFObject.ownFloat(name: String): Float? {
    val o = get(name) ?: return null
    try {
        val r = o.resolve()
        try {
            return if (r.isNumber) r.asFloat() else null
        } finally {
            if (r !== o) r.destroy()
        }
    } finally {
        o.destroy()
    }
}

/** `[x0 y0 x1 y1]` entry (page-tree inheritance applied), or `null` when absent or malformed. */
private fun PDFObject.inheritedBox(name: String): Box? {
    val o = getInheritable(name) ?: return null
    try {
        val arr = o.resolve()
        try {
            if (!arr.isArray || arr.size() < 4) return null
            val v = FloatArray(4)
            for (i in 0 until 4) {
                val e = arr.get(i) ?: return null
                try {
                    val r = e.resolve()
                    try {
                        if (!r.isNumber) return null
                        v[i] = r.asFloat()
                    } finally {
                        if (r !== e) r.destroy()
                    }
                } finally {
                    e.destroy()
                }
            }
            // PDF user space has y up: the top of the page is the larger y. Only the extent matters
            // for sizes, so keep the raw corners (Box.normalized() orders them).
            return Box(v[0], v[1], v[2], v[3])
        } finally {
            if (arr !== o) arr.destroy()
        }
    } finally {
        o.destroy()
    }
}
