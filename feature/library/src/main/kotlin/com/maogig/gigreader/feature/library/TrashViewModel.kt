package com.maogig.gigreader.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.data.library.LibraryRepository
import com.maogig.gigreader.core.data.library.TrashEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Confirmation pending in the trash screen (both actions are destructive and not undoable). */
@Immutable
internal sealed interface TrashConfirmation {
    data class DeleteForever(val entry: TrashEntry) : TrashConfirmation

    data object EmptyTrash : TrashConfirmation
}

@Immutable
internal data class TrashUiState(
    val loading: Boolean = true,
    val entries: List<TrashEntry> = emptyList(),
    val confirmation: TrashConfirmation? = null,
    val nowMillis: Long = 0L,
)

/**
 * Trash: lists trashed roots, restores them or deletes them for good. Expired items (30 days) are
 * purged lazily when this screen opens (and once per process by the Home, see LibraryViewModel);
 * there is no background job (docs/PERFORMANCE_AND_POWER.md).
 */
internal class TrashViewModel(
    private val library: LibraryRepository,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val confirmation = MutableStateFlow<TrashConfirmation?>(null)
    private val _events = Channel<LibraryMessage>(Channel.BUFFERED)
    val events: Flow<LibraryMessage> = _events.receiveAsFlow()

    val uiState: StateFlow<TrashUiState> = combine(library.observeTrash(), confirmation) { entries, pending ->
        TrashUiState(loading = false, entries = entries, confirmation = pending, nowMillis = clock.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    init {
        launchSafely { library.purgeExpiredTrash() }
    }

    fun restore(entry: TrashEntry) = launchSafely {
        library.restoreFromTrash(listOf(entry.ref))
        _events.send(LibraryMessage.Restored(entry.title))
    }

    fun requestDeleteForever(entry: TrashEntry) {
        confirmation.value = TrashConfirmation.DeleteForever(entry)
    }

    fun requestEmptyTrash() {
        confirmation.value = TrashConfirmation.EmptyTrash
    }

    fun dismissConfirmation() {
        confirmation.value = null
    }

    /** Runs the pending confirmation. */
    fun confirm() {
        val pending = confirmation.value ?: return
        confirmation.value = null
        when (pending) {
            is TrashConfirmation.DeleteForever -> launchSafely {
                library.deleteForever(listOf(pending.entry.ref))
                _events.send(LibraryMessage.DeletedForever)
            }
            TrashConfirmation.EmptyTrash -> launchSafely {
                library.emptyTrash()
                _events.send(LibraryMessage.TrashEmptied)
            }
        }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(LibraryMessage.Failed)
            }
        }
    }
}
