package com.maogig.gigreader.core.data.library

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.data.SequentialIds
import com.maogig.gigreader.core.data.TestClock
import com.maogig.gigreader.core.data.insertDocument
import com.maogig.gigreader.core.data.notes.LocalNotesRepository
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.SearchKeys
import com.maogig.gigreader.core.database.entity.BookmarkEntity
import com.maogig.gigreader.core.database.entity.FolderEntity
import com.maogig.gigreader.core.database.entity.TextAnnotationEntity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LocalLibraryRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: GigReaderDatabase
    private lateinit var files: DocumentFileStore
    private lateinit var covers: CoverStore
    private lateinit var repo: LocalLibraryRepository
    private val clock = TestClock()

    @Before
    fun setUp() {
        db = GigReaderDatabase.inMemory(ApplicationProvider.getApplicationContext())
        files = DocumentFileStore(File(tmp.root, "library"))
        covers = CoverStore(File(tmp.root, "covers")).also { it.ensureDir() }
        repo = LocalLibraryRepository(db, files, covers, clock, SequentialIds())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun doc(id: String) = ItemRef(id, ItemKind.DOCUMENT)

    private fun folder(id: String) = ItemRef(id, ItemKind.FOLDER)

    private fun note(id: String) = ItemRef(id, ItemKind.NOTE)

    private suspend fun folderOf(documentId: String) = db.documentDao().getById(documentId)!!.folderId

    @Test
    fun moveRejectsCyclesAndUndoRedoRestoreLocations() = runTest {
        val a = repo.createFolder(null, "A")
        val b = repo.createFolder(a, "B")
        db.insertDocument(files, "d")

        assertEquals(MoveResult.WouldCreateCycle, repo.move(listOf(folder(a)), b))
        assertEquals(MoveResult.WouldCreateCycle, repo.move(listOf(folder(a)), a))
        assertEquals(a, db.folderDao().getById(b)!!.parentId, "a rejected move changes nothing")

        val moved = assertIs<MoveResult.Moved>(repo.move(listOf(doc("d"), folder(b)), null))
        assertNull(folderOf("d"))
        assertNull(db.folderDao().getById(b)!!.parentId)
        moved.undo.undo()
        assertNull(folderOf("d"), "d came from the root")
        assertEquals(a, db.folderDao().getById(b)!!.parentId)
        moved.undo.redo()
        assertNull(db.folderDao().getById(b)!!.parentId)

        val intoA = assertIs<MoveResult.Moved>(repo.move(listOf(doc("d")), a))
        assertEquals(a, folderOf("d"))
        intoA.undo.undo()
        assertNull(folderOf("d"))
        assertEquals(MoveResult.NothingToMove, repo.move(emptyList(), a))
    }

    @Test
    fun itemsNeverMoveIntoATrashedFolder() = runTest {
        val target = repo.createFolder(null, "Target")
        db.insertDocument(files, "d")
        repo.moveToTrash(listOf(folder(target)))

        assertEquals(MoveResult.NothingToMove, repo.move(listOf(doc("d")), target))
        assertNull(folderOf("d"))
        assertEquals(listOf("d"), repo.observeFolder(null).first().documents.map { it.id })
    }

    @Test
    fun undoMoveFallsBackToTheRootWhenTheOriginalFolderIsGone() = runTest {
        val origin = repo.createFolder(null, "Origin")
        val target = repo.createFolder(null, "Target")
        db.insertDocument(files, "d", folderId = origin)
        val moved = assertIs<MoveResult.Moved>(repo.move(listOf(doc("d")), target))
        repo.moveToTrash(listOf(folder(origin)))

        moved.undo.undo()

        assertNull(folderOf("d"), "undo must not hide the document inside a trashed folder")
        assertNull(db.documentDao().getById("d")!!.trashedAt)
        assertTrue(repo.observeFolder(null).first().documents.any { it.id == "d" })

        // Redo into a target trashed meanwhile leaves the document where it is.
        repo.moveToTrash(listOf(folder(target)))
        moved.undo.redo()
        assertNull(folderOf("d"))
    }

    @Test
    fun trashingAFolderCascadesAndRestoreBringsTheSubtreeBack() = runTest {
        val a = repo.createFolder(null, "A")
        val b = repo.createFolder(a, "B")
        db.insertDocument(files, "d1", folderId = a)
        db.insertDocument(files, "d2", folderId = b)
        val notes = LocalNotesRepository(db, clock, SequentialIds("note-"))
        val n = notes.createNote(folderId = b)
        notes.updateContent(n, "Note", "text")

        val undo = repo.moveToTrash(listOf(folder(a)))
        assertNotNull(undo)
        assertTrue(repo.observeFolder(null).first().isEmpty)
        assertEquals(listOf(folder(a)), repo.observeTrash().first().map { it.ref })
        assertEquals(a, db.documentDao().getById("d2")!!.trashRootId)
        assertEquals(a, db.noteDao().getById(n)!!.trashRootId)
        assertTrue(repo.search("note").isEmpty)

        repo.restoreFromTrash(listOf(folder(a)))
        assertEquals(listOf(a), repo.observeFolder(null).first().folders.map { it.id })
        val inB = repo.observeFolder(b).first()
        assertEquals(listOf("d2"), inB.documents.map { it.id })
        assertEquals(listOf(n), inB.notes.map { it.id })
        assertTrue(repo.observeTrash().first().isEmpty())

        // Undo of the trashing is a restore; redo trashes again.
        undo.redo()
        assertTrue(repo.observeFolder(null).first().isEmpty)
        undo.undo()
        assertEquals(listOf("d1"), repo.observeFolder(a).first().documents.map { it.id })
    }

    @Test
    fun restoringAnItemWhoseFolderIsGoneReattachesItToTheRoot() = runTest {
        val a = repo.createFolder(null, "A")
        db.insertDocument(files, "d", folderId = a)
        repo.moveToTrash(listOf(doc("d")))
        repo.moveToTrash(listOf(folder(a)))

        repo.restoreFromTrash(listOf(doc("d")))

        assertNull(folderOf("d"))
        assertEquals(listOf("d"), repo.observeFolder(null).first().documents.map { it.id })
    }

    @Test
    fun deleteForeverTombstonesTheSubtreeDeletesFilesAndReattachesLiveChildren() = runTest {
        val a = repo.createFolder(null, "A")
        db.insertDocument(files, "d1", folderId = a)
        covers.fileFor("d1").writeText("cover")
        db.annotationDao().insert(annotation("h1", "d1"))
        repo.moveToTrash(listOf(folder(a)))
        // Rows written into the folder after it was trashed (what older versions could do).
        db.insertDocument(files, "late", folderId = a)
        db.folderDao().insert(
            FolderEntity(
                id = "late-folder", parentId = a, name = "Late", searchName = SearchKeys.of("Late"), createdAt = 1,
                modifiedAt = 1, version = 1, trashedAt = null, trashRootId = null, deletedAt = null,
            ),
        )

        repo.deleteForever(listOf(folder(a)))

        val d1 = db.documentDao().getById("d1")!!
        assertNotNull(d1.deletedAt)
        assertEquals("", d1.sourcePath)
        assertEquals("", d1.searchTitle)
        assertFalse(File(files.rootDir, "d1.pdf").exists())
        assertFalse(covers.exists("d1"))
        assertTrue(db.annotationDao().allForDocument("d1").isEmpty())
        assertNotNull(db.folderDao().getById(a)!!.deletedAt)
        assertTrue(repo.observeTrash().first().isEmpty())

        // The live children now sit at the root instead of under a tombstone.
        val root = repo.observeFolder(null).first()
        assertEquals(listOf("late"), root.documents.map { it.id })
        assertEquals(listOf("late-folder"), root.folders.map { it.id })
        assertTrue(files.fileFor("late.pdf").exists())
    }

    @Test
    fun deleteForeverFinishesEvenWhenTheCallerIsCancelled() = runTest {
        db.insertDocument(files, "d")
        repo.moveToTrash(listOf(doc("d")))

        val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.deleteForever(listOf(doc("d"))) }
        job.cancel() // e.g. the user left the Trash screen right after confirming
        job.join()

        assertNotNull(db.documentDao().getById("d")!!.deletedAt)
        assertFalse(File(files.rootDir, "d.pdf").exists())
    }

    @Test
    fun deleteForeverSweepsOldOrphansButKeepsRecentAndReferencedFiles() = runTest {
        db.insertDocument(files, "live")
        db.insertDocument(files, "trashed")
        repo.moveToTrash(listOf(doc("trashed")))
        val old = clock.now() - 2 * 24 * 60 * 60 * 1000L
        fun cover(id: String, lastModified: Long? = old) =
            covers.fileFor(id).apply { writeText(id); lastModified?.let { setLastModified(it) } }
        val liveCover = cover("live")
        val orphanCover = cover("ghost")
        val recentOrphanCover = cover("importing", lastModified = null)
        val orphanFile = File(files.rootDir, "ghost.pdf").apply { writeText("x"); setLastModified(old) }
        File(files.rootDir, "live.pdf").setLastModified(old)
        db.insertDocument(files, "other")
        repo.moveToTrash(listOf(doc("other")))

        repo.deleteForever(listOf(doc("other")))

        assertTrue(liveCover.exists())
        assertFalse(orphanCover.exists())
        assertTrue(recentOrphanCover.exists(), "an import may still be inserting its row")
        assertFalse(orphanFile.exists())
        assertTrue(File(files.rootDir, "live.pdf").exists())
        assertTrue(File(files.rootDir, "trashed.pdf").exists(), "trashed documents keep their file")
    }

    @Test
    fun duplicateCopiesFileAndAnnotationsUnderTheGivenTitle() = runTest {
        val folderId = repo.createFolder(null, "Papers")
        db.insertDocument(files, "src", title = "Relatório", folderId = folderId, content = "%PDF-1.7 content")
        db.annotationDao().insert(annotation("h1", "src"))
        db.bookmarkDao().insert(BookmarkEntity("b1", "src", page = 2, title = "Intro", createdAt = 1, modifiedAt = 1, version = 1, deletedAt = null))
        covers.fileFor("src").writeText("cover")

        val copyId = repo.duplicateDocument("src", "Relatório (cópia)")

        val copy = db.documentDao().getById(copyId)!!
        assertEquals("Relatório (cópia)", copy.title)
        assertEquals(SearchKeys.of("Relatório (cópia)"), copy.searchTitle)
        assertEquals(folderId, copy.folderId)
        assertEquals(1, copy.version)
        assertTrue(copy.sourcePath != "src.pdf")
        assertEquals("%PDF-1.7 content", files.fileFor(copy.sourcePath).readText())
        assertEquals(1, db.annotationDao().allForDocument(copyId).size)
        assertEquals(1, db.bookmarkDao().allForDocument(copyId).size)
        assertTrue(covers.exists(copyId))
        assertEquals(setOf("src", copyId), repo.search("RELATORIO").documents.map { it.id }.toSet())

        // A blank title keeps the original one.
        val second = repo.duplicateDocument("src", "  ")
        assertEquals("Relatório", db.documentDao().getById(second)!!.title)
    }

    @Test
    fun itemsAreNeverCreatedUnderATrashedFolder() = runTest {
        val gone = repo.createFolder(null, "Gone")
        db.insertDocument(files, "d", folderId = gone)
        repo.moveToTrash(listOf(folder(gone)))

        val child = repo.createFolder(gone, "Child")
        assertNull(db.folderDao().getById(child)!!.parentId)
        val noteId = LocalNotesRepository(db, clock, SequentialIds("note-")).createNote(folderId = gone)
        assertNull(db.noteDao().getById(noteId)!!.folderId)
        // Duplicating a document that sits in the trash with its folder.
        val copy = repo.duplicateDocument("d", "Copy")
        assertNull(db.documentDao().getById(copy)!!.folderId)
    }

    @Test
    fun searchIgnoresCaseAndAccentsAndFollowsRenames() = runTest {
        val f = repo.createFolder(null, "Relatórios")
        db.insertDocument(files, "d", title = "Difusão Anômala")
        val notes = LocalNotesRepository(db, clock, SequentialIds("note-"))
        val n = notes.createNote()
        notes.updateContent(n, "Lista", "Café e pão")

        assertEquals(listOf(f), repo.search("RELATORIOS").folders.map { it.id })
        assertEquals(listOf("d"), repo.search("difusao").documents.map { it.id })
        assertEquals(listOf(n), repo.search("pao").notes.map { it.id })
        assertTrue(repo.search("   ").isEmpty)
        assertTrue(repo.search("%").isEmpty)

        val undo = repo.rename(note(n), "Ação")
        assertNotNull(undo)
        assertEquals(listOf(n), repo.search("acao").notes.map { it.id })
        assertEquals(listOf(n), repo.search("cafe").notes.map { it.id }, "the body stays indexed")
        undo.undo()
        assertEquals(listOf(n), repo.search("lista").notes.map { it.id })
        assertTrue(repo.search("acao").isEmpty)

        repo.rename(doc("d"), "Ótica")
        assertEquals(listOf("d"), repo.search("OTICA").documents.map { it.id })
    }

    @Test
    fun libraryWideListsSpanFolders() = runTest {
        val f = repo.createFolder(null, "F")
        db.insertDocument(files, "root")
        db.insertDocument(files, "nested", folderId = f)
        val notes = LocalNotesRepository(db, clock, SequentialIds("note-"))
        val older = notes.createNote(folderId = f)
        notes.updateContent(older, "older", "")
        repo.setFavorite(listOf(note(older)), favorite = true)
        clock.millis += 1000
        val newer = notes.createNote()
        notes.updateContent(newer, "newer", "")
        clock.millis += 1000
        db.documentDao().markOpened("nested", clock.now())

        assertEquals(setOf("root", "nested"), repo.observeAllDocuments().first().map { it.id }.toSet())
        assertEquals(listOf(newer, older), repo.observeAllNotes().first().map { it.id })
        assertEquals(listOf(older), repo.observeFavoriteNotes().first().map { it.id })
        assertEquals(listOf("nested"), repo.observeOpenedSince(clock.now() - 500).first().map { it.id })
        assertTrue(repo.observeOpenedSince(clock.now() + 1).first().isEmpty())
    }

    private fun annotation(id: String, documentId: String) = TextAnnotationEntity(
        id = id, documentId = documentId, page = 0, type = "HIGHLIGHT", text = "t", rects = "0,0,1,1", color = "yellow",
        note = null, charStart = null, charEnd = null, createdAt = 1, modifiedAt = 1, version = 1, deletedAt = null,
    )
}
