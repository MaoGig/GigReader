package com.maogig.gigreader.feature.notes

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maogig.gigreader.core.data.notes.NotesRepository
import com.maogig.gigreader.core.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsContentOnceAndStartsIdle() = runTest(dispatcher) {
        val repo = FakeNotesRepository(note(title = "Groceries", body = "Milk"))
        val vm = NoteEditorViewModel(NOTE_ID, repo, persistScope = this)
        subscribe(vm)
        advanceUntilIdle()

        assertEquals("Groceries", vm.title)
        assertEquals("Milk", vm.body)
        assertEquals(NoteLoadState.READY, vm.uiState.value.load)
        assertEquals(NoteSaveState.IDLE, vm.uiState.value.save)
        assertFalse(vm.uiState.value.startedEmpty)

        // Later database changes never overwrite what is on screen.
        repo.notes[NOTE_ID] = note(title = "Changed elsewhere", body = "")
        advanceUntilIdle()
        assertEquals("Groceries", vm.title)
    }

    @Test
    fun keystrokesWithinTheWindowAreCoalescedIntoOneWrite() = runTest(dispatcher) {
        val repo = FakeNotesRepository(note(title = "", body = ""))
        val vm = NoteEditorViewModel(NOTE_ID, repo, persistScope = this)
        subscribe(vm)
        advanceUntilIdle()

        vm.onTitleChange("H")
        vm.onTitleChange("He")
        vm.onTitleChange("Hey")
        advanceTimeBy(300)
        vm.onBodyChange("x")
        runCurrent()
        assertEquals(NoteSaveState.SAVING, vm.uiState.value.save)
        assertTrue(repo.updates.isEmpty(), "nothing written before the window elapses")

        advanceTimeBy(NoteEditorViewModel.AUTOSAVE_DELAY_MILLIS - 300 + 1)
        runCurrent()
        assertEquals(listOf("Hey" to "x"), repo.updates)
        assertEquals(NoteSaveState.SAVED, vm.uiState.value.save)

        advanceUntilIdle()
        assertEquals(1, repo.updates.size, "no extra write while nothing is pending")
    }

    @Test
    fun newNoteLeftEmptyIsDiscardedOnLeave() = runTest(dispatcher) {
        val repo = FakeNotesRepository(note(title = "", body = ""))
        val vm = NoteEditorViewModel(NOTE_ID, repo, persistScope = this)
        subscribe(vm)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.startedEmpty)

        vm.onLeave()
        advanceUntilIdle()

        assertEquals(listOf(NOTE_ID), repo.discarded)
        assertTrue(repo.updates.isEmpty())
    }

    @Test
    fun whitespaceOnlyNoteIsStoredEmptyAndDiscarded() = runTest(dispatcher) {
        val repo = FakeNotesRepository(note(title = "", body = ""))
        val vm = NoteEditorViewModel(NOTE_ID, repo, persistScope = this)
        advanceUntilIdle()

        vm.onTitleChange("   ")
        vm.onBodyChange("\n")
        vm.onLeave()
        advanceUntilIdle()

        assertEquals(listOf("" to ""), repo.updates)
        assertEquals(listOf(NOTE_ID), repo.discarded)
    }

    @Test
    fun leavingFlushesPendingTextImmediatelyAndKeepsTheNote() = runTest(dispatcher) {
        val repo = FakeNotesRepository(note(title = "", body = ""))
        val vm = NoteEditorViewModel(NOTE_ID, repo, persistScope = this)
        advanceUntilIdle()

        vm.onBodyChange("Call Ana")
        vm.onLeave()
        runCurrent() // well before the autosave window

        assertEquals(listOf("" to "Call Ana"), repo.updates)
        assertTrue(repo.discarded.isEmpty())

        vm.onLeave() // idempotent: nothing new to write
        advanceUntilIdle()
        assertEquals(1, repo.updates.size)
    }

    @Test
    fun clearingTheViewModelFlushesPendingText() = runTest(dispatcher) {
        val repo = FakeNotesRepository(note(title = "", body = ""))
        val store = ViewModelStore()
        val persistScope: CoroutineScope = this
        val factory = viewModelFactory {
            initializer { NoteEditorViewModel(NOTE_ID, repo, persistScope = persistScope) }
        }
        val vm = ViewModelProvider(store, factory)[NoteEditorViewModel::class.java]
        advanceUntilIdle()

        vm.onTitleChange("Draft")
        store.clear() // cancels viewModelScope (and the scheduled autosave), then onCleared()
        advanceUntilIdle()

        assertEquals(listOf("Draft" to ""), repo.updates)
        assertTrue(repo.discarded.isEmpty())
    }

    @Test
    fun stopFlushesPendingText() = runTest(dispatcher) {
        val repo = FakeNotesRepository(note(title = "Title", body = ""))
        val vm = NoteEditorViewModel(NOTE_ID, repo, persistScope = this)
        advanceUntilIdle()

        vm.onBodyChange("Body")
        vm.onStop()
        runCurrent()

        assertEquals(listOf("Title" to "Body"), repo.updates)
    }

    @Test
    fun failedWriteIsReportedAndRetried() = runTest(dispatcher) {
        val repo = FakeNotesRepository(note(title = "", body = ""))
        val vm = NoteEditorViewModel(NOTE_ID, repo, persistScope = this)
        subscribe(vm)
        advanceUntilIdle()

        repo.failWrites = true
        vm.onBodyChange("a")
        advanceTimeBy(NoteEditorViewModel.AUTOSAVE_DELAY_MILLIS + 1)
        runCurrent()
        assertEquals(NoteSaveState.FAILED, vm.uiState.value.save)

        repo.failWrites = false
        vm.onBodyChange("ab")
        advanceTimeBy(NoteEditorViewModel.AUTOSAVE_DELAY_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("" to "ab"), repo.updates)
        assertEquals(NoteSaveState.SAVED, vm.uiState.value.save)
    }

    @Test
    fun missingNoteIgnoresEditsAndIsNeverWritten() = runTest(dispatcher) {
        val repo = FakeNotesRepository(initial = null)
        val vm = NoteEditorViewModel(NOTE_ID, repo, persistScope = this)
        subscribe(vm)
        advanceUntilIdle()

        assertEquals(NoteLoadState.MISSING, vm.uiState.value.load)
        vm.onTitleChange("x")
        vm.onLeave()
        advanceUntilIdle()

        assertEquals("", vm.title)
        assertTrue(repo.updates.isEmpty())
        assertTrue(repo.discarded.isEmpty())
    }

    @Test
    fun titleLineBreaksBecomeSpaces() = runTest(dispatcher) {
        val repo = FakeNotesRepository(note(title = "", body = ""))
        val vm = NoteEditorViewModel(NOTE_ID, repo, persistScope = this)
        advanceUntilIdle()

        vm.onTitleChange("first\nsecond\r\nthird")

        assertEquals("first second third", vm.title)
    }

    @Test
    fun saveStateIsDerivedFromRevisions() {
        assertEquals(NoteSaveState.IDLE, NoteEditorViewModel.saveStateOf(edited = 0, saved = 0, failed = 0))
        assertEquals(NoteSaveState.SAVING, NoteEditorViewModel.saveStateOf(edited = 3, saved = 2, failed = 0))
        assertEquals(NoteSaveState.SAVED, NoteEditorViewModel.saveStateOf(edited = 3, saved = 3, failed = 2))
        assertEquals(NoteSaveState.FAILED, NoteEditorViewModel.saveStateOf(edited = 4, saved = 2, failed = 3))
    }

    private fun TestScope.subscribe(vm: NoteEditorViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun note(title: String, body: String) = Note(
        id = NOTE_ID,
        folderId = null,
        title = title,
        body = body,
        createdAt = 0L,
        modifiedAt = 0L,
    )

    private class FakeNotesRepository(initial: Note?) : NotesRepository {
        val notes = mutableMapOf<String, Note>()
        val updates = mutableListOf<Pair<String, String>>()
        val discarded = mutableListOf<String>()
        var failWrites = false

        init {
            if (initial != null) notes[initial.id] = initial
        }

        override suspend fun createNote(folderId: String?, linkedDocumentId: String?, linkedPage: Int?): String =
            error("not used by the editor")

        override fun observeNote(id: String): Flow<Note?> = flowOf(notes[id])

        override suspend fun note(id: String): Note? = notes[id]

        override suspend fun updateContent(id: String, title: String, body: String) {
            if (failWrites) throw IOException("disk full")
            updates += title to body
            notes[id]?.let { notes[id] = it.copy(title = title, body = body) }
        }

        override suspend fun discardIfEmpty(id: String): Boolean {
            val note = notes[id] ?: return false
            if (note.title.isNotEmpty() || note.body.isNotEmpty()) return false
            notes.remove(id)
            discarded += id
            return true
        }
    }

    private companion object {
        const val NOTE_ID = "note-1"
    }
}
