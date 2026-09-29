package com.maogig.gigreader.feature.library

import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.TreeNode
import com.maogig.gigreader.core.data.library.FolderContents
import com.maogig.gigreader.core.data.library.ItemKind
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.data.library.LibrarySearchResults
import com.maogig.gigreader.core.data.library.MoveResult
import com.maogig.gigreader.core.data.library.TrashEntry
import com.maogig.gigreader.core.model.Document
import com.maogig.gigreader.core.model.DocumentSource
import com.maogig.gigreader.core.model.DocumentType
import com.maogig.gigreader.core.model.LibraryFilter
import com.maogig.gigreader.core.model.LibraryLayout
import com.maogig.gigreader.core.model.SortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val library = FakeLibraryRepository()
    private val notes = FakeNotesRepository()
    private val settings = FakeSettingsRepository()
    private val importer = FakeImportGateway()
    private val now = 1_000 * DAY
    private val libraryRoot = File("library-root")

    /** Per test instead of the process-wide flag, so tests never depend on each other. */
    private val trashPurgeStarted = AtomicBoolean(false)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(folderId: String? = null): LibraryViewModel {
        val vm = LibraryViewModel(
            folderId = folderId,
            library = library,
            notes = notes,
            settings = settings,
            importer = importer,
            resolveFile = { File(libraryRoot, it) },
            clock = Clock { now },
            computeDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            trashPurgeStarted = trashPurgeStarted,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun TestScope.events(vm: LibraryViewModel): List<LibraryEvent> {
        val events = mutableListOf<LibraryEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        return events
    }

    private fun LibraryUiState.cellKeys(): List<String> = rows.filterIsInstance<LibraryRow.Cell>().map { it.key }

    @Test
    fun homeShowsShelvesThenSortedSections() = runTest(dispatcher) {
        library.continueReading.value = listOf(doc("r1", lastOpenedAt = now - DAY, folderId = "a"))
        library.favorites.value = listOf(doc("f1", favorite = true, folderId = "a"))
        library.recentNotes.value = listOf(note("n2", folderId = "a"))
        library.setContents(
            null,
            FolderContents(
                folders = listOf(folder("b", "Beta"), folder("a", "Alpha")),
                documents = listOf(doc("d2", "Zeta"), doc("d1", "Eta")),
                notes = listOf(note("n1", "Root note")),
            ),
        )
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.isRoot)
        val shelves = state.rows.filterIsInstance<LibraryRow.Shelf>().map { it.section }
        assertEquals(listOf(LibrarySection.CONTINUE_READING, LibrarySection.FAVORITES), shelves)
        val headers = state.rows.filterIsInstance<LibraryRow.Header>().map { it.section to it.count }
        assertEquals(
            listOf(LibrarySection.FOLDERS to 2, LibrarySection.DOCUMENTS to 2, LibrarySection.QUICK_NOTES to 2),
            headers,
        )
        // Folders first, each section sorted by name (settings default in the fake).
        assertEquals(listOf("folder:a", "folder:b", "doc:d1", "doc:d2", "note:n2", "note:n1"), state.cellKeys())
    }

    @Test
    fun emptyLibraryShowsEmptyState() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val rows = vm.uiState.value.rows
        assertEquals(1, rows.size)
        assertEquals(EmptyKind.LIBRARY, assertIs<LibraryRow.Empty>(rows.single()).kind)
    }

    @Test
    fun folderScreenHasBreadcrumbsAndNoShelves() = runTest(dispatcher) {
        library.setTree(TreeNode("a", null, "Alpha"), TreeNode("b", "a", "Beta"))
        library.continueReading.value = listOf(doc("r1", lastOpenedAt = now))
        val vm = viewModel(folderId = "b")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Beta", state.folderName)
        assertEquals(listOf(Crumb(null, null), Crumb("a", "Alpha"), Crumb("b", "Beta")), state.breadcrumbs)
        assertTrue(state.rows.none { it is LibraryRow.Shelf })
        assertEquals(EmptyKind.FOLDER, assertIs<LibraryRow.Empty>(state.rows.single()).kind)
    }

    @Test
    fun pdfFilterOnTheHomeListsEveryDocumentOfTheLibrary() = runTest(dispatcher) {
        library.setContents(
            null,
            FolderContents(listOf(folder("a")), listOf(doc("d1")), listOf(note("n1"))),
        )
        library.allDocuments.value = listOf(doc("d2", folderId = "a"), doc("d1"))
        val vm = viewModel()
        vm.setFilter(LibraryFilter.PDFS)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(LibraryFilter.PDFS, state.filter)
        assertIs<LibraryRow.ActiveFilter>(state.rows.first())
        // Folders and notes hidden; the document stored in folder "a" is found from the Home.
        assertEquals(listOf("doc:d1", "doc:d2"), state.cellKeys())
    }

    @Test
    fun homeObservesOnlyTheQueriesOfTheActiveView() = runTest(dispatcher) {
        library.allNotes.value = listOf(note("n1", folderId = "a"), note("n2", folderId = "b"))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(1, library.contentsOf(null).subscriptionCount.value)
        assertEquals(1, library.continueReading.subscriptionCount.value)
        assertEquals(0, library.allNotes.subscriptionCount.value)
        assertEquals(0, library.allDocuments.subscriptionCount.value)

        vm.setFilter(LibraryFilter.NOTES)
        advanceUntilIdle()
        assertEquals(listOf(LibrarySection.NOTES), vm.uiState.value.rows.filterIsInstance<LibraryRow.Header>().map { it.section })
        assertEquals(listOf("note:n1", "note:n2"), vm.uiState.value.cellKeys())
        assertEquals(1, library.allNotes.subscriptionCount.value)
        assertEquals(0, library.contentsOf(null).subscriptionCount.value, "the root listing is not needed meanwhile")
        assertEquals(0, library.continueReading.subscriptionCount.value)
        assertEquals(0, library.recentNotes.subscriptionCount.value)

        vm.setFilter(LibraryFilter.ALL)
        advanceUntilIdle()
        assertEquals(0, library.allNotes.subscriptionCount.value)
        assertEquals(1, library.contentsOf(null).subscriptionCount.value)
    }

    @Test
    fun recentFilterObservesDocumentsOpenedInTheLastThirtyDays() = runTest(dispatcher) {
        library.allDocuments.value = listOf(
            doc("old", folderId = "a", lastOpenedAt = now - 40 * DAY),
            doc("recent", folderId = "a", lastOpenedAt = now - 10 * DAY),
            doc("never"),
        )
        val vm = viewModel()
        vm.setFilter(LibraryFilter.RECENT)
        advanceUntilIdle()

        assertEquals(listOf(now - RECENT_WINDOW_MILLIS), library.openedSinceCalls)
        assertEquals(listOf("doc:recent"), vm.uiState.value.cellKeys())
    }

    @Test
    fun annotatedFilterCoversEveryFolder() = runTest(dispatcher) {
        library.allDocuments.value = listOf(doc("plain"), doc("marked", folderId = "a", annotations = 3))
        val vm = viewModel()
        vm.setFilter(LibraryFilter.ANNOTATED)
        advanceUntilIdle()
        assertEquals(listOf("doc:marked"), vm.uiState.value.cellKeys())

        library.allDocuments.value = listOf(doc("plain"))
        advanceUntilIdle()
        assertEquals(EmptyKind.FILTER, vm.uiState.value.rows.filterIsInstance<LibraryRow.Empty>().single().kind)
    }

    @Test
    fun favoritesViewCombinesFavoriteDocumentsAndNotesOfEveryFolder() = runTest(dispatcher) {
        library.favorites.value = listOf(doc("d1", folderId = "a", favorite = true))
        library.favoriteNotes.value = listOf(note("n1", folderId = "b", favorite = true))
        val vm = viewModel()
        advanceUntilIdle()
        library.favoriteLimits.clear()

        vm.setFilter(LibraryFilter.FAVORITES)
        advanceUntilIdle()
        assertEquals(listOf("doc:d1", "note:n1"), vm.uiState.value.cellKeys())
        assertEquals(listOf(Int.MAX_VALUE), library.favoriteLimits, "the view is not capped like the shelf")
    }

    @Test
    fun filtersInsideAFolderOnlyUseItsContents() = runTest(dispatcher) {
        library.setContents("a", FolderContents(listOf(folder("sub")), listOf(doc("d1", folderId = "a")), listOf(note("n1"))))
        library.allDocuments.value = listOf(doc("elsewhere", folderId = "b"))
        val vm = viewModel(folderId = "a")
        vm.setFilter(LibraryFilter.PDFS)
        advanceUntilIdle()

        assertEquals(listOf("doc:d1"), vm.uiState.value.cellKeys())
        assertEquals(0, library.allDocuments.subscriptionCount.value)
    }

    @Test
    fun folderPanelListsEveryFolderDepthFirst() = runTest(dispatcher) {
        library.setTree(TreeNode("c", null, "Gamma"), TreeNode("b", "a", "Beta"), TreeNode("a", null, "Alpha"))
        val vm = viewModel(folderId = "b")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.folderPanel.collect {} }
        advanceUntilIdle()

        assertEquals(
            listOf(FolderPanelRow("a", "Alpha", 0), FolderPanelRow("b", "Beta", 1), FolderPanelRow("c", "Gamma", 0)),
            vm.folderPanel.value,
        )
    }

    @Test
    fun homePurgesExpiredTrashOncePerProcess() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()
        assertEquals(1, library.purged)

        viewModel()
        viewModel(folderId = "a")
        advanceUntilIdle()
        assertEquals(1, library.purged, "later Home screens and folders do not purge again")
    }

    @Test
    fun failedPurgeIsRetriedByTheNextHome() = runTest(dispatcher) {
        library.purgeFailure = IOException("disk")
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        assertEquals(0, library.purged)
        assertTrue(events.isEmpty(), "a failed purge is silent")

        library.purgeFailure = null
        viewModel()
        advanceUntilIdle()
        assertEquals(1, library.purged)
    }

    @Test
    fun duplicateUsesTheLocalizedCopyTitle() = runTest(dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()

        vm.duplicate("d1", copyTitle = "Paper (cópia)")
        advanceUntilIdle()

        assertEquals(listOf("d1" to "Paper (cópia)"), library.duplicated)
        assertEquals(LibraryEvent.Message(LibraryMessage.Duplicated, undoable = true), events.single())
        assertTrue(vm.undoManager.state.value.canUndo)
    }

    @Test
    fun duplicateImportInTheTrashCanBeRestored() = runTest(dispatcher) {
        library.documents["d1"] = document("d1", DocumentSource.Managed("d1.pdf"), trashedAt = 5 * DAY)
        library.trash.value = listOf(TrashEntry(ItemRef("d1", ItemKind.DOCUMENT), "d1", trashedAt = 5 * DAY))
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()

        vm.restoreDocumentFromTrash("d1")
        advanceUntilIdle()

        assertEquals(listOf(listOf(ItemRef("d1", ItemKind.DOCUMENT))), library.restored)
        assertEquals(LibraryEvent.Message(LibraryMessage.Restored("d1")), events.single())
    }

    @Test
    fun documentTrashedWithItsFolderIsNotRestoredAlone() = runTest(dispatcher) {
        library.documents["d1"] = document("d1", DocumentSource.Managed("d1.pdf"), trashedAt = 5 * DAY)
        // Only its folder is a trash root.
        library.trash.value = listOf(TrashEntry(ItemRef("f", ItemKind.FOLDER), "Papers", trashedAt = 5 * DAY))
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()

        vm.restoreDocumentFromTrash("d1")
        advanceUntilIdle()

        assertTrue(library.restored.isEmpty())
        assertEquals(LibraryEvent.Message(LibraryMessage.RestoreWithFolder), events.single())
    }

    @Test
    fun searchIsDebouncedAndGrouped() = runTest(dispatcher) {
        library.setContents(null, FolderContents(emptyList(), listOf(doc("d1")), emptyList()))
        library.searchResults = LibrarySearchResults(listOf(folder("f")), listOf(doc("d9")), listOf(note("n9")))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onQueryChange("a")
        advanceTimeBy(100)
        vm.onQueryChange("ab")
        advanceTimeBy(200)
        runCurrent()
        assertTrue(library.searches.isEmpty(), "no search before the debounce window")

        advanceTimeBy(100)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("ab"), library.searches)
        val state = vm.uiState.value
        assertTrue(state.searchActive)
        assertEquals(
            listOf(LibrarySection.FOLDERS, LibrarySection.DOCUMENTS, LibrarySection.NOTES),
            state.rows.filterIsInstance<LibraryRow.Header>().map { it.section },
        )
        assertEquals(listOf("folder:f", "doc:d9", "note:n9"), state.cellKeys())

        vm.onQueryChange("")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.searchActive)
        assertEquals(listOf("doc:d1"), vm.uiState.value.cellKeys())
    }

    @Test
    fun searchWithoutResultsShowsEmptyState() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("zzz")
        advanceUntilIdle()

        val empty = vm.uiState.value.rows.filterIsInstance<LibraryRow.Empty>().single()
        assertEquals(EmptyKind.SEARCH, empty.kind)
        assertEquals("zzz", empty.query)
    }

    @Test
    fun selectionLongPressToggleSelectAllAndClear() = runTest(dispatcher) {
        library.setContents(null, FolderContents(listOf(folder("a")), listOf(doc("d1"), doc("d2")), emptyList()))
        val vm = viewModel()
        advanceUntilIdle()
        val d1 = ItemRef("d1", ItemKind.DOCUMENT)
        val d2 = ItemRef("d2", ItemKind.DOCUMENT)

        vm.onLongPress(d1)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.selectionMode)
        assertEquals(setOf(d1), vm.uiState.value.selection)
        assertTrue(vm.uiState.value.selectionHasDocuments)

        vm.toggleSelection(d2)
        vm.toggleSelection(d1)
        advanceUntilIdle()
        assertEquals(setOf(d2), vm.uiState.value.selection)

        vm.selectAll()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.selection.size)

        vm.clearSelection()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.selectionMode)
    }

    @Test
    fun selectionDropsItemsThatDisappear() = runTest(dispatcher) {
        library.setContents(null, FolderContents(emptyList(), listOf(doc("d1"), doc("d2")), emptyList()))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLongPress(ItemRef("d1", ItemKind.DOCUMENT))
        advanceUntilIdle()

        library.setContents(null, FolderContents(emptyList(), listOf(doc("d2")), emptyList()))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.selectionMode)
        // Taps open items again instead of toggling a selection the user cannot see.
        assertFalse(vm.inSelectionMode)
    }

    @Test
    fun hiddenSelectionNeverTakesPartInBulkActions() = runTest(dispatcher) {
        library.setContents(null, FolderContents(emptyList(), listOf(doc("d1")), listOf(note("n1"))))
        library.allNotes.value = listOf(note("n1"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLongPress(ItemRef("d1", ItemKind.DOCUMENT))
        advanceUntilIdle()

        vm.setFilter(LibraryFilter.NOTES)
        advanceUntilIdle()
        assertFalse(vm.inSelectionMode)

        vm.onLongPress(ItemRef("n1", ItemKind.NOTE))
        vm.trashSelection()
        advanceUntilIdle()
        assertEquals(listOf(listOf(ItemRef("n1", ItemKind.NOTE))), library.trashed)
    }

    @Test
    fun trashingRecordsUndoAndUndoRevertsIt() = runTest(dispatcher) {
        library.setContents(null, FolderContents(emptyList(), listOf(doc("d1")), emptyList()))
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        val d1 = ItemRef("d1", ItemKind.DOCUMENT)

        vm.onLongPress(d1)
        vm.trashSelection()
        advanceUntilIdle()

        assertEquals(listOf(listOf(d1)), library.trashed)
        assertEquals(LibraryEvent.Message(LibraryMessage.Trashed(1), undoable = true), events.last())
        assertFalse(vm.uiState.value.selectionMode)
        assertTrue(vm.undoManager.state.value.canUndo)

        vm.undo()
        advanceUntilIdle()
        assertEquals(listOf("trash"), library.undone)
        assertFalse(vm.undoManager.state.value.canUndo)
    }

    @Test
    fun moveIntoOwnSubfolderReportsCycle() = runTest(dispatcher) {
        library.nextMoveResult = MoveResult.WouldCreateCycle
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()

        vm.moveTo(listOf(ItemRef("a", ItemKind.FOLDER)), "b")
        advanceUntilIdle()

        assertEquals(LibraryEvent.Message(LibraryMessage.MoveCycle), events.single())
        assertFalse(vm.undoManager.state.value.canUndo)
    }

    @Test
    fun moveDialogDisablesCycleTargets() = runTest(dispatcher) {
        library.setTree(TreeNode("a", null, "A"), TreeNode("b", "a", "B"), TreeNode("c", null, "C"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.requestMove(listOf(ItemRef("a", ItemKind.FOLDER)))
        advanceUntilIdle()

        val dialog = assertIs<LibraryDialog.Move>(vm.uiState.value.dialog)
        val enabled = dialog.targets.associate { it.folderId to it.enabled }
        assertEquals(mapOf(null to true, "a" to false, "b" to false, "c" to true), enabled)
        assertEquals(listOf(0, 1, 2, 1), dialog.targets.map { it.depth })
    }

    @Test
    fun createNoteOpensItImmediately() = runTest(dispatcher) {
        val vm = viewModel(folderId = "a")
        val events = events(vm)
        advanceUntilIdle()

        vm.createNote()
        advanceUntilIdle()

        assertEquals(listOf<String?>("a"), notes.created)
        assertEquals(LibraryEvent.OpenNote("note-1"), events.single())
    }

    @Test
    fun createFolderUsesCurrentFolderAndClosesDialog() = runTest(dispatcher) {
        val vm = viewModel(folderId = "a")
        advanceUntilIdle()

        vm.requestNewFolder()
        advanceUntilIdle()
        assertEquals(LibraryDialog.NewFolder, vm.uiState.value.dialog)

        vm.createFolder("Papers")
        advanceUntilIdle()
        assertEquals(listOf<Pair<String?, String>>("a" to "Papers"), library.createdFolders)
        assertEquals(null, vm.uiState.value.dialog)
    }

    @Test
    fun layoutAndSortArePersistedInSettings() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setLayout(LibraryLayout.COMPACT)
        vm.setSortField(SortField.SIZE)
        vm.setSortAscending(false)
        advanceUntilIdle()

        assertEquals(LibraryLayout.COMPACT, settings.current.libraryLayout)
        assertEquals(SortField.SIZE, settings.current.librarySort.field)
        assertFalse(settings.current.librarySort.ascending)
        assertEquals(LibraryLayout.COMPACT, vm.uiState.value.layout)
        assertEquals(SortField.SIZE, vm.uiState.value.sort.field)
    }

    @Test
    fun shareResolvesManagedDocumentsOnly() = runTest(dispatcher) {
        library.documents["d1"] = document("d1", DocumentSource.Managed("d1.pdf"))
        library.documents["d2"] = document("d2", DocumentSource.Linked("content://x/d2"))
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()

        vm.share(
            listOf(
                ItemRef("d1", ItemKind.DOCUMENT),
                ItemRef("d2", ItemKind.DOCUMENT),
                ItemRef("a", ItemKind.FOLDER),
            ),
        )
        advanceUntilIdle()
        assertEquals(LibraryEvent.Share(listOf(File(libraryRoot, "d1.pdf"))), events.last())

        vm.share(listOf(ItemRef("a", ItemKind.FOLDER)))
        advanceUntilIdle()
        assertEquals(LibraryEvent.Message(LibraryMessage.ShareNothing), events.last())
    }

    @Test
    fun dragAndDropRespectsFolderTreeAndFavorites() = runTest(dispatcher) {
        library.setTree(TreeNode("a", null, "A"), TreeNode("b", "a", "B"), TreeNode("c", null, "C"))
        library.setContents(null, FolderContents(listOf(folder("a"), folder("c")), listOf(doc("d1")), emptyList()))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(1, vm.beginDrag(ItemRef("a", ItemKind.FOLDER)))
        assertFalse(vm.canDrop(DropTarget.Folder("a")), "a folder cannot be dropped on itself")
        assertFalse(vm.canDrop(DropTarget.Folder("b")), "nor into its own subfolder")
        assertTrue(vm.canDrop(DropTarget.Folder("c")))
        assertFalse(vm.canDrop(DropTarget.Favorites), "folders cannot become favorites")
        assertFalse(vm.canDrop(DropTarget.Root), "already at the root")

        assertEquals(2, vm.beginDrag(ItemRef("d1", ItemKind.DOCUMENT)))
        vm.drop(DropTarget.Folder("c"))
        advanceUntilIdle()
        assertEquals("c", library.moves.single().second)
        assertEquals(2, library.moves.single().first.size)
        assertFalse(vm.uiState.value.selectionMode)

        vm.beginDrag(ItemRef("d1", ItemKind.DOCUMENT))
        vm.drop(DropTarget.Favorites)
        advanceUntilIdle()
        assertEquals(listOf(ItemRef("d1", ItemKind.DOCUMENT)) to true, library.favoriteCalls.single())
    }

    @Test
    fun favoriteToggleOfSelectionUsesCurrentFlags() = runTest(dispatcher) {
        library.setContents(
            null,
            FolderContents(emptyList(), listOf(doc("d1", favorite = true)), listOf(note("n1", favorite = true))),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.onLongPress(ItemRef("d1", ItemKind.DOCUMENT))
        vm.onLongPress(ItemRef("n1", ItemKind.NOTE))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.selectionAllFavorite)

        vm.toggleFavoriteSelection()
        advanceUntilIdle()
        assertFalse(library.favoriteCalls.single().second, "everything was a favorite, so it is removed")
    }

    @Test
    fun emptyImportIsIgnoredAndCancelIsForwarded() = runTest(dispatcher) {
        val vm = viewModel(folderId = "a")
        advanceUntilIdle()

        vm.importDocuments(emptyList())
        assertTrue(importer.requests.isEmpty())
        vm.cancelImport()
        assertEquals(1, importer.cancelled)
    }

    private fun document(id: String, source: DocumentSource, trashedAt: Long? = null) = Document(
        id = id,
        folderId = null,
        title = id,
        fileName = "$id.pdf",
        type = DocumentType.PDF,
        source = source,
        contentHash = "hash-$id",
        fileSize = 10,
        pageCount = 1,
        createdAt = 0,
        modifiedAt = 0,
        trashedAt = trashedAt,
    )
}
