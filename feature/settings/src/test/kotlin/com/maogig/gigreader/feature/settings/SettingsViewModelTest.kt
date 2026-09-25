package com.maogig.gigreader.feature.settings

import com.maogig.gigreader.core.data.settings.SettingsRepository
import com.maogig.gigreader.core.model.AppSettings
import com.maogig.gigreader.core.model.HighlightColor
import com.maogig.gigreader.core.model.LibraryLayout
import com.maogig.gigreader.core.model.ReaderPageBackground
import com.maogig.gigreader.core.model.ReaderScrollMode
import com.maogig.gigreader.core.model.SortField
import com.maogig.gigreader.core.model.SortOrder
import com.maogig.gigreader.core.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
class SettingsViewModelTest {
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
    fun startsLoadingThenExposesStoredSettings() = runTest(dispatcher) {
        val stored = AppSettings(themeMode = ThemeMode.DARK, readerPageGapDp = 12)
        val repo = FakeSettingsRepository(stored)
        val vm = SettingsViewModel(repo)
        assertTrue(vm.uiState.value.loading)

        subscribe(vm)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.loading)
        assertEquals(stored, vm.uiState.value.settings)
    }

    @Test
    fun appearanceActionsArePersisted() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)
        subscribe(vm)

        vm.onAction(SettingsAction.SetTheme(ThemeMode.AMOLED))
        vm.onAction(SettingsAction.SetAnimations(false))
        advanceUntilIdle()

        assertEquals(ThemeMode.AMOLED, repo.state.value.themeMode)
        assertFalse(repo.state.value.animationsEnabled)
        assertEquals(repo.state.value, vm.uiState.value.settings)
    }

    @Test
    fun readerAndHighlightActionsArePersisted() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)

        vm.onAction(SettingsAction.SetPageBackground(ReaderPageBackground.SEPIA))
        vm.onAction(SettingsAction.SetScrollMode(ReaderScrollMode.PAGED))
        vm.onAction(SettingsAction.SetKeepScreenOn(true))
        vm.onAction(SettingsAction.SetHighlightColor(HighlightColor.BLUE))
        vm.onAction(SettingsAction.SetLibraryLayout(LibraryLayout.COMPACT))
        advanceUntilIdle()

        val saved = repo.state.value
        assertEquals(ReaderPageBackground.SEPIA, saved.readerBackground)
        assertEquals(ReaderScrollMode.PAGED, saved.readerScrollMode)
        assertTrue(saved.readerKeepScreenOn)
        assertEquals(HighlightColor.BLUE, saved.defaultHighlightColor)
        assertEquals(LibraryLayout.COMPACT, saved.libraryLayout)
    }

    @Test
    fun pageGapIsClampedAndSnappedToSteps() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)

        vm.onAction(SettingsAction.SetPageGap(9))
        advanceUntilIdle()
        assertEquals(8, repo.state.value.readerPageGapDp)

        vm.onAction(SettingsAction.SetPageGap(100))
        advanceUntilIdle()
        assertEquals(32, repo.state.value.readerPageGapDp)

        vm.onAction(SettingsAction.SetPageGap(-3))
        advanceUntilIdle()
        assertEquals(0, repo.state.value.readerPageGapDp)

        assertEquals(16, SettingsViewModel.snapPageGap(14))
        assertEquals(12, SettingsViewModel.snapPageGap(13))
        assertEquals(28, SettingsViewModel.snapPageGap(30 - 1))
    }

    @Test
    fun sortFieldAndDirectionChangeIndependently() = runTest(dispatcher) {
        val repo = FakeSettingsRepository(AppSettings(librarySort = SortOrder(SortField.LAST_OPENED, ascending = false)))
        val vm = SettingsViewModel(repo)

        vm.onAction(SettingsAction.SetSortField(SortField.SIZE))
        advanceUntilIdle()
        assertEquals(SortOrder(SortField.SIZE, ascending = false), repo.state.value.librarySort)

        vm.onAction(SettingsAction.SetSortAscending(true))
        advanceUntilIdle()
        assertEquals(SortOrder(SortField.SIZE, ascending = true), repo.state.value.librarySort)
    }

    @Test
    fun failedWriteEmitsOneShotEvent() = runTest(dispatcher) {
        val repo = FakeSettingsRepository().apply { failWrites = true }
        val vm = SettingsViewModel(repo)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.onAction(SettingsAction.SetTheme(ThemeMode.LIGHT))
        advanceUntilIdle()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.SaveFailed), events)
        assertEquals(AppSettings(), repo.state.value)
    }

    @Test
    fun reduceOnlyTouchesTheTargetedField() {
        val base = AppSettings()
        assertEquals(base.copy(readerPageGapDp = 4), base.reduce(SettingsAction.SetPageGap(5)))
        assertEquals(
            base.copy(librarySort = base.librarySort.copy(field = SortField.NAME)),
            base.reduce(SettingsAction.SetSortField(SortField.NAME)),
        )
    }

    private fun TestScope.subscribe(vm: SettingsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        val state = MutableStateFlow(initial)
        var failWrites = false

        override val settings: Flow<AppSettings> = state

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            if (failWrites) throw IOException("read-only storage")
            state.value = transform(state.value)
        }
    }
}
