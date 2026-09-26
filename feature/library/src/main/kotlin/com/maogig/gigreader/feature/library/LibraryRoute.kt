package com.maogig.gigreader.feature.library

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maogig.gigreader.core.data.importer.ImportOutcome
import com.maogig.gigreader.core.data.library.CoverRepository
import com.maogig.gigreader.core.data.library.ref
import com.maogig.gigreader.core.model.LibraryFilter
import com.maogig.gigreader.core.model.LibraryItem
import com.maogig.gigreader.core.model.LibraryLayout
import com.maogig.gigreader.core.ui.adaptive.rememberWindowSize
import com.maogig.gigreader.core.ui.components.SectionHeader
import com.maogig.gigreader.core.ui.theme.LocalUiPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/** Space kept free under the last row so the FAB never covers it. */
private val FabClearance = 88.dp

/** Width of the folder panel shown next to the grid on expanded screens. */
private val FolderPanelWidth = 280.dp

/**
 * The library: the Home (a modern personal library) when [folderId] is `null`, otherwise a folder.
 * Entry point used by the app's navigation (see GigReaderApp).
 *
 * [onOpenFolder] is also used by the breadcrumbs and the folder panel for folders that may already be
 * in the back stack (the app pops back to them instead of pushing a copy). [onOpenRoot] returns to the
 * library root (breadcrumb root, folder panel); when `null`, [onNavigateUp] is used instead.
 */
@Composable
fun LibraryRoute(
    folderId: String?,
    deps: LibraryDependencies,
    onOpenFolder: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateUp: (() -> Unit)?,
    onOpenRoot: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val factory = remember(folderId, deps) {
        viewModelFactory {
            initializer {
                LibraryViewModel(
                    folderId = folderId,
                    library = deps.library,
                    notes = deps.notes,
                    settings = deps.settings,
                    importer = ImportManagerGateway(deps.importer),
                    resolveFile = deps.files::fileFor,
                )
            }
        }
    }
    val viewModel: LibraryViewModel = viewModel(factory = factory)
    LibraryScreen(
        viewModel = viewModel,
        covers = deps.covers,
        onOpenFolder = onOpenFolder,
        onOpenDocument = onOpenDocument,
        onOpenNote = onOpenNote,
        onOpenTrash = onOpenTrash,
        onOpenSettings = onOpenSettings,
        onNavigateUp = onNavigateUp,
        onOpenRoot = onOpenRoot ?: onNavigateUp,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    viewModel: LibraryViewModel,
    covers: CoverRepository,
    onOpenFolder: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateUp: (() -> Unit)?,
    onOpenRoot: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val windowSize = rememberWindowSize()
    val large = windowSize.isLarge
    val showFolderPanel = windowSize.canShowSidePanel
    val gridState = rememberLazyGridState()

    val currentContext by rememberUpdatedState(context)
    val currentOpenFolder by rememberUpdatedState(onOpenFolder)
    val currentOpenDocument by rememberUpdatedState(onOpenDocument)
    val currentOpenNote by rememberUpdatedState(onOpenNote)

    // Search text lives in the UI (a TextField must never wait for a flow); the ViewModel debounces.
    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        if (query.isNotEmpty()) viewModel.onQueryChange(query) // restored after process death
    }
    val onQueryChange: (String) -> Unit = remember(viewModel) {
        { value ->
            query = value
            viewModel.onQueryChange(value)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importDocuments(uris)
    }
    val launchImport: () -> Unit = {
        try {
            importLauncher.launch(arrayOf(PDF_MIME_TYPE))
        } catch (e: ActivityNotFoundException) {
            // No document picker on this device; nothing sensible to do.
        }
    }

    val callbacks = remember(viewModel) {
        val open: (LibraryItem) -> Unit = { item ->
            when (item) {
                is LibraryItem.FolderEntry -> currentOpenFolder(item.id)
                is LibraryItem.DocumentEntry -> currentOpenDocument(item.id)
                is LibraryItem.NoteEntry -> currentOpenNote(item.id)
            }
        }
        LibraryItemCallbacks(
            onClick = { item ->
                if (viewModel.inSelectionMode) viewModel.toggleSelection(item.ref()) else open(item)
            },
            onLongClick = { ref -> viewModel.onLongPress(ref) },
            onAction = { item, action ->
                val refs = listOf(item.ref())
                when (action) {
                    ItemAction.OPEN -> open(item)
                    ItemAction.RENAME -> viewModel.requestRename(item)
                    ItemAction.MOVE -> viewModel.requestMove(refs)
                    ItemAction.FAVORITE -> viewModel.setFavorite(refs, true)
                    ItemAction.UNFAVORITE -> viewModel.setFavorite(refs, false)
                    ItemAction.SHARE -> viewModel.share(refs)
                    ItemAction.INFO -> if (item is LibraryItem.DocumentEntry) viewModel.showInfo(item)
                    ItemAction.DUPLICATE -> viewModel.duplicate(
                        documentId = item.id,
                        copyTitle = currentContext.getString(R.string.library_copy_title, item.title),
                    )
                    ItemAction.DELETE -> viewModel.trash(refs)
                }
            },
            onDragStart = { ref -> viewModel.beginDrag(ref) },
        )
    }
    val dragDrop = remember(viewModel) { DragDropState(canDrop = viewModel::canDrop, onDrop = viewModel::drop) }

    // One-shot events: snackbars (a new one replaces the current one), navigation, sharing.
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is LibraryEvent.OpenNote -> currentOpenNote(event.noteId)
                is LibraryEvent.Share -> shareOrReport(context, snackbarHostState, event.files)
                is LibraryEvent.Message -> showLibraryMessage(
                    host = snackbarHostState,
                    context = context,
                    message = event.message,
                    undoable = event.undoable,
                    onUndo = viewModel::undo,
                    onOpenDocument = { currentOpenDocument(it) },
                    onRestoreDocument = viewModel::restoreDocumentFromTrash,
                )
            }
        }
    }
    // Import results arrive one per file; bursts are summarized into a few snackbars.
    LaunchedEffect(viewModel, snackbarHostState) {
        val pending = Channel<ImportOutcome>(Channel.UNLIMITED)
        launch { viewModel.importOutcomes.collect { pending.send(it) } }
        for (first in pending) {
            val batch = ArrayList<ImportOutcome>()
            batch.add(first)
            var next = pending.tryReceive().getOrNull()
            while (next != null) {
                batch.add(next)
                next = pending.tryReceive().getOrNull()
            }
            for (message in summarizeImportOutcomes(batch)) {
                showLibraryMessage(
                    host = snackbarHostState,
                    context = context,
                    message = message,
                    undoable = false,
                    onUndo = {},
                    onOpenDocument = { currentOpenDocument(it) },
                    onRestoreDocument = viewModel::restoreDocumentFromTrash,
                )
            }
        }
    }

    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = !state.selectionMode && query.isNotEmpty()) { onQueryChange("") }

    // Keyboard: Ctrl+A selects all; in selection mode Delete moves to trash and Esc clears.
    val rootFocus = remember { FocusRequester() }
    var rootHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectionMode) {
        if (!rootHasFocus) {
            try {
                rootFocus.requestFocus()
            } catch (e: IllegalStateException) {
                // Not attached yet; keyboard shortcuts start working once something is focused.
            }
        }
    }
    val onKey: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown || searchFocused) {
            false
        } else {
            val selecting = viewModel.inSelectionMode
            when {
                event.isCtrlPressed && event.key == Key.A -> {
                    viewModel.selectAll()
                    true
                }
                selecting && (event.key == Key.Delete || event.key == Key.Backspace) -> {
                    viewModel.trashSelection()
                    true
                }
                selecting && event.key == Key.Escape -> {
                    viewModel.clearSelection()
                    true
                }
                else -> false
            }
        }
    }

    val topBarInsets = if (large) {
        TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.End)
    } else {
        TopAppBarDefaults.windowInsets
    }
    val contentInsets = if (large) {
        ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
    } else {
        ScaffoldDefaults.contentWindowInsets
    }
    val createButton: @Composable () -> Unit = {
        CreateButton(
            onImport = launchImport,
            onNewFolder = viewModel::requestNewFolder,
            onNewNote = viewModel::createNote,
        )
    }

    val paneTitle = state.folderName ?: stringResource(R.string.library_title)
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { this.paneTitle = paneTitle }
            .onGloballyPositioned { dragDrop.rootCoordinates = it }
            .onPreviewKeyEvent(onKey)
            .onFocusChanged { rootHasFocus = it.hasFocus }
            .focusRequester(rootFocus)
            // focusTarget (not focusable): receives key events without adding a screen-sized,
            // unlabeled node to the accessibility tree.
            .focusTarget(),
    ) {
        Row(Modifier.fillMaxSize()) {
            if (large) {
                LibraryRail(
                    filter = state.filter,
                    onFilter = viewModel::setFilter,
                    onOpenTrash = onOpenTrash,
                    onOpenSettings = onOpenSettings,
                    header = { if (!state.selectionMode) createButton() },
                )
            }
            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                topBar = {
                    LibraryTopBar(
                        state = state,
                        large = large,
                        query = query,
                        onQueryChange = onQueryChange,
                        onSearchFocusChanged = { searchFocused = it },
                        topBarInsets = topBarInsets,
                        dragDrop = dragDrop,
                        onNavigateUp = onNavigateUp,
                        onOpenRoot = onOpenRoot,
                        onOpenFolder = onOpenFolder,
                        onOpenTrash = onOpenTrash,
                        onOpenSettings = onOpenSettings,
                        viewModel = viewModel,
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                floatingActionButton = { if (!large && !state.selectionMode) createButton() },
                contentWindowInsets = contentInsets,
            ) { innerPadding ->
                val layoutDirection = LocalLayoutDirection.current
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = innerPadding.calculateStartPadding(layoutDirection),
                            end = innerPadding.calculateEndPadding(layoutDirection),
                            top = innerPadding.calculateTopPadding(),
                        ),
                ) {
                    if (showFolderPanel) {
                        // docs/ARCHITECTURE.md §9: expanded screens keep the folder tree next to the grid.
                        FolderPanel(
                            rows = viewModel.folderPanel,
                            currentFolderId = state.folderId,
                            dragDrop = dragDrop,
                            onOpenRoot = onOpenRoot,
                            onOpenFolder = onOpenFolder,
                            bottomPadding = innerPadding.calculateBottomPadding(),
                            modifier = Modifier
                                .width(FolderPanelWidth)
                                .fillMaxHeight(),
                        )
                        VerticalDivider()
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        ImportBanner(progress = viewModel.importProgress, onCancel = viewModel::cancelImport)
                        if (!state.loading) {
                            LibraryGrid(
                                state = state,
                                large = large,
                                bottomPadding = innerPadding.calculateBottomPadding(),
                                gridState = gridState,
                                covers = covers,
                                dragDrop = dragDrop,
                                callbacks = callbacks,
                                onImport = launchImport,
                                onNewFolder = viewModel::requestNewFolder,
                                onNewNote = viewModel::createNote,
                                onClearFilter = { viewModel.setFilter(LibraryFilter.ALL) },
                            )
                        }
                    }
                }
            }
        }
        FavoritesDropZone(
            dragDrop = dragDrop,
            canDrop = { viewModel.canDrop(DropTarget.Favorites) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        DragPreview(dragDrop)
    }

    LibraryDialogHost(
        dialog = state.dialog,
        onCreateFolder = viewModel::createFolder,
        onRename = viewModel::rename,
        onMove = viewModel::moveTo,
        onDismiss = viewModel::dismissDialog,
    )
}

/** Shares [files]; the FileProvider work runs off the main thread (see [shareDocuments]). */
private suspend fun shareOrReport(context: Context, host: SnackbarHostState, files: List<File>) {
    if (context.shareDocuments(files)) return
    showLibraryMessage(
        host = host,
        context = context,
        message = LibraryMessage.ShareFailed,
        undoable = false,
        onUndo = {},
        onOpenDocument = {},
    )
}

@Composable
private fun LibraryTopBar(
    state: LibraryUiState,
    large: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    topBarInsets: WindowInsets,
    dragDrop: DragDropState,
    onNavigateUp: (() -> Unit)?,
    onOpenRoot: (() -> Unit)?,
    onOpenFolder: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel,
) {
    val actions: @Composable RowScope.() -> Unit = {
        ViewOptionsButton(
            layout = state.layout,
            sort = state.sort,
            filter = state.filter,
            onLayout = viewModel::setLayout,
            onSortField = viewModel::setSortField,
            onSortAscending = viewModel::setSortAscending,
            onFilter = viewModel::setFilter,
        )
        if (!large) LibraryOverflowMenu(onOpenTrash = onOpenTrash, onOpenSettings = onOpenSettings)
    }
    when {
        state.selectionMode -> SelectionTopBar(
            count = state.selection.size,
            canFavorite = state.selectionCanFavorite,
            allFavorite = state.selectionAllFavorite,
            canShare = state.selectionHasDocuments,
            wide = large,
            windowInsets = topBarInsets,
            onClear = viewModel::clearSelection,
            onSelectAll = viewModel::selectAll,
            onMove = viewModel::requestMoveSelection,
            onFavorite = viewModel::toggleFavoriteSelection,
            onShare = viewModel::shareSelection,
            onDelete = viewModel::trashSelection,
        )
        state.isRoot -> RootSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onFocusChanged = onSearchFocusChanged,
            windowInsets = topBarInsets,
            actions = actions,
        )
        else -> FolderTopBar(
            title = state.folderName.orEmpty(),
            onNavigateUp = onNavigateUp,
            crumbs = state.breadcrumbs,
            dragDrop = dragDrop,
            rootCrumbEnabled = onOpenRoot != null,
            onCrumbClick = { crumb ->
                // The app pops back to an ancestor already in the stack instead of pushing a copy.
                val id = crumb.folderId
                if (id == null) onOpenRoot?.invoke() else onOpenFolder(id)
            },
            windowInsets = topBarInsets,
            actions = actions,
        )
    }
}

@Composable
private fun LibraryGrid(
    state: LibraryUiState,
    large: Boolean,
    bottomPadding: Dp,
    gridState: LazyGridState,
    covers: CoverRepository,
    dragDrop: DragDropState,
    callbacks: LibraryItemCallbacks,
    onImport: () -> Unit,
    onNewFolder: () -> Unit,
    onNewNote: () -> Unit,
    onClearFilter: () -> Unit,
) {
    val layout = state.layout
    val isGrid = layout == LibraryLayout.GRID
    val gridPadding = when {
        isGrid -> if (large) 24.dp else 16.dp
        large -> 16.dp
        else -> 0.dp
    }
    // Aligns SectionHeader's own 16 dp inset with the covers (cards have a 6 dp inner padding).
    val headerBleed = if (isGrid) 10.dp else 0.dp
    val columns = when (layout) {
        LibraryLayout.GRID -> GridCells.Adaptive(if (large) 176.dp else 148.dp)
        LibraryLayout.LIST -> if (large) GridCells.Adaptive(360.dp) else GridCells.Fixed(1)
        LibraryLayout.COMPACT -> if (large) GridCells.Adaptive(320.dp) else GridCells.Fixed(1)
    }
    val animate = LocalUiPreferences.current.animationsEnabled
    LazyVerticalGrid(
        columns = columns,
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(TEST_TAG_LIST),
        contentPadding = PaddingValues(
            start = gridPadding,
            end = gridPadding,
            top = 4.dp,
            bottom = bottomPadding + FabClearance,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (isGrid) 12.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (isGrid) 8.dp else 0.dp),
    ) {
        items(
            items = state.rows,
            key = { it.key },
            span = { row -> if (row.fullSpan) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { it.contentType },
        ) { row ->
            val itemModifier = if (animate) Modifier.animateItem() else Modifier
            when (row) {
                is LibraryRow.Header -> SectionHeader(
                    title = sectionTitle(row.section),
                    modifier = itemModifier.horizontalBleed(headerBleed),
                    trailing = {
                        Text(
                            text = row.count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    },
                )
                is LibraryRow.Shelf -> DocumentShelf(
                    shelf = row,
                    selection = state.selection,
                    selectionMode = state.selectionMode,
                    nowMillis = state.nowMillis,
                    covers = covers,
                    dragDrop = dragDrop,
                    callbacks = callbacks,
                    bleed = gridPadding,
                    modifier = itemModifier,
                )
                is LibraryRow.Cell -> LibraryItemView(
                    item = row.item,
                    ref = row.ref,
                    layout = layout,
                    selected = row.ref in state.selection,
                    selectionMode = state.selectionMode,
                    nowMillis = state.nowMillis,
                    covers = covers,
                    dragDrop = dragDrop,
                    callbacks = callbacks,
                    modifier = itemModifier,
                )
                is LibraryRow.ActiveFilter -> Box(itemModifier.padding(start = if (isGrid) 6.dp else 16.dp, top = 8.dp)) {
                    ActiveFilterChip(filter = row.filter, onClear = onClearFilter)
                }
                is LibraryRow.Empty -> LibraryEmptyState(
                    row = row,
                    onImport = onImport,
                    onNewFolder = onNewFolder,
                    onNewNote = onNewNote,
                    onClearFilter = onClearFilter,
                    modifier = itemModifier,
                )
            }
        }
    }
}
