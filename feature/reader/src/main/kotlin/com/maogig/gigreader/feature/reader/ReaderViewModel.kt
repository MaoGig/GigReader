package com.maogig.gigreader.feature.reader

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maogig.gigreader.core.common.coroutines.CoalescingSaver
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.common.render.PagePosition
import com.maogig.gigreader.core.common.render.RenderPlanner
import com.maogig.gigreader.core.common.text.FileNames
import com.maogig.gigreader.core.data.importer.ImportManager
import com.maogig.gigreader.core.data.library.LibraryRepository
import com.maogig.gigreader.core.data.reader.PageMetrics
import com.maogig.gigreader.core.data.reader.ReaderRepository
import com.maogig.gigreader.core.data.settings.SettingsRepository
import com.maogig.gigreader.core.model.AppSettings
import com.maogig.gigreader.core.model.DocumentSource
import com.maogig.gigreader.core.pdf.PerfTrace
import com.maogig.gigreader.core.pdf.engine.PdfDocument
import com.maogig.gigreader.core.pdf.engine.PdfEngine
import com.maogig.gigreader.core.pdf.engine.PdfOpenException
import com.maogig.gigreader.core.pdf.render.RenderBudgets
import com.maogig.gigreader.core.pdf.render.RenderPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min

/** What the reader shows. */
sealed interface ReaderSource {
    data class LibraryDocument(val documentId: String) : ReaderSource

    /** A `content://` or `file://` URI opened from another app (not imported). */
    data class ExternalUri(val uri: String) : ReaderSource
}

class ReaderDependencies(
    val library: LibraryRepository,
    val reader: ReaderRepository,
    val engine: PdfEngine,
    val files: DocumentFileStore,
    val settings: SettingsRepository,
    val importer: ImportManager,
)

enum class ReaderError { NOT_FOUND, FILE_MISSING, PASSWORD_PROTECTED, CORRUPTED, UNREADABLE, OUT_OF_MEMORY }

/** Everything the viewport needs for one open document. Created once per open. */
@Immutable
class ReaderSession(
    val title: String,
    val pageCount: Int,
    val isExternal: Boolean,
    val initialPosition: PagePosition,
    val initialZoom: Float,
    val pipeline: RenderPipeline,
    val planner: RenderPlanner,
)

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Ready(val session: ReaderSession) : ReaderUiState

    data class Error(val error: ReaderError) : ReaderUiState
}

sealed interface ReaderEvent {
    data object AddedToLibrary : ReaderEvent
}

/**
 * Owns one open document: engine handle, render pipeline, page-size measurement and position
 * persistence. Everything is released in [onCleared]; afterwards CPU, memory and threads return to
 * idle.
 */
class ReaderViewModel(
    private val source: ReaderSource,
    private val deps: ReaderDependencies,
    private val app: Application,
) : ViewModel() {
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** Page sizes (points). Starts with estimates; replaced when background measurement refines it. */
    private val _pageSizes = MutableStateFlow<PageSizes?>(null)
    val pageSizes: StateFlow<PageSizes?> = _pageSizes.asStateFlow()

    val settings: StateFlow<AppSettings> =
        deps.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val events = Channel<ReaderEvent>(Channel.BUFFERED)
    val eventFlow: Flow<ReaderEvent> = events.receiveAsFlow()

    /** Outlives viewModelScope so the final position write and the document close always complete. */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val documentId: String? = (source as? ReaderSource.LibraryDocument)?.documentId

    private val positionSaver = CoalescingSaver<SavedPosition>(persistScope, delayMillis = 1_500) { p ->
        documentId?.let { deps.reader.savePosition(it, p.page, p.pageOffset, p.zoom) }
    }

    private var document: PdfDocument? = null
    private var pipeline: RenderPipeline? = null
    private var externalCopy: File? = null
    private var lastSaved: SavedPosition? = null

    private data class SavedPosition(val page: Int, val pageOffset: Float, val zoom: Float)

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val cookie = PerfTrace.begin(PerfTrace.OPEN_DOCUMENT)
        try {
            val opened = when (source) {
                is ReaderSource.LibraryDocument -> openLibraryDocument(source.documentId)
                is ReaderSource.ExternalUri -> openExternal(Uri.parse(source.uri))
            } ?: return
            document = opened.document
            val pageCount = opened.document.pageCount
            if (pageCount <= 0) {
                fail(ReaderError.CORRUPTED)
                return
            }

            val cached = documentId?.let { deps.reader.pageMetrics(it, pageCount) }
            val metrics = cached ?: run {
                val first = opened.document.pageSize(0)
                PageMetrics(PageSizes.uniform(pageCount, first.width, first.height), measuredCount = 1)
            }
            _pageSizes.value = metrics.sizes.copy()

            val position = documentId?.let { deps.reader.position(it) }
            documentId?.let {
                deps.reader.markOpened(it)
                deps.reader.updatePageCount(it, pageCount)
            }

            val display = app.resources.displayMetrics
            val budget = RenderBudgets.forViewport(app, display.widthPixels, display.heightPixels)
            val planner = RenderPlanner(
                tileSize = budget.tileSize,
                prefetchPages = budget.prefetchPages,
                maxBaseWidthPx = budget.maxBaseWidthPx,
            )
            val newPipeline = RenderPipeline(opened.document, viewModelScope, budget)
            pipeline = newPipeline
            _state.value = ReaderUiState.Ready(
                ReaderSession(
                    title = opened.title,
                    pageCount = pageCount,
                    isExternal = source is ReaderSource.ExternalUri,
                    initialPosition = PagePosition(
                        (position?.page ?: 0).coerceIn(0, pageCount - 1),
                        position?.pageOffset ?: 0f,
                    ),
                    initialZoom = position?.zoom ?: 1f,
                    pipeline = newPipeline,
                    planner = planner,
                ),
            )
            if (!metrics.isComplete) {
                viewModelScope.launch(Dispatchers.Default) { measureRemaining(opened.document, metrics) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfOpenException) {
            fail(e.toReaderError())
        } catch (e: FileNotFoundException) {
            fail(ReaderError.UNREADABLE)
        } catch (e: SecurityException) {
            fail(ReaderError.UNREADABLE)
        } catch (e: IOException) {
            fail(ReaderError.UNREADABLE)
        } catch (e: OutOfMemoryError) {
            fail(ReaderError.OUT_OF_MEMORY)
        } catch (e: RuntimeException) {
            fail(ReaderError.CORRUPTED)
        } finally {
            PerfTrace.end(PerfTrace.OPEN_DOCUMENT, cookie)
        }
    }

    private class Opened(val document: PdfDocument, val title: String)

    private suspend fun openLibraryDocument(id: String): Opened? {
        val doc = deps.library.document(id)
        if (doc == null || doc.trashedAt != null) {
            fail(ReaderError.NOT_FOUND)
            return null
        }
        val pdf = when (val s = doc.source) {
            is DocumentSource.Managed -> {
                val file = deps.files.fileFor(s.relativePath)
                if (!withContext(Dispatchers.IO) { file.isFile }) {
                    fail(ReaderError.FILE_MISSING)
                    return null
                }
                deps.engine.open(file)
            }
            is DocumentSource.Linked -> openUri(Uri.parse(s.uri))
        }
        return Opened(pdf, doc.title)
    }

    private suspend fun openExternal(uri: Uri): Opened {
        val name = withContext(Dispatchers.IO) { displayName(uri) }
        return Opened(openUri(uri), FileNames.titleFromFileName(name))
    }

    /** Opens a URI in place when the provider gives a seekable file, else from a temporary copy. */
    private suspend fun openUri(uri: Uri): PdfDocument {
        val descriptor = try {
            withContext(Dispatchers.IO) { app.contentResolver.openFileDescriptor(uri, "r") }
        } catch (e: FileNotFoundException) {
            // Also thrown for "Not a whole file" (the provider serves a section of a file or only a
            // stream); openInputStream below may still work. A truly missing file fails there too.
            null
        }
        if (descriptor != null) {
            try {
                return deps.engine.open(descriptor) // the engine owns (and closes) the descriptor
            } catch (e: PdfOpenException.NotSeekable) {
                // Streaming provider (pipe/socket): fall back to a temporary copy.
            }
        }
        val copy = copyToCache(uri)
        externalCopy = copy
        return deps.engine.open(copy)
    }

    /** Reads [uri] once into the reader's temporary file; cancellable, never leaves a stray copy. */
    private suspend fun copyToCache(uri: Uri): File {
        var target: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "external").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // one temporary copy at a time
                val file = File(dir, "open.pdf")
                target = file
                val input = app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
                input.use {
                    file.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            // Leaving the reader stops a long copy from a slow provider right away.
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                file
            }
        } catch (e: Throwable) {
            // Failed, cancelled mid-copy, or cancelled just after it finished (withContext then
            // discards the result): nobody will own the file, so remove it now.
            target?.let { file -> withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
            throw e
        }
    }

    private fun displayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    } ?: uri.lastPathSegment

    /**
     * Measures real page sizes in small chunks so renders interleave on the engine's serial queue.
     * Publishes refined sizes at most every [PUBLISH_EVERY] pages (each publish rebuilds the layout
     * once, anchored at the current page) and persists them so the next open needs no measuring.
     */
    private suspend fun measureRemaining(doc: PdfDocument, initial: PageMetrics) {
        val job = currentCoroutineContext()[Job]
        val sizes = initial.sizes.copy()
        var measured = initial.measuredCount
        var changedSincePublish = false
        var sinceSave = 0
        while (measured < sizes.count) {
            val end = min(sizes.count, measured + CHUNK)
            val before = sizes.widths.copyOfRange(measured, end) to sizes.heights.copyOfRange(measured, end)
            try {
                // Checked between pages, so closing the reader stops measuring within one page load.
                doc.measurePages(measured until end, sizes) { job?.isActive == false }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                break // a broken page must not stop reading; keep estimates for the rest
            }
            for (i in measured until end) {
                if (sizes.widths[i] != before.first[i - measured] || sizes.heights[i] != before.second[i - measured]) {
                    changedSincePublish = true
                }
            }
            measured = end
            sinceSave += CHUNK
            if (changedSincePublish && (sinceSave >= PUBLISH_EVERY || measured == sizes.count)) {
                _pageSizes.value = sizes.copy()
                changedSincePublish = false
            }
            if (sinceSave >= PUBLISH_EVERY || measured == sizes.count) {
                sinceSave = 0
                documentId?.let { deps.reader.savePageMetrics(it, PageMetrics(sizes.copy(), measured)) }
            }
        }
    }

    fun onPositionChanged(position: PagePosition, zoom: Float) {
        if (documentId == null) return
        val p = SavedPosition(position.page, position.pageOffset, zoom)
        if (p == lastSaved) return
        lastSaved = p
        positionSaver.submit(p)
    }

    /** Called when the screen stops: persist now, the process may be killed afterwards. */
    fun flush() {
        persistScope.launch { positionSaver.flush() }
    }

    fun addToLibrary() {
        val uri = (source as? ReaderSource.ExternalUri)?.uri ?: return
        deps.importer.import(listOf(Uri.parse(uri)), folderId = null)
        events.trySend(ReaderEvent.AddedToLibrary)
    }

    private fun fail(error: ReaderError) {
        _state.value = ReaderUiState.Error(error)
    }

    override fun onCleared() {
        val cookie = PerfTrace.begin(PerfTrace.CLOSE_DOCUMENT)
        pipeline?.close()
        pipeline = null
        val doc = document
        document = null
        val copy = externalCopy
        persistScope.launch {
            try {
                positionSaver.flush()
            } finally {
                // Cleanup must always run and must not throw: this scope has no exception handler,
                // so a failure here would crash the process after the reader is already gone.
                runCatching { doc?.close() }
                runCatching { copy?.delete() }
                PerfTrace.end(PerfTrace.CLOSE_DOCUMENT, cookie)
                persistScope.cancel()
            }
        }
    }

    private fun PdfOpenException.toReaderError(): ReaderError = when (this) {
        is PdfOpenException.PasswordRequired -> ReaderError.PASSWORD_PROTECTED
        is PdfOpenException.Corrupted -> ReaderError.CORRUPTED
        is PdfOpenException.OutOfMemory -> ReaderError.OUT_OF_MEMORY
        is PdfOpenException.NotSeekable, is PdfOpenException.Unreadable -> ReaderError.UNREADABLE
    }

    private companion object {
        const val CHUNK = 16
        const val PUBLISH_EVERY = 256
        const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
