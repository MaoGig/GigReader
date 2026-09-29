package com.maogig.gigreader.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maogig.gigreader.core.database.entity.DocumentTagEntity
import com.maogig.gigreader.core.database.entity.ReadingPositionEntity
import com.maogig.gigreader.core.database.entity.TagEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Room DAO queries against a real (in-memory) SQLite database. */
@RunWith(AndroidJUnit4::class)
class LibraryDaoTest {
    private lateinit var db: GigReaderDatabase

    @Before
    fun setUp() {
        db = GigReaderDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun subtreeIsRecursiveAndSkipsTrashedBranches() = runTest {
        val folders = db.folderDao()
        folders.insert(folderEntity("a"))
        folders.insert(folderEntity("b", parentId = "a"))
        folders.insert(folderEntity("c", parentId = "b"))
        folders.insert(folderEntity("d", parentId = "a", trashedAt = 5, trashRootId = "d"))
        folders.insert(folderEntity("e", parentId = "d")) // below a trashed folder: not reachable
        folders.insert(folderEntity("x"))

        assertEquals(setOf("a", "b", "c"), folders.subtreeIds("a").toSet())
        assertEquals(setOf("b", "c"), folders.subtreeIds("b").toSet())
        assertEquals(listOf("x"), folders.subtreeIds("x"))
    }

    @Test
    fun trashAndRestoreFollowTheTrashRoot() = runTest {
        val folders = db.folderDao()
        val documents = db.documentDao()
        val notes = db.noteDao()
        folders.insert(folderEntity("a"))
        folders.insert(folderEntity("b", parentId = "a"))
        documents.insert(documentEntity("d1", folderId = "a"))
        documents.insert(documentEntity("d2", folderId = "b"))
        documents.insert(documentEntity("root-doc"))
        notes.insert(noteEntity("n1", folderId = "b", title = "note"))

        // What LocalLibraryRepository does when trashing folder "a".
        val subtree = folders.subtreeIds("a")
        folders.trash(subtree, rootId = "a", now = 10)
        documents.trash(documents.liveIdsInFolders(subtree), rootId = "a", now = 10)
        notes.trash(notes.liveIdsInFolders(subtree), rootId = "a", now = 10)

        assertTrue(folders.observeChildren(null).first().isEmpty())
        assertEquals(listOf("root-doc"), documents.observeInFolder(null).first().map { it.id })
        assertEquals(setOf("a", "b"), folders.idsTrashedWith("a").toSet())
        assertEquals(setOf("d1", "d2"), documents.idsTrashedWith("a").toSet())
        assertEquals(listOf("n1"), notes.idsTrashedWith("a"))
        // Only the root is listed in the trash; its descendants follow it.
        assertEquals(listOf("a"), folders.observeTrashRoots().first().map { it.id })
        assertTrue(documents.observeTrashRoots().first().isEmpty())
        assertTrue(notes.observeTrashRoots().first().isEmpty())
        assertFalse(folders.isLive("a"))
        assertFalse(folders.isLive("b"))

        folders.restore("a", now = 20)
        documents.restore("a", now = 20)
        notes.restore("a", now = 20)

        assertTrue(folders.isLive("a"))
        assertEquals(listOf("b"), folders.observeChildren("a").first().map { it.id })
        assertEquals(listOf("d2"), documents.observeInFolder("b").first().map { it.id })
        assertEquals(listOf("n1"), notes.observeInFolder("b").first().map { it.id })
        assertNull(documents.getById("d2")!!.trashRootId)
        assertTrue(documents.idsTrashedWith("a").isEmpty())
    }

    @Test
    fun liveChildrenOfAFolderAreFoundForReattachment() = runTest {
        val folders = db.folderDao()
        folders.insert(folderEntity("gone", trashedAt = 5, trashRootId = "gone"))
        folders.insert(folderEntity("live-child", parentId = "gone"))
        folders.insert(folderEntity("trashed-child", parentId = "gone", trashedAt = 6, trashRootId = "trashed-child"))
        folders.insert(folderEntity("other"))

        assertEquals(listOf("live-child"), folders.liveIdsWithParents(listOf("gone", "other")))
        assertFalse(folders.isLive("gone"))
        assertFalse(folders.isLive("missing"))
        assertTrue(folders.isLive("other"))

        folders.tombstone(listOf("gone"), now = 7)
        val tombstone = folders.getById("gone")!!
        assertNotNull(tombstone.deletedAt)
        assertEquals("", tombstone.name)
        assertEquals("", tombstone.searchName)
    }

    @Test
    fun searchMatchesNormalizedColumns() = runTest {
        val folders = db.folderDao()
        val documents = db.documentDao()
        val notes = db.noteDao()
        folders.insert(folderEntity("f1", name = "Relatórios 2024"))
        folders.insert(folderEntity("f2", name = "50% off"))
        folders.insert(folderEntity("f3", name = "500 items"))
        folders.insert(folderEntity("f4", name = "a_b"))
        folders.insert(folderEntity("f5", name = "axb"))
        documents.insert(documentEntity("d1", title = "DIFUSÃO Anômala"))
        documents.insert(documentEntity("d2", title = "Outro"))
        notes.insert(noteEntity("n1", title = "Compras", body = "Café com leite"))
        notes.insert(noteEntity("n2", title = "abc", body = "def"))

        for (query in listOf("relatorio", "RELATÓRIO", "Relatórios", "  relatorios   2024 ")) {
            assertEquals(listOf("f1"), folders.searchByName(SearchKeys.likeQuery(query), 10).map { it.id }, query)
        }
        // LIKE wildcards in the query are literal.
        assertEquals(listOf("f2"), folders.searchByName(SearchKeys.likeQuery("50%"), 10).map { it.id })
        assertEquals(listOf("f4"), folders.searchByName(SearchKeys.likeQuery("a_b"), 10).map { it.id })

        assertEquals(listOf("d1"), documents.searchByTitle(SearchKeys.likeQuery("difusao anomala"), 10).map { it.id })
        assertEquals(listOf("d1"), documents.searchByTitle(SearchKeys.likeQuery("ANÔM"), 10).map { it.id })

        // Title and body are both searched, but a match never spans the two.
        assertEquals(listOf("n1"), notes.search(SearchKeys.likeQuery("CAFE"), 10).map { it.id })
        assertEquals(listOf("n1"), notes.search(SearchKeys.likeQuery("compras"), 10).map { it.id })
        assertTrue(notes.search(SearchKeys.likeQuery("c d"), 10).isEmpty())

        // Renames keep the search column in sync.
        documents.rename("d2", "Ação", SearchKeys.of("Ação"), now = 5)
        assertEquals(listOf("d2"), documents.searchByTitle(SearchKeys.likeQuery("acao"), 10).map { it.id })
        folders.rename("f5", "Índice", SearchKeys.of("Índice"), now = 5)
        assertEquals(listOf("f5"), folders.searchByName(SearchKeys.likeQuery("indice"), 10).map { it.id })
        notes.updateContent("n2", "Título", "Corpo", SearchKeys.note("Título", "Corpo"), now = 5)
        assertEquals(listOf("n2"), notes.search(SearchKeys.likeQuery("titulo"), 10).map { it.id })
        notes.rename("n2", "Novo", SearchKeys.note("Novo", "Corpo"), now = 6)
        assertEquals(listOf("n2"), notes.search(SearchKeys.likeQuery("corpo"), 10).map { it.id })
        assertTrue(notes.search(SearchKeys.likeQuery("titulo"), 10).isEmpty())
    }

    @Test
    fun readingPositionUpsertReplacesTheRowAndFeedsTheDocumentRow() = runTest {
        db.documentDao().insert(documentEntity("d"))
        val positions = db.readingPositionDao()
        val first = ReadingPositionEntity(
            documentId = "d", page = 3, pageOffset = 0.25f, zoom = 1f, offsetXFraction = 0f, currentPage = 4,
            maxPageReached = 5, updatedAt = 10, version = 1,
        )
        positions.upsert(first)
        assertEquals(first, positions.get("d"))

        val second = first.copy(page = 7, pageOffset = 0.5f, zoom = 2.5f, offsetXFraction = 0.4f, currentPage = 8, maxPageReached = 9, version = 2)
        positions.upsert(second)
        assertEquals(second, positions.get("d"))
        assertEquals(1f, second.toModel().progress(10), "the last page was visible: 100 %")

        db.documentDao().markOpened("d", now = 100)
        val row = db.documentDao().observeRecent(10).first().single()
        // "Continue reading" shows the page the reader's indicator showed, not the top page.
        assertEquals(8, row.lastPage)
        assertEquals(9, row.maxPageReached)

        positions.deleteForDocuments(listOf("d"))
        assertNull(positions.get("d"))
    }

    @Test
    fun libraryWideQueriesCoverEveryFolderButOnlyLiveRows() = runTest {
        val folders = db.folderDao()
        val documents = db.documentDao()
        val notes = db.noteDao()
        folders.insert(folderEntity("f"))
        documents.insert(documentEntity("root", lastOpenedAt = 50))
        documents.insert(documentEntity("nested", folderId = "f", lastOpenedAt = 200))
        documents.insert(documentEntity("never", folderId = "f"))
        documents.insert(documentEntity("archived", folderId = "f", archived = true, lastOpenedAt = 300))
        documents.insert(documentEntity("trashed", folderId = "f", lastOpenedAt = 400, trashedAt = 5, trashRootId = "trashed"))
        notes.insert(noteEntity("n-old", title = "old", modifiedAt = 10))
        notes.insert(noteEntity("n-new", folderId = "f", title = "new", modifiedAt = 30, favorite = true))
        notes.insert(noteEntity("n-mid", folderId = "f", title = "mid", modifiedAt = 20))

        assertEquals(setOf("root", "nested", "never"), documents.observeAll().first().map { it.id }.toSet())
        assertEquals(listOf("nested"), documents.observeOpenedSince(100).first().map { it.id })
        assertEquals(listOf("nested", "root"), documents.observeOpenedSince(50).first().map { it.id })
        assertEquals(listOf("n-new", "n-mid", "n-old"), notes.observeAll().first().map { it.id })
        assertEquals(listOf("n-new"), notes.observeFavorites().first().map { it.id })
        // Covers are kept for everything that is not a tombstone (trashed documents can come back).
        assertEquals(setOf("root", "nested", "never", "archived", "trashed"), documents.existingIds().toSet())
    }

    @Test
    fun onlyNeverFilledNotesAreHardDeleted() = runTest {
        val notes = db.noteDao()
        notes.insert(noteEntity("fresh"))
        notes.insert(noteEntity("cleared", version = 3))
        notes.insert(noteEntity("full", title = "x", version = 2))
        notes.insert(noteEntity("linked", linkedDocumentId = "doc"))

        assertEquals(1, notes.deleteIfEmpty("fresh"))
        assertNull(notes.getById("fresh"))

        assertEquals(0, notes.deleteIfEmpty("cleared"))
        assertEquals(1, notes.tombstoneIfEmpty("cleared", now = 50))
        val tombstone = notes.getById("cleared")!!
        assertEquals(50, tombstone.deletedAt)
        assertEquals(4, tombstone.version)
        assertEquals("", tombstone.searchText)
        assertEquals(0, notes.tombstoneIfEmpty("cleared", now = 60), "already a tombstone")

        assertEquals(0, notes.deleteIfEmpty("full"))
        assertEquals(0, notes.tombstoneIfEmpty("full", now = 50))
        assertEquals(0, notes.deleteIfEmpty("linked"))
        assertEquals(0, notes.tombstoneIfEmpty("linked", now = 50))
        assertNull(notes.getById("full")!!.deletedAt)
    }

    @Test
    fun syncMetadataMovesWithEveryChange() = runTest {
        val documents = db.documentDao()
        documents.insert(documentEntity("d"))
        documents.addAnnotationCount("d", delta = 2, now = 30)
        val updated = documents.getById("d")!!
        assertEquals(2, updated.annotationCount)
        assertEquals(30, updated.modifiedAt)
        assertEquals(2, updated.version)

        val tags = db.tagDao()
        tags.insert(TagEntity("t", "asme", createdAt = 1, modifiedAt = 1, version = 1, deletedAt = null))
        tags.link(DocumentTagEntity("d", "t", createdAt = 1, modifiedAt = 1, version = 1, deletedAt = null))
        assertEquals(listOf("t"), tags.observeForDocument("d").first().map { it.id })
        tags.unlink("d", "t", now = 40)
        assertTrue(tags.observeForDocument("d").first().isEmpty())
    }
}
