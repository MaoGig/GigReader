package com.maogig.gigreader.feature.library

import com.maogig.gigreader.core.data.importer.ImportError
import com.maogig.gigreader.core.data.importer.ImportOutcome
import com.maogig.gigreader.core.data.library.FolderContents
import com.maogig.gigreader.core.data.library.ItemKind
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.data.library.LibrarySearchResults
import com.maogig.gigreader.core.model.LibraryFilter
import com.maogig.gigreader.core.model.SortField
import com.maogig.gigreader.core.model.SortOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryContentTest {
    private val now = 500 * DAY

    private fun build(
        contents: FolderContents = FolderContents.Empty,
        shelves: RootShelves = RootShelves.Empty,
        search: SearchSnapshot = SearchSnapshot.Idle,
        filter: LibraryFilter = LibraryFilter.ALL,
        sort: SortOrder = SortOrder(SortField.NAME, ascending = true),
        isRoot: Boolean = true,
    ) = buildLibraryContent(isRoot, contents, shelves, search, filter, sort, now)

    @Test
    fun sortingKeepsFoldersFirstAndAppliesDirection() {
        val content = build(
            contents = FolderContents(
                folders = listOf(folder("f")),
                documents = listOf(doc("small", size = 10), doc("big", size = 1_000)),
                notes = emptyList(),
            ),
            sort = SortOrder(SortField.SIZE, ascending = false),
        )
        assertEquals(
            listOf(ItemRef("f", ItemKind.FOLDER), ItemRef("big", ItemKind.DOCUMENT), ItemRef("small", ItemKind.DOCUMENT)),
            content.selectable,
        )
    }

    @Test
    fun quickNotesMergeRootAndRecentNotesWithoutDuplicates() {
        val content = build(
            contents = FolderContents(emptyList(), emptyList(), listOf(note("n1", "B"))),
            shelves = RootShelves(recentNotes = listOf(note("n1", "B"), note("n2", "A", folderId = "x"))),
        )
        val keys = content.rows.map { it.key }
        assertEquals(listOf("header:QUICK_NOTES", "note:n2", "note:n1"), keys)
        assertEquals(keys.size, keys.toSet().size, "lazy grid keys must be unique")
    }

    @Test
    fun shelvesAreVisibleButNotPartOfSelectAll() {
        val reading = doc("r", lastOpenedAt = now)
        val content = build(shelves = RootShelves(continueReading = listOf(reading)))
        assertTrue(content.selectable.isEmpty())
        assertTrue(ItemRef("r", ItemKind.DOCUMENT) in content.visible)
        assertFalse(content.rows.any { it is LibraryRow.Empty }, "a library with only shelves is not empty")
    }

    @Test
    fun foldersOutsideTheRootNeverShowShelves() {
        val content = build(
            shelves = RootShelves(continueReading = listOf(doc("r"))),
            isRoot = false,
        )
        assertEquals(listOf<LibraryRow>(LibraryRow.Empty(EmptyKind.FOLDER)), content.rows)
    }

    @Test
    fun favoritesFilterIncludesFavoriteNotes() {
        val content = build(
            contents = FolderContents(
                listOf(folder("f")),
                listOf(doc("d", favorite = false)),
                listOf(note("n", favorite = true)),
            ),
            filter = LibraryFilter.FAVORITES,
        )
        assertEquals(listOf(ItemRef("n", ItemKind.NOTE)), content.selectable)
    }

    @Test
    fun searchResultsKeepRepositoryOrder() {
        val content = build(
            search = SearchSnapshot("q", LibrarySearchResults(emptyList(), listOf(doc("z"), doc("a")), emptyList())),
        )
        assertEquals(listOf("header:DOCUMENTS", "doc:z", "doc:a"), content.rows.map { it.key })
    }

    @Test
    fun importBurstsAreSummarized() {
        assertEquals(
            listOf<LibraryMessage>(LibraryMessage.Imported("Paper", "d1")),
            summarizeImportOutcomes(listOf(ImportOutcome.Imported("d1", "Paper"))),
        )
        val burst = listOf(
            ImportOutcome.Imported("d1", "A"),
            ImportOutcome.Imported("d2", "B"),
            ImportOutcome.Duplicate("d0", "C.pdf", inTrash = false),
            ImportOutcome.Failed("D.pdf", ImportError.PASSWORD_PROTECTED),
            ImportOutcome.Failed("E.pdf", ImportError.CANCELLED),
        )
        assertEquals(
            listOf(
                LibraryMessage.ImportedMany(2),
                LibraryMessage.AlreadyInLibrary("C.pdf", "d0", inTrash = false),
                LibraryMessage.ImportFailed("D.pdf", ImportError.PASSWORD_PROTECTED),
            ),
            summarizeImportOutcomes(burst),
        )
        assertEquals(
            listOf<LibraryMessage>(LibraryMessage.ImportFailedMany(2)),
            summarizeImportOutcomes(
                listOf(
                    ImportOutcome.Failed("a", ImportError.NOT_A_PDF),
                    ImportOutcome.Failed("b", ImportError.CORRUPTED),
                ),
            ),
        )
        assertTrue(summarizeImportOutcomes(listOf(ImportOutcome.Failed("x", ImportError.CANCELLED))).isEmpty())
    }

    @Test
    fun openActionOnlyForDocumentsOutsideTheTrash() {
        assertEquals("d1", LibraryMessage.Imported("A", "d1").openableDocumentId())
        assertEquals("d0", LibraryMessage.AlreadyInLibrary("A", "d0", inTrash = false).openableDocumentId())
        assertEquals(null, LibraryMessage.AlreadyInLibrary("A", "d0", inTrash = true).openableDocumentId())
        assertEquals(null, LibraryMessage.Moved(2).openableDocumentId())
    }

    @Test
    fun restoreActionOnlyForDuplicatesInTheTrash() {
        assertEquals("d0", LibraryMessage.AlreadyInLibrary("A", "d0", inTrash = true).restorableDocumentId())
        assertEquals(null, LibraryMessage.AlreadyInLibrary("A", "d0", inTrash = false).restorableDocumentId())
        assertEquals(null, LibraryMessage.Imported("A", "d1").restorableDocumentId())
    }

    @Test
    fun filterViewsListDocumentsThenNotesOfTheGivenPool() {
        val content = build(
            contents = FolderContents(
                emptyList(),
                listOf(doc("b", favorite = true), doc("a", favorite = true), doc("x")),
                listOf(note("n", favorite = true)),
            ),
            filter = LibraryFilter.FAVORITES,
        )
        assertEquals(
            listOf("filter", "header:DOCUMENTS", "doc:a", "doc:b", "header:NOTES", "note:n"),
            content.rows.map { it.key },
        )
    }

    @Test
    fun menuActionsDependOnKindAndFavorite() {
        assertEquals(
            listOf(ItemAction.OPEN, ItemAction.RENAME, ItemAction.MOVE, ItemAction.DELETE),
            folder("f").menuActions(),
        )
        assertTrue(ItemAction.UNFAVORITE in doc("d", favorite = true).menuActions())
        assertTrue(ItemAction.FAVORITE in note("n").menuActions())
        assertFalse(ItemAction.SHARE in note("n").menuActions())
    }
}
