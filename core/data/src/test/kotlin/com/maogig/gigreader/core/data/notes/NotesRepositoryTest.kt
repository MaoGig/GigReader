package com.maogig.gigreader.core.data.notes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maogig.gigreader.core.data.SequentialIds
import com.maogig.gigreader.core.data.TestClock
import com.maogig.gigreader.core.database.GigReaderDatabase
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

@RunWith(AndroidJUnit4::class)
class NotesRepositoryTest {
    private lateinit var db: GigReaderDatabase
    private lateinit var notes: LocalNotesRepository
    private val clock = TestClock()

    @Before
    fun setUp() {
        db = GigReaderDatabase.inMemory(ApplicationProvider.getApplicationContext())
        notes = LocalNotesRepository(db, clock, SequentialIds())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun aNoteThatNeverHadContentIsDeletedOutright() = runTest {
        val id = notes.createNote()
        assertNotNull(notes.note(id))

        assertTrue(notes.discardIfEmpty(id))
        assertNull(db.noteDao().getById(id), "never written: nothing to propagate, no tombstone")
    }

    @Test
    fun aClearedNoteBecomesATombstone() = runTest {
        val id = notes.createNote()
        notes.updateContent(id, "Shopping", "milk")
        clock.millis += 1000
        notes.updateContent(id, "", "")

        assertTrue(notes.discardIfEmpty(id))

        val row = assertNotNull(db.noteDao().getById(id), "it may have been synced: the deletion must be kept")
        assertEquals(clock.now(), row.deletedAt)
        assertEquals("", row.title)
        assertEquals("", row.body)
        assertEquals(4, row.version)
        assertNull(notes.note(id))
        assertNull(notes.observeNote(id).first())
        assertFalse(notes.discardIfEmpty(id), "already gone")
    }

    @Test
    fun notesWithContentOrALinkAreKept() = runTest {
        val full = notes.createNote()
        notes.updateContent(full, "", "text")
        assertFalse(notes.discardIfEmpty(full))
        assertEquals("text", notes.note(full)!!.body)

        val linked = notes.createNote(linkedDocumentId = "doc", linkedPage = 3)
        assertFalse(notes.discardIfEmpty(linked))
        assertNotNull(notes.note(linked))
    }

    @Test
    fun updateContentBumpsVersionAndModifiedAt() = runTest {
        val id = notes.createNote()
        clock.millis += 5000
        notes.updateContent(id, "Title", "Body")
        val note = assertNotNull(notes.note(id))
        assertEquals("Title", note.title)
        assertEquals("Body", note.body)
        assertEquals(2, note.version)
        assertEquals(clock.now(), note.modifiedAt)
    }
}
