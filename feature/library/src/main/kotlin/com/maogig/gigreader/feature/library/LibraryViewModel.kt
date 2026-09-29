package com.maogig.gigreader.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.FolderTree
import com.maogig.gigreader.core.common.undo.UndoManager
import com.maogig.gigreader.core.common.undo.UndoableAction
import com.maogig.gigreader.core.data.importer.ImportOutcome
import com.maogig.gigreader.core.data.importer.ImportProgress
import com.maogig.gigreader.core.data.library.FolderContents
import com.maogig.gigreader.core.data.library.ItemKind
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.data.library.LibraryRepository
import com.maogig.gigreader.core.data.library.LibrarySearchResults
import com.maogig.gigreader.core.data.library.MoveResult
import com.maogig.gigreader.core.data.library.ref
import com.maogig.gigreader.core.data.notes.NotesRepository
import com.maogig.gigreader.core.data.settings.SettingsRepository
import com.maogig.gigreader.core.model.DocumentSource
import com.maogig.gigreader.core.model.LibraryFilter
import com.maogig.gigreader.core.model.LibraryItem
import com.maogig.gigreader.core.model.LibraryLayout
import com.maogig.gigreader.core.model.SortField
import com.maogig.gigreader.core.model.SortOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal const val SEARCH_DEBOUNCE_MILLIS = 250L

/**
 * Set once this process started purging expired trash. The Home triggers the purge once per process
 * (the Trash screen purges again whenever it opens); there is no periodic job.
 */
internal val processTrashPurgeStarted = AtomicBoolean(false)

/** "No limit" for the library-wide Favorites view (the Home shelf uses the repository default). */
private const val UNLIMITED = Int.MAX_VALUE

/**
 * State holder of one library screen (the Home when [folderId] is `null`, otherwise a folder).
 *
 * Everything the screen shows is combined into one [LibraryUiState]: folder contents and Home
 * shelves (Room flows, invalidation driven), settings (layout/sort), filter, debounced search,
 * selection and the open dialog. Sorting and filtering run on [computeDispatcher], never during
 * composition. Nothing here polls: when the user does nothing, every flow is suspended.
 *
 * On the Home, filters and rail views are library-wide (every note, every favorite, everything opened
 * in the last 30 days…) and only the queries of the active view are observed. In a folder, filters
 * apply to the folder's own items.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LibraryViewModel(
    private val folderId: String?,
    private val library: LibraryRepository,
    private val notes: NotesRepository,
    private val settings: SettingsRepository,
    private val importer: ImportGateway,
    private val resolveFile: (String) -> File,
    private val clock: Clock = Clock.System,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
    private val trashPurgeStarted: AtomicBoolean = processTrashPurgeStarted,
) : ViewModel() {

    /** One history per screen; every undoable edit made here is recorded (plan §11). */
    val undoManager = UndoManager()

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val selection = MutableStateFlow<Set<ItemRef>>(emptySet())
    private val dialog = MutableStateFlow<LibraryDialog?>(null)

    /** Bumped after edits made from this screen so an active search reflects them. */
    private val searchRefresh = MutableStateFlow(0)

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    /** High-frequency import progress: collected by a leaf composable, not part of [uiState]. */
    val importProgress: StateFlow<ImportProgress> get() = importer.progress
    val importOutcomes: Flow<ImportOutcome> get() = importer.outcomes

    private val tree: StateFlow<FolderTree?> = library.observeFolderTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** Folders of the side panel (expanded screens): collected only while the panel is shown. */
    val folderPanel: StateFlow<List<FolderPanelRow>> = tree.filterNotNull()
        .map(::buildFolderPanelRows)
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * What the listing is built from, tagged with the filter it was loaded for, so a frame never
     * mixes a new filter with the previous view's items. On the Home, flatMapLatest drops the
     * previous view's queries: only what the active view shows is observed.
     */
    private val pool: Flow<Pool> = if (folderId == null) {
        filter.flatMapLatest(::rootPool)
    } else {
        combine(library.observeFolder(folderId), filter) { contents, active -> Pool(active, contents) }
    }

    private val search: Flow<SearchSnapshot> =
        combine(query, searchRefresh) { q, tick -> q.trim() to tick }
            .distinctUntilChanged()
            .mapLatest { (q, _) ->
                if (q.isEmpty()) {
                    SearchSnapshot.Idle
                } else {
                    // mapLatest cancels this while the user keeps typing: a debounce without timers.
                    delay(searchDebounceMillis)
                    SearchSnapshot(q, searchSafely(q))
                }
            }

    private val source: Flow<SourceData> = combine(pool, tree.filterNotNull()) { data, folderTree ->
        SourceData(data, folderTree)
    }

    private val viewPrefs: Flow<ViewPrefs> = settings.settings
        .map { appSettings -> ViewPrefs(appSettings.libraryLayout, appSettings.librarySort) }
        .distinctUntilChanged()

    private val content: Flow<ContentSnapshot> = combine(source, viewPrefs, search) { data, prefs, searchState ->
        val now = clock.now()
        val built = buildLibraryContent(
            isRoot = folderId == null,
            contents = data.pool.contents,
            shelves = data.pool.shelves,
            search = searchState,
            filter = data.pool.filter,
            sort = prefs.sort,
            nowMillis = now,
        )
        ContentSnapshot(
            content = built,
            prefs = prefs,
            filter = data.pool.filter,
            searchActive = searchState.results != null,
            folderName = folderId?.let { data.tree[it]?.name },
            breadcrumbs = buildBreadcrumbs(data.tree, folderId),
            // Only used for day-granularity labels: quantized so unrelated data changes do not
            // hand every visible item a new value (which would defeat recomposition skipping).
            nowMillis = now - now % NOW_QUANTUM_MILLIS,
        )
    }.flowOn(computeDispatcher)
        // Items that disappeared (trashed, moved away, hidden by a filter or an undo) leave the
        // selection for good, so a later tap or bulk action never acts on something not on screen.
        .onEach { snapshot -> pruneSelection(snapshot.content.visible) }

    val uiState: StateFlow<LibraryUiState> = combine(content, selection, dialog) { snapshot, selected, openDialog ->
        snapshot.toUiState(selected, openDialog)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LibraryUiState(folderId = folderId))

    /** Selection read synchronously (the UI state may lag one dispatch behind a long press). */
    val inSelectionMode: Boolean get() = selection.value.isNotEmpty()

    init {
        if (folderId == null) purgeExpiredTrashOnce()
    }

    /**
     * Items expire from the trash after 30 days. Besides the Trash screen, the Home purges once per
     * process, after its first content is shown so it never competes with the startup queries.
     * Failures are silent (the next Home or the Trash screen tries again).
     */
    private fun purgeExpiredTrashOnce() {
        if (!trashPurgeStarted.compareAndSet(false, true)) return
        viewModelScope.launch {
            var purged = false
            try {
                uiState.first { !it.loading }
                library.purgeExpiredTrash()
                purged = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing to tell the user: expired items simply stay in the trash a little longer.
            } finally {
                if (!purged) trashPurgeStarted.set(false)
            }
        }
    }

    /** Library-wide pool of one Home view: only the queries that view needs. */
    private fun rootPool(active: LibraryFilter): Flow<Pool> = when (active) {
        LibraryFilter.ALL -> combine(
            library.observeFolder(null),
            library.observeContinueReading(),
            library.observeFavorites(),
            library.observeRecentNotes(),
        ) { contents, continueReading, favorites, recentNotes ->
            Pool(active, contents, RootShelves(continueReading, favorites, recentNotes))
        }
        // "Annotated" is every document filtered by its denormalized annotation count.
        LibraryFilter.PDFS, LibraryFilter.ANNOTATED ->
            library.observeAllDocuments().map { documents -> Pool.of(active, documents = documents) }
        LibraryFilter.NOTES -> library.observeAllNotes().map { notes -> Pool.of(active, notes = notes) }
        LibraryFilter.FAVORITES -> combine(
            library.observeFavorites(limit = UNLIMITED),
            library.observeFavoriteNotes(),
        ) { documents, notes -> Pool.of(active, documents, notes) }
        LibraryFilter.RECENT -> library.observeOpenedSince(clock.now() - RECENT_WINDOW_MILLIS)
            .map { documents -> Pool.of(active, documents = documents) }
    }

    // region Search, filter, sort, layout

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun setFilter(value: LibraryFilter) {
        filter.value = value
    }

    fun setLayout(layout: LibraryLayout) = launchSafely {
        settings.update { it.copy(libraryLayout = layout) }
    }

    fun setSortField(field: SortField) = launchSafely {
        settings.update { it.copy(librarySort = it.librarySort.copy(field = field)) }
    }

    fun setSortAscending(ascending: Boolean) = launchSafely {
        settings.update { it.copy(librarySort = it.librarySort.copy(ascending = ascending)) }
    }

    // endregion

    // region Selection

    fun onLongPress(ref: ItemRef) {
        selection.update { it + ref }
    }

    fun toggleSelection(ref: ItemRef) {
        selection.update { if (ref in it) it - ref else it + ref }
    }

    fun selectAll() {
        val selectable = uiState.value.rows.mapNotNull { (it as? LibraryRow.Cell)?.ref }
        if (selectable.isNotEmpty()) selection.value = selectable.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    // endregion

    // region Drag and drop

    /** A drag starts from a long press: the pressed item joins the selection. Returns the count. */
    fun beginDrag(ref: ItemRef): Int {
        selection.update { it + ref }
        return selection.value.size
    }

    fun canDrop(target: DropTarget): Boolean {
        val dragged = selection.value
        if (dragged.isEmpty()) return false
        return when (target) {
            is DropTarget.Folder -> {
                val folderTree = tree.value ?: return false
                ItemRef(target.id, ItemKind.FOLDER) !in dragged &&
                    folderTree.canMoveInto(dragged.folderIds(), target.id)
            }
            DropTarget.Root -> folderId != null
            DropTarget.Favorites -> dragged.any { it.kind != ItemKind.FOLDER }
        }
    }

    fun drop(target: DropTarget) {
        if (!canDrop(target)) return
        val dragged = selection.value.toList()
        when (target) {
            is DropTarget.Folder -> move(dragged, target.id)
            DropTarget.Root -> move(dragged, null)
            // Folders cannot be favorites; setFavorite ignores them and clears the whole drag.
            DropTarget.Favorites -> setFavorite(dragged, true)
        }
    }

    // endregion

    // region Dialogs

    fun requestNewFolder() {
        dialog.value = LibraryDialog.NewFolder
    }

    fun requestRename(item: LibraryItem) {
        dialog.value = LibraryDialog.Rename(item.ref(), item.title)
    }

    fun requestMove(items: List<ItemRef>) {
        val folderTree = tree.value ?: return
        if (items.isEmpty()) return
        dialog.value = LibraryDialog.Move(items, buildMoveTargets(folderTree, items))
    }

    fun requestMoveSelection() = requestMove(selection.value.toList())

    fun showInfo(document: LibraryItem.DocumentEntry) {
        dialog.value = LibraryDialog.Info(document)
    }

    fun dismissDialog() {
        dialog.value = null
    }

    // endregion

    // region Edits

    fun createFolder(name: String) {
        dialog.value = null
        launchSafely {
            library.createFolder(folderId, name)
            refreshSearch()
        }
    }

    /** Creates an empty note in this folder and opens it right away (no dialog). */
    fun createNote() = launchSafely {
        val id = notes.createNote(folderId)
        _events.send(LibraryEvent.OpenNote(id))
    }

    fun rename(ref: ItemRef, newName: String) {
        dialog.value = null
        launchSafely {
            val action = library.rename(ref, newName) ?: return@launchSafely
            record(action)
            _events.send(LibraryEvent.Message(LibraryMessage.Renamed, undoable = true))
        }
    }

    fun moveTo(items: List<ItemRef>, targetFolderId: String?) {
        dialog.value = null
        move(items, targetFolderId)
    }

    private fun move(items: List<ItemRef>, targetFolderId: String?) = launchSafely {
        when (val result = library.move(items, targetFolderId)) {
            is MoveResult.Moved -> {
                record(result.undo)
                removeFromSelection(items)
                _events.send(LibraryEvent.Message(LibraryMessage.Moved(items.size), undoable = true))
            }
            MoveResult.WouldCreateCycle -> _events.send(LibraryEvent.Message(LibraryMessage.MoveCycle))
            MoveResult.NothingToMove -> Unit
        }
    }

    fun setFavorite(items: List<ItemRef>, favorite: Boolean) = launchSafely {
        val favoritable = items.filter { it.kind != ItemKind.FOLDER }
        val action = library.setFavorite(favoritable, favorite) ?: return@launchSafely
        record(action)
        removeFromSelection(items)
        val message = if (favorite) {
            LibraryMessage.Favorited(favoritable.size)
        } else {
            LibraryMessage.Unfavorited(favoritable.size)
        }
        _events.send(LibraryEvent.Message(message, undoable = true))
    }

    /** Adds the selection to favorites, or removes it when everything selected already is one. */
    fun toggleFavoriteSelection() {
        val state = uiState.value
        setFavorite(state.selection.toList(), favorite = !state.selectionAllFavorite)
    }

    fun trash(items: List<ItemRef>) = launchSafely {
        if (items.isEmpty()) return@launchSafely
        val action = library.moveToTrash(items) ?: return@launchSafely
        record(action)
        removeFromSelection(items)
        _events.send(LibraryEvent.Message(LibraryMessage.Trashed(items.size), undoable = true))
    }

    fun trashSelection() = trash(selection.value.toList())

    /** Resolves the managed files of the selected documents and asks the UI to share them. */
    fun share(items: List<ItemRef>) = launchSafely {
        val documentIds = items.filter { it.kind == ItemKind.DOCUMENT }.map { it.id }
        val relativePaths = documentIds.mapNotNull { id ->
            (library.document(id)?.source as? DocumentSource.Managed)?.relativePath
        }
        if (relativePaths.isEmpty()) {
            _events.send(LibraryEvent.Message(LibraryMessage.ShareNothing))
            return@launchSafely
        }
        val files = withContext(ioDispatcher) { relativePaths.map(resolveFile) }
        _events.send(LibraryEvent.Share(files))
    }

    fun shareSelection() = share(selection.value.toList())

    /** [copyTitle] is localized by the UI, e.g. "Paper (cópia)". */
    fun duplicate(documentId: String, copyTitle: String) = launchSafely {
        val copyId = library.duplicateDocument(documentId, copyTitle)
        val copy = listOf(ItemRef(copyId, ItemKind.DOCUMENT))
        record(
            object : UndoableAction {
                override val label: String = "Duplicated"

                override suspend fun undo() {
                    library.moveToTrash(copy)
                }

                override suspend fun redo() {
                    library.restoreFromTrash(copy)
                }
            },
        )
        _events.send(LibraryEvent.Message(LibraryMessage.Duplicated, undoable = true))
    }

    fun undo() = launchSafely {
        undoManager.undo()
        refreshSearch()
    }

    /**
     * "Already in your library (in the trash) · Restore" after an import. A document trashed together
     * with its folder cannot come back alone (it would stay inside a trashed folder): the user is
     * told to restore the folder from the trash instead.
     */
    fun restoreDocumentFromTrash(documentId: String) = launchSafely {
        val document = library.document(documentId)
        if (document == null) {
            _events.send(LibraryEvent.Message(LibraryMessage.Failed))
            return@launchSafely
        }
        if (document.trashedAt != null) {
            val ref = ItemRef(documentId, ItemKind.DOCUMENT)
            if (library.observeTrash().first().none { it.ref == ref }) {
                _events.send(LibraryEvent.Message(LibraryMessage.RestoreWithFolder))
                return@launchSafely
            }
            library.restoreFromTrash(listOf(ref))
            refreshSearch()
        }
        _events.send(LibraryEvent.Message(LibraryMessage.Restored(document.title)))
    }

    fun importDocuments(uris: List<Uri>) {
        if (uris.isNotEmpty()) importer.import(uris, folderId)
    }

    fun cancelImport() = importer.cancelAll()

    // endregion

    private suspend fun record(action: UndoableAction) {
        undoManager.record(action)
        refreshSearch()
    }

    private fun refreshSearch() {
        if (query.value.isNotBlank()) searchRefresh.update { it + 1 }
    }

    private fun pruneSelection(visible: Map<ItemRef, LibraryItem>) {
        selection.update { current ->
            if (current.all { it in visible }) current else current.filterTo(LinkedHashSet()) { it in visible }
        }
    }

    private fun removeFromSelection(items: Collection<ItemRef>) {
        if (items.isEmpty()) return
        val removed = items.toSet()
        selection.update { current -> current.filterNotTo(LinkedHashSet()) { it in removed } }
    }

    private suspend fun searchSafely(q: String): LibrarySearchResults = try {
        library.search(q)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LibrarySearchResults(emptyList(), emptyList(), emptyList())
    }

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(LibraryEvent.Message(LibraryMessage.Failed))
            }
        }
    }

    private class Pool(
        val filter: LibraryFilter,
        val contents: FolderContents,
        val shelves: RootShelves = RootShelves.Empty,
    ) {
        companion object {
            fun of(
                filter: LibraryFilter,
                documents: List<LibraryItem.DocumentEntry> = emptyList(),
                notes: List<LibraryItem.NoteEntry> = emptyList(),
            ) = Pool(filter, FolderContents(emptyList(), documents, notes))
        }
    }

    private class SourceData(val pool: Pool, val tree: FolderTree)

    private data class ViewPrefs(val layout: LibraryLayout, val sort: SortOrder)

    private inner class ContentSnapshot(
        val content: LibraryContent,
        val prefs: ViewPrefs,
        val filter: LibraryFilter,
        val searchActive: Boolean,
        val folderName: String?,
        val breadcrumbs: List<Crumb>,
        val nowMillis: Long,
    ) {
        fun toUiState(selected: Set<ItemRef>, openDialog: LibraryDialog?): LibraryUiState {
            // Items that disappeared (trashed, moved elsewhere) silently leave the selection.
            val visibleSelection = if (selected.isEmpty()) {
                emptySet()
            } else {
                selected.filterTo(LinkedHashSet()) { it in content.visible }
            }
            var hasDocuments = false
            var favoritable = 0
            var favorites = 0
            for (ref in visibleSelection) {
                val item = content.visible[ref] ?: continue
                if (item is LibraryItem.DocumentEntry) hasDocuments = true
                if (item !is LibraryItem.FolderEntry) {
                    favoritable++
                    if (item.isFavorite()) favorites++
                }
            }
            return LibraryUiState(
                folderId = folderId,
                loading = false,
                folderName = folderName,
                breadcrumbs = breadcrumbs,
                layout = prefs.layout,
                sort = prefs.sort,
                filter = filter,
                searchActive = searchActive,
                rows = content.rows,
                selection = visibleSelection,
                selectionHasDocuments = hasDocuments,
                selectionCanFavorite = favoritable > 0,
                selectionAllFavorite = favoritable > 0 && favorites == favoritable,
                dialog = openDialog,
                nowMillis = nowMillis,
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val NOW_QUANTUM_MILLIS = 60L * 60 * 1000

        fun Set<ItemRef>.folderIds(): List<String> = filter { it.kind == ItemKind.FOLDER }.map { it.id }
    }
}
