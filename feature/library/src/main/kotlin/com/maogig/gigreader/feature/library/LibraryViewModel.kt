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
import kotlinx.coroutines.flow.flowOf
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

internal const val SEARCH_DEBOUNCE_MILLIS = 250L

/**
 * State holder of one library screen (the Home when [folderId] is `null`, otherwise a folder).
 *
 * Everything the screen shows is combined into one [LibraryUiState]: folder contents and Home
 * shelves (Room flows, invalidation driven), settings (layout/sort), filter, debounced search,
 * selection and the open dialog. Sorting and filtering run on [computeDispatcher], never during
 * composition. Nothing here polls: when the user does nothing, every flow is suspended.
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

    private val shelves: Flow<RootShelves> = if (folderId == null) {
        combine(
            library.observeContinueReading(),
            library.observeFavorites(),
            library.observeRecentNotes(),
        ) { continueReading, favorites, recentNotes -> RootShelves(continueReading, favorites, recentNotes) }
    } else {
        flowOf(RootShelves.Empty)
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

    private val source: Flow<SourceData> = combine(
        library.observeFolder(folderId),
        shelves,
        tree.filterNotNull(),
    ) { contents, rootShelves, folderTree -> SourceData(contents, rootShelves, folderTree) }

    private val viewPrefs: Flow<ViewPrefs> = combine(settings.settings, filter) { appSettings, activeFilter ->
        ViewPrefs(appSettings.libraryLayout, appSettings.librarySort, activeFilter)
    }.distinctUntilChanged()

    private val content: Flow<ContentSnapshot> = combine(source, viewPrefs, search) { data, prefs, searchState ->
        val now = clock.now()
        val built = buildLibraryContent(
            isRoot = folderId == null,
            contents = data.contents,
            shelves = data.shelves,
            search = searchState,
            filter = prefs.filter,
            sort = prefs.sort,
            nowMillis = now,
        )
        ContentSnapshot(
            content = built,
            prefs = prefs,
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

    fun duplicate(documentId: String) = launchSafely {
        val copyId = library.duplicateDocument(documentId)
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

    private class SourceData(val contents: FolderContents, val shelves: RootShelves, val tree: FolderTree)

    private data class ViewPrefs(val layout: LibraryLayout, val sort: SortOrder, val filter: LibraryFilter)

    private inner class ContentSnapshot(
        val content: LibraryContent,
        val prefs: ViewPrefs,
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
                filter = prefs.filter,
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
