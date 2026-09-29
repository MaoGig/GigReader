package com.maogig.gigreader.feature.notes

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maogig.gigreader.core.common.coroutines.CoalescingSaver
import com.maogig.gigreader.core.data.notes.NotesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NoteLoadState { LOADING, READY, MISSING }

enum class NoteSaveState {
    /** Nothing edited since the note was opened. */
    IDLE,
    SAVING,
    SAVED,

    /** The last write failed; the content is kept and retried on the next change or flush. */
    FAILED,
}

/**
 * Screen state of the note editor. The text itself is NOT part of it: title and body live in
 * snapshot state on the ViewModel ([NoteEditorViewModel.title]/[NoteEditorViewModel.body]) so a
 * keystroke only recomposes the field being edited, not the whole screen.
 */
@Immutable
data class NoteEditorUiState(
    val load: NoteLoadState = NoteLoadState.LOADING,
    val save: NoteSaveState = NoteSaveState.IDLE,
    /** The note was empty when opened (a fresh "+ New note"), so the title field takes focus. */
    val startedEmpty: Boolean = false,
)

/** One autosave unit; [revision] identifies which edit it contains (for the save indicator). */
internal data class NoteContent(val title: String, val body: String, val revision: Long)

/**
 * Note editor. Content is loaded once; later database emissions are ignored so they can never
 * overwrite what the user is typing. Every change is submitted to a [CoalescingSaver] (at most one
 * write per [autosaveDelayMillis]); pending content is flushed when the screen stops, when the user
 * leaves and when the ViewModel is cleared. The final flushes run in [persistScope], which outlives
 * `viewModelScope`, so they complete even after the screen is gone.
 */
class NoteEditorViewModel(
    private val noteId: String,
    private val notes: NotesRepository,
    private val persistScope: CoroutineScope,
    autosaveDelayMillis: Long = AUTOSAVE_DELAY_MILLIS,
) : ViewModel() {

    var title: String by mutableStateOf("")
        private set

    var body: String by mutableStateOf("")
        private set

    private val load = MutableStateFlow(NoteLoadState.LOADING)
    private val startedEmpty = MutableStateFlow(false)

    // Monotonic revision counters. The save indicator is a pure function of them, so writes that
    // finish on another thread can never leave a stale "Saved" while newer text is pending.
    private val editedRevision = MutableStateFlow(0L)
    private val savedRevision = MutableStateFlow(0L)
    private val failedRevision = MutableStateFlow(0L)

    /** Revision handled by the last [finish]; avoids repeating the same final flush/discard. */
    private var finishedRevision = -1L

    private val saver = CoalescingSaver<NoteContent>(
        scope = viewModelScope,
        delayMillis = autosaveDelayMillis,
        save = { content -> persist(content) },
    )

    val uiState: StateFlow<NoteEditorUiState> =
        combine(load, startedEmpty, editedRevision, savedRevision, failedRevision) { loadState, empty, edited, saved, failed ->
            NoteEditorUiState(load = loadState, save = saveStateOf(edited, saved, failed), startedEmpty = empty)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteEditorUiState())

    init {
        viewModelScope.launch {
            val note = try {
                notes.note(noteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (note == null || note.trashedAt != null) {
                load.value = NoteLoadState.MISSING
                return@launch
            }
            title = note.title
            body = note.body
            startedEmpty.value = note.title.isEmpty() && note.body.isEmpty()
            load.value = NoteLoadState.READY
        }
    }

    fun onTitleChange(value: String) {
        if (load.value != NoteLoadState.READY) return
        val singleLine = value.toSingleLine()
        if (singleLine == title) return
        title = singleLine
        submitCurrent()
    }

    fun onBodyChange(value: String) {
        if (load.value != NoteLoadState.READY || value == body) return
        body = value
        submitCurrent()
    }

    /** Explicit save (Ctrl+S): writes pending content now instead of at the end of the window. */
    fun saveNow() {
        if (saver.hasPending) viewModelScope.launch { saver.flush() }
    }

    /** The screen stopped (app in background): persist now, the process may die afterwards. */
    fun onStop() {
        if (saver.hasPending) persistScope.launch { saver.flush() }
    }

    /** The user is leaving the editor (back): flush, and drop the note if it was left empty. */
    fun onLeave() {
        finish()
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here; finish() only uses persistScope.
        finish()
    }

    private fun finish() {
        if (load.value != NoteLoadState.READY) return
        val revision = editedRevision.value
        // Already handled, unless a write failed meanwhile and its content is still pending.
        if (revision == finishedRevision && !saver.hasPending) return
        finishedRevision = revision
        val empty = title.isBlank() && body.isBlank()
        if (empty && (title.isNotEmpty() || body.isNotEmpty())) {
            // Whitespace only: store it as truly empty so the repository recognizes an empty note.
            saver.submit(NoteContent(title = "", body = "", revision = revision))
        }
        if (!empty && !saver.hasPending) return
        persistScope.launch {
            saver.flush() // never throws: a failed write stays pending inside the saver
            if (empty) {
                try {
                    notes.discardIfEmpty(noteId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort: an empty note left behind is harmless and can be deleted by hand.
                }
            }
        }
    }

    private fun submitCurrent() {
        val revision = editedRevision.value + 1
        editedRevision.value = revision
        saver.submit(NoteContent(title = title, body = body, revision = revision))
    }

    private suspend fun persist(content: NoteContent) {
        try {
            // A write that has started always completes, even if the screen closes meanwhile.
            withContext(NonCancellable) { notes.updateContent(noteId, content.title, content.body) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedRevision.update { maxOf(it, content.revision) }
            throw e // CoalescingSaver keeps the value and retries it on the next submit/flush.
        }
        savedRevision.update { maxOf(it, content.revision) }
    }

    companion object {
        const val AUTOSAVE_DELAY_MILLIS = 700L

        internal fun saveStateOf(edited: Long, saved: Long, failed: Long): NoteSaveState = when {
            edited == 0L -> NoteSaveState.IDLE
            saved >= edited -> NoteSaveState.SAVED
            failed > saved -> NoteSaveState.FAILED
            else -> NoteSaveState.SAVING
        }
    }
}

/** Titles are single line; pasted line breaks become spaces. */
private fun String.toSingleLine(): String =
    if (none { it == '\n' || it == '\r' }) this
    else replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
