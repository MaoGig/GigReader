package com.maogig.gigreader.feature.reader

import android.app.Application
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maogig.gigreader.core.common.cache.WeightedLruCache
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
import com.maogig.gigreader.core.data.reader.SavedReadingPosition
import com.maogig.gigreader.core.data.settings.SettingsRepository
import com.maogig.gigreader.core.model.AppSettings
import com.maogig.gigreader.core.model.DocumentSource
import com.maogig.gigreader.core.pdf.PerfTrace
import com.maogig.gigreader.core.pdf.engine.PdfDocument
import com.maogig.gigreader.core.pdf.engine.PdfEngine
import com.maogig.gigreader.core.pdf.engine.PdfOpenException
import com.maogig.gigreader.core.pdf.render.RenderBudgets
import com.maogig.gigreader.core.pdf.render.RenderPipeline
import com.maogig.gigreader.feature.reader.nav.LinkHits
import com.maogig.gigreader.feature.reader.nav.LinkRegion
import com.maogig.gigreader.feature.reader.nav.ReaderNavigation
import com.maogig.gigreader.feature.reader.nav.ThumbnailLoader
import com.maogig.gigreader.feature.reader.nav.TocModel
import com.maogig.gigreader.feature.reader.viewport.ViewportPosition
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

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

enum class ReaderError {
    NOT_FOUND,
    FILE_MISSING,

    /** Encrypted PDF while the active engine cannot take a password (only the framework fallback below API 35). */
    PASSWORD_PROTECTED,
    CORRUPTED,
    UNREADABLE,
    OUT_OF_MEMORY,

    /** The app's database failed (e.g. disk full or corrupted), not the document. */
    STORAGE,
}

/** Everything the viewport needs for one open document. Created once per open. */
@Immutable
class ReaderSession(
    val title: String,
    val pageCount: Int,
    val isExternal: Boolean,
    val initialPosition: PagePosition,
    val initialZoom: Float,
    val initialOffsetXFraction: Float,
    val pipeline: RenderPipeline,
    val planner: RenderPlanner,
)

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    /** The PDF is encrypted: ask for its password ([wrongPassword] after a failed attempt). */
    data class PasswordRequired(val wrongPassword: Boolean) : ReaderUiState

    data class Ready(val session: ReaderSession) : ReaderUiState

    data class Error(val error: ReaderError) : ReaderUiState
}

/** Which navigation panel is open next to / over the page. */
enum class ReaderPanel { NONE, CONTENTS, PAGES }

/** The table of contents, loaded lazily the first time the panel is opened. */
sealed interface OutlineState {
    data object NotLoaded : OutlineState

    data object Loading : OutlineState

    /** [model] is empty when the document has no outline. */
    data class Loaded(val model: TocModel) : OutlineState
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

    private val _panel = MutableStateFlow(ReaderPanel.NONE)

    /** The open navigation panel. Closed by default: a closed panel holds no state and does no work. */
    val panel: StateFlow<ReaderPanel> = _panel.asStateFlow()

    private val _outline = MutableStateFlow<OutlineState>(OutlineState.NotLoaded)
    val outline: StateFlow<OutlineState> = _outline.asStateFlow()

    private val _tocExpanded = MutableStateFlow<Set<Int>>(emptySet())

    /** Ids of the table-of-contents sections currently expanded (see [TocModel]). */
    val tocExpanded: StateFlow<Set<Int>> = _tocExpanded.asStateFlow()

    private val _thumbnails = MutableStateFlow<ThumbnailLoader?>(null)

    /** Exists only while the pages panel is open. */
    val thumbnails: StateFlow<ThumbnailLoader?> = _thumbnails.asStateFlow()

    /** Jump history and the transient back/forward hint. */
    val navigation = ReaderNavigation()

    private val events = Channel<ReaderEvent>(Channel.BUFFERED)
    val eventFlow: Flow<ReaderEvent> = events.receiveAsFlow()

    /** Outlives viewModelScope so the final position write and the document close always complete. */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val documentId: String? = (source as? ReaderSource.LibraryDocument)?.documentId

    // CoalescingSaver catches (and retries) failed writes: a full disk never crashes the reader.
    private val positionSaver = CoalescingSaver<SavedReadingPosition>(persistScope, delayMillis = 1_500) { p ->
        documentId?.let { deps.reader.savePosition(it, p) }
    }

    // Main thread only (like every method of this class not marked otherwise).
    private var document: PdfDocument? = null
    private var pipeline: RenderPipeline? = null
    private var externalCopy: File? = null
    private var lastSaved: SavedReadingPosition? = null

    /** Page labels already asked for (`null` = the page has none). Main thread only. */
    private val pageLabels = HashMap<Int, String?>()

    /** Links of recently visible pages (weight 1 each). Thread-safe: read by tap handling. */
    private val linkCache = WeightedLruCache<Int, List<LinkRegion>>(LINK_CACHE_PAGES, { _, _ -> 1L })
    private val linkJobs = HashMap<Int, Job>()
    private var visiblePages: IntRange? = null

    /** The screen is not visible (ON_STOP or left the composition): nothing renders or measures. */
    private var stopped = false
    private var measurement: Measurement? = null
    private var measureJob: Job? = null

    init {
        viewModelScope.launch { open(password = null) }
    }

    /** Retries opening with [password] after [ReaderUiState.PasswordRequired]. */
    fun submitPassword(password: String) {
        if (_state.value !is ReaderUiState.PasswordRequired) return
        // Leaving PasswordRequired right away also ignores a double submit.
        _state.value = ReaderUiState.Loading
        viewModelScope.launch { open(password) }
    }

    private suspend fun open(password: String?) {
        val cookie = PerfTrace.begin(PerfTrace.OPEN_DOCUMENT)
        // "Time to first page": from here until the first page bitmap is ready (or the open fails).
        val firstPageCookie = PerfTrace.begin(PerfTrace.FIRST_PAGE)
        var firstPageTracked = false
        try {
            val opened = when (source) {
                is ReaderSource.LibraryDocument -> openLibraryDocument(source.documentId, password)
                is ReaderSource.ExternalUri -> openExternal(Uri.parse(source.uri), password)
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
                // Bookkeeping only: a failed write must not keep the user from reading. The page count
                // also fills in documents imported while encrypted (stored with 0 pages).
                bestEffortWrite { deps.reader.markOpened(it) }
                bestEffortWrite { deps.reader.updatePageCount(it, pageCount) }
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
            // Opened while the screen was stopped: render nothing until it is visible again.
            if (stopped) newPipeline.pause()
            firstPageTracked = true
            viewModelScope.launch {
                try {
                    newPipeline.version.first { it > 0 }
                } finally {
                    PerfTrace.end(PerfTrace.FIRST_PAGE, firstPageCookie)
                }
            }
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
                    initialOffsetXFraction = position?.offsetXFraction ?: 0f,
                    pipeline = newPipeline,
                    planner = planner,
                ),
            )
            if (!metrics.isComplete) {
                measurement = Measurement(metrics.sizes.copy(), metrics.measuredCount)
                startMeasuring()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfOpenException.PasswordRequired) {
            if (deps.engine.supportsPasswords) {
                _state.value = ReaderUiState.PasswordRequired(wrongPassword = password != null)
            } else {
                fail(ReaderError.PASSWORD_PROTECTED)
            }
        } catch (e: PdfOpenException) {
            fail(e.toReaderError())
        } catch (e: SQLiteException) {
            // Before RuntimeException: a database failure is not a corrupted document.
            fail(ReaderError.STORAGE)
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
            if (!firstPageTracked) PerfTrace.end(PerfTrace.FIRST_PAGE, firstPageCookie)
        }
    }

    private class Opened(val document: PdfDocument, val title: String)

    private suspend fun openLibraryDocument(id: String, password: String?): Opened? {
        val doc = deps.library.document(id)
        if (doc == null || doc.trashedAt != null) {
            fail(ReaderError.NOT_FOUND)
            return null
        }
        val pdf = when (val s = doc.source) {
            is DocumentSource.Managed -> {
                // fileFor canonicalizes paths (file system access): never on the main thread.
                val file = withContext(Dispatchers.IO) { deps.files.fileFor(s.relativePath).takeIf { it.isFile } }
                if (file == null) {
                    fail(ReaderError.FILE_MISSING)
                    return null
                }
                deps.engine.open(file, password)
            }
            is DocumentSource.Linked -> openUri(Uri.parse(s.uri), password)
        }
        return Opened(pdf, doc.title)
    }

    private suspend fun openExternal(uri: Uri, password: String?): Opened {
        val name = withContext(Dispatchers.IO) { displayName(uri) }
        return Opened(openUri(uri, password), FileNames.titleFromFileName(name))
    }

    /** Opens a URI in place when the provider gives a seekable file, else from a temporary copy. */
    private suspend fun openUri(uri: Uri, password: String?): PdfDocument {
        // A password retry reuses the copy made by the first attempt.
        externalCopy?.let { return deps.engine.open(it, password) }
        var opened: ParcelFileDescriptor? = null
        val descriptor = try {
            withContext(Dispatchers.IO) { app.contentResolver.openFileDescriptor(uri, "r").also { opened = it } }
        } catch (e: FileNotFoundException) {
            // Also thrown for "Not a whole file" (the provider serves a section of a file or only a
            // stream); openInputStream below may still work. A truly missing file fails there too.
            null
        } catch (e: CancellationException) {
            // withContext may discard a descriptor that was already opened (prompt cancellation).
            opened?.let { runCatching { it.close() } }
            throw e
        }
        if (descriptor != null) {
            try {
                return deps.engine.open(descriptor, password) // the engine owns (and closes) the descriptor
            } catch (e: PdfOpenException.NotSeekable) {
                // Streaming provider (pipe/socket): fall back to a temporary copy.
            }
        }
        val copy = copyToCache(uri)
        externalCopy = copy
        return deps.engine.open(copy, password)
    }

    /**
     * Reads [uri] once into this reader's own temporary file (unique per ViewModel, so another
     * reader's cleanup can never delete it); cancellable, never leaves a stray copy.
     */
    private suspend fun copyToCache(uri: Uri): File {
        var target: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, EXTERNAL_DIR)
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create $dir")
                // Copies orphaned by a killed process. Never a live reader's: an open document keeps
                // its file descriptor, so even a very old copy still in use survives the unlink.
                val now = System.currentTimeMillis()
                dir.listFiles()?.forEach { if (now - it.lastModified() > STALE_COPY_MILLIS) it.delete() }
                val file = File.createTempFile("open-", ".pdf", dir)
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
     * Progress of the background page-size measurement. Used by one measuring coroutine at a time
     * (a new one joins the previous first); the main thread only reads the volatile flags.
     */
    private class Measurement(val sizes: PageSizes, measured: Int) {
        @Volatile
        var measured: Int = measured

        /** A page failed to load: the rest keeps its estimates (not retried until the next open). */
        @Volatile
        var failed: Boolean = false
        var persisted: Int = measured
        var changedSincePublish: Boolean = false

        val isDone: Boolean get() = failed || measured >= sizes.count
    }

    private fun startMeasuring() {
        val doc = document ?: return
        val m = measurement ?: return
        if (stopped || m.isDone || measureJob?.isActive == true) return
        val previous = measureJob
        measureJob = viewModelScope.launch(Dispatchers.Default) {
            previous?.join() // a stopped run may still be persisting its progress
            measureRemaining(doc, m)
        }
    }

    private fun stopMeasuring() {
        measureJob?.cancel() // the run persists what it measured on its way out
    }

    /**
     * Measures real page sizes in small chunks so renders interleave on the engine's serial queue.
     * Publishes refined sizes at most every [PUBLISH_EVERY] pages (each publish rebuilds the layout
     * once, anchored at the current page) and persists them so the next open needs no measuring.
     * Stopping (screen stopped, reader closed) persists the progress made so far.
     */
    private suspend fun measureRemaining(doc: PdfDocument, m: Measurement) {
        val job = currentCoroutineContext()[Job]
        val sizes = m.sizes
        var sinceSave = 0
        try {
            while (m.measured < sizes.count) {
                val start = m.measured
                val end = min(sizes.count, start + CHUNK)
                val before = sizes.widths.copyOfRange(start, end) to sizes.heights.copyOfRange(start, end)
                try {
                    // Checked between pages, so stopping or closing the reader stops measuring within
                    // one page load.
                    doc.measurePages(start until end, sizes) { job?.isActive == false }
                    // A chunk cut short by stopping must not count as measured.
                    currentCoroutineContext().ensureActive()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    m.failed = true
                    break // a broken page must not stop reading; keep estimates for the rest
                }
                for (i in start until end) {
                    if (sizes.widths[i] != before.first[i - start] || sizes.heights[i] != before.second[i - start]) {
                        m.changedSincePublish = true
                    }
                }
                m.measured = end
                sinceSave += end - start
                if (m.changedSincePublish && (sinceSave >= PUBLISH_EVERY || end == sizes.count)) {
                    _pageSizes.value = sizes.copy()
                    m.changedSincePublish = false
                }
                if (sinceSave >= PUBLISH_EVERY || end == sizes.count) {
                    sinceSave = 0
                    persistMetrics(m)
                }
            }
        } finally {
            if (m.measured > m.persisted) withContext(NonCancellable) { persistMetrics(m) }
        }
    }

    private suspend fun persistMetrics(m: Measurement) {
        val id = documentId ?: return
        val measured = m.measured
        // A lost write only means measuring again from an earlier page on the next open.
        bestEffortWrite {
            deps.reader.savePageMetrics(id, PageMetrics(m.sizes.copy(), measured))
            m.persisted = measured
        }
    }

    /** Runs a database write whose failure (e.g. disk full) must not crash the reader. */
    private inline fun bestEffortWrite(write: () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to show: the write is retried by the next save of the same data.
        }
    }

    fun onPositionChanged(position: ViewportPosition) {
        navigation.onPosition(position.top)
        if (documentId == null) return
        val p = SavedReadingPosition(
            page = position.top.page,
            pageOffset = position.top.pageOffset,
            zoom = position.zoom,
            offsetXFraction = position.offsetXFraction,
            currentPage = position.currentPage,
            lastVisiblePage = position.lastVisiblePage,
        )
        if (p == lastSaved) return
        lastSaved = p
        positionSaver.submit(p)
    }

    /** The reader is visible again (ON_START): render what the viewport needs, keep measuring. */
    fun onStart() {
        if (!stopped) return
        stopped = false
        pipeline?.resume()
        startMeasuring()
        _thumbnails.value?.resume()
        visiblePages?.let(::fetchLinks)
    }

    /**
     * The reader is no longer visible (ON_STOP, or it left the composition under another screen):
     * persist the position now (the process may be killed afterwards), release every bitmap and
     * stop measuring page sizes. Nothing of this document runs until [onStart].
     *
     * [changingConfigurations]: the activity is only being recreated (rotation, window resize,
     * theme); the reader is visible again right away, so only the position is persisted and the
     * bitmaps stay (a rotated page is drawn from its previous bitmap until re-rendered).
     */
    fun onStop(changingConfigurations: Boolean = false) {
        persistScope.launch { positionSaver.flush() }
        if (changingConfigurations || stopped) return
        stopped = true
        pipeline?.pause()
        stopMeasuring()
        // Energy: no thumbnail or link work while invisible; thumbnail bitmaps are released too.
        _thumbnails.value?.pause()
        cancelLinkJobs()
    }

    // --- Navigation panels -------------------------------------------------------------------

    /** Opens [panel] (or closes any when [ReaderPanel.NONE]). Work starts only now, never before. */
    fun openPanel(panel: ReaderPanel) {
        if (panel == ReaderPanel.NONE) return closePanel()
        if (document == null) return
        _panel.value = panel
        when (panel) {
            ReaderPanel.CONTENTS -> {
                releaseThumbnails()
                loadOutline()
            }
            ReaderPanel.PAGES -> startThumbnails()
            ReaderPanel.NONE -> Unit
        }
    }

    /** Closes the panel and cancels/releases everything it was doing (thumbnail renders and bitmaps). */
    fun closePanel() {
        _panel.value = ReaderPanel.NONE
        releaseThumbnails()
    }

    /** Loads the outline once, off the main thread; safe to call repeatedly. */
    private fun loadOutline() {
        if (_outline.value !is OutlineState.NotLoaded) return
        val doc = document ?: return
        _outline.value = OutlineState.Loading
        viewModelScope.launch {
            val model = try {
                val items = doc.outline()
                withContext(Dispatchers.Default) { TocModel.from(items) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TocModel.Empty // a broken outline is the same as none
            }
            _outline.value = OutlineState.Loaded(model)
        }
    }

    fun toggleTocSection(id: Int) {
        val current = _tocExpanded.value
        _tocExpanded.value = if (id in current) current - id else current + id
    }

    /** Expands the sections above [nodeId] so it is visible (the panel opens at the current section). */
    fun revealTocNode(nodeId: Int) {
        val model = (_outline.value as? OutlineState.Loaded)?.model ?: return
        val current = _tocExpanded.value
        val revealed = model.expandedToReveal(nodeId, current)
        if (revealed !== current) _tocExpanded.value = revealed
    }

    private fun startThumbnails() {
        if (_thumbnails.value != null) return
        val doc = document ?: return
        val pipeline = pipeline ?: return
        val density = app.resources.displayMetrics.density
        val widthPx = (THUMBNAIL_WIDTH_DP * density).roundToInt().coerceIn(1, ThumbnailLoader.MAX_WIDTH_PX)
        val loader = ThumbnailLoader(
            document = doc,
            pageCount = doc.pageCount,
            widthPx = widthPx,
            aspectOf = ::pageAspect,
            viewportBusy = pipeline.isBusy,
            scope = viewModelScope,
            // Small, and never more than a quarter of what the viewport itself may cache.
            budgetBytes = min(THUMBNAIL_CACHE_BYTES, pipeline.budget.cacheBytes / 4),
        )
        if (stopped) loader.pause()
        _thumbnails.value = loader
    }

    private fun releaseThumbnails() {
        _thumbnails.value?.close()
        _thumbnails.value = null
    }

    /** Height / width of [page] from the (possibly still estimated) page sizes. */
    private fun pageAspect(page: Int): Float {
        val sizes = _pageSizes.value ?: return 0f
        if (page !in 0 until sizes.count) return 0f
        val w = sizes.widths[page]
        return if (w > 0f) sizes.heights[page] / w else 0f
    }

    // --- Page labels ---------------------------------------------------------------------------

    /** The printed label of [page] if it was already looked up (`null`: none or not yet known). */
    fun cachedPageLabel(page: Int): String? = pageLabels[page]

    /** The printed label of [page] ("iv"), or `null` when the document defines none. Cached. */
    suspend fun pageLabel(page: Int): String? {
        if (pageLabels.containsKey(page)) return pageLabels[page]
        val doc = document ?: return null
        val label = try {
            doc.pageLabel(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        pageLabels[page] = label
        return label
    }

    // --- Links ---------------------------------------------------------------------------------

    /** Links of [page] if already fetched (never blocks; tap handling needs an immediate answer). */
    fun linkRegions(page: Int): List<LinkRegion>? = linkCache.peek(page)

    /** The viewport now shows [pages]: fetch the links of those pages lazily. */
    fun onVisiblePagesChanged(pages: IntRange) {
        if (pages == visiblePages) return
        visiblePages = pages
        fetchLinks(pages)
    }

    private fun fetchLinks(pages: IntRange) {
        val doc = document ?: return
        if (stopped || !doc.capabilities.links) return
        // Pages that scrolled away lose their pending fetch.
        val stale = linkJobs.filter { (page, job) -> page !in pages || !job.isActive }.keys.toList()
        for (page in stale) linkJobs.remove(page)?.cancel()
        for (page in pages.first..min(pages.last, pages.first + MAX_LINK_PAGES - 1)) {
            if (page in linkCache || linkJobs.containsKey(page)) continue
            linkJobs[page] = viewModelScope.launch {
                try {
                    // Like thumbnails: wait for the viewport's renders instead of queueing before them.
                    pipeline?.isBusy?.first { busy -> !busy }
                    val regions = LinkHits.regionsOf(doc.links(page))
                    linkCache.put(page, regions)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    linkCache.put(page, emptyList()) // do not retry a page whose links fail to load
                } finally {
                    linkJobs.remove(page, currentCoroutineContext()[Job])
                }
            }
        }
    }

    private fun cancelLinkJobs() {
        val jobs = linkJobs.values.toList()
        linkJobs.clear()
        jobs.forEach { it.cancel() }
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
        releaseThumbnails()
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
        const val THUMBNAIL_WIDTH_DP = 128
        const val THUMBNAIL_CACHE_BYTES = 12L * 1024 * 1024
        const val LINK_CACHE_PAGES = 12L
        const val MAX_LINK_PAGES = 6
        const val PUBLISH_EVERY = 256
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val EXTERNAL_DIR = "external"
        const val STALE_COPY_MILLIS = 24L * 60 * 60 * 1000
    }
}
