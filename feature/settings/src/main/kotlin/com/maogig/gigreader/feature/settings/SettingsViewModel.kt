package com.maogig.gigreader.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maogig.gigreader.core.data.settings.SettingsRepository
import com.maogig.gigreader.core.model.AppSettings
import com.maogig.gigreader.core.model.HighlightColor
import com.maogig.gigreader.core.model.LibraryLayout
import com.maogig.gigreader.core.model.ReaderPageBackground
import com.maogig.gigreader.core.model.ReaderScrollMode
import com.maogig.gigreader.core.model.SortField
import com.maogig.gigreader.core.model.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    /** True until the first value arrives from storage (avoids flashing defaults). */
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
)

/** Everything the user can change on the settings screen. */
sealed interface SettingsAction {
    data class SetTheme(val mode: ThemeMode) : SettingsAction
    data class SetAnimations(val enabled: Boolean) : SettingsAction
    data class SetPageGap(val dp: Int) : SettingsAction
    data class SetPageBackground(val background: ReaderPageBackground) : SettingsAction
    data class SetScrollMode(val mode: ReaderScrollMode) : SettingsAction
    data class SetKeepScreenOn(val enabled: Boolean) : SettingsAction
    data class SetHighlightColor(val color: HighlightColor) : SettingsAction
    data class SetLibraryLayout(val layout: LibraryLayout) : SettingsAction
    data class SetSortField(val field: SortField) : SettingsAction
    data class SetSortAscending(val ascending: Boolean) : SettingsAction
}

sealed interface SettingsEvent {
    /** A preference could not be written (storage error); the screen shows a snackbar. */
    data object SaveFailed : SettingsEvent
}

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = repository.settings
        .map { SettingsUiState(loading = false, settings = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    fun onAction(action: SettingsAction) {
        viewModelScope.launch {
            try {
                repository.update { it.reduce(action) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.SaveFailed)
            }
        }
    }

    companion object {
        const val PAGE_GAP_MAX_DP = 32
        const val PAGE_GAP_STEP_DP = 4

        /** Clamps to the slider range and snaps to the nearest step. */
        fun snapPageGap(dp: Int): Int {
            val clamped = dp.coerceIn(0, PAGE_GAP_MAX_DP)
            return (clamped + PAGE_GAP_STEP_DP / 2) / PAGE_GAP_STEP_DP * PAGE_GAP_STEP_DP
        }
    }
}

/** Pure state transition for one [SettingsAction]. */
internal fun AppSettings.reduce(action: SettingsAction): AppSettings = when (action) {
    is SettingsAction.SetTheme -> copy(themeMode = action.mode)
    is SettingsAction.SetAnimations -> copy(animationsEnabled = action.enabled)
    is SettingsAction.SetPageGap -> copy(readerPageGapDp = SettingsViewModel.snapPageGap(action.dp))
    is SettingsAction.SetPageBackground -> copy(readerBackground = action.background)
    is SettingsAction.SetScrollMode -> copy(readerScrollMode = action.mode)
    is SettingsAction.SetKeepScreenOn -> copy(readerKeepScreenOn = action.enabled)
    is SettingsAction.SetHighlightColor -> copy(defaultHighlightColor = action.color)
    is SettingsAction.SetLibraryLayout -> copy(libraryLayout = action.layout)
    is SettingsAction.SetSortField -> copy(librarySort = librarySort.copy(field = action.field))
    is SettingsAction.SetSortAscending -> copy(librarySort = librarySort.copy(ascending = action.ascending))
}
