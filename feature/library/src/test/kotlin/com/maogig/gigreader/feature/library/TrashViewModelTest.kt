package com.maogig.gigreader.feature.library

import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.data.library.ItemKind
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.data.library.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val library = FakeLibraryRepository()
    private val entry = TrashEntry(ItemRef("d1", ItemKind.DOCUMENT), "Paper", trashedAt = 10 * DAY)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): Pair<TrashViewModel, List<LibraryMessage>> {
        val vm = TrashViewModel(library, Clock { 12 * DAY })
        val messages = mutableListOf<LibraryMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { messages.add(it) } }
        return vm to messages
    }

    @Test
    fun openingTheTrashPurgesExpiredItemsOnce() = runTest(dispatcher) {
        library.trash.value = listOf(entry)
        val (vm, _) = viewModel()
        advanceUntilIdle()

        assertEquals(1, library.purged)
        assertEquals(listOf(entry), vm.uiState.value.entries)
        assertEquals(12 * DAY, vm.uiState.value.nowMillis)
    }

    @Test
    fun restoreReportsTheItem() = runTest(dispatcher) {
        val (vm, messages) = viewModel()
        advanceUntilIdle()

        vm.restore(entry)
        advanceUntilIdle()

        assertEquals(listOf(listOf(entry.ref)), library.restored)
        assertEquals(listOf<LibraryMessage>(LibraryMessage.Restored("Paper")), messages)
    }

    @Test
    fun destructiveActionsRequireConfirmation() = runTest(dispatcher) {
        val (vm, messages) = viewModel()
        advanceUntilIdle()

        vm.requestDeleteForever(entry)
        advanceUntilIdle()
        assertIs<TrashConfirmation.DeleteForever>(vm.uiState.value.confirmation)
        assertTrue(library.deletedForever.isEmpty())

        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf(listOf(entry.ref)), library.deletedForever)
        assertNull(vm.uiState.value.confirmation)

        vm.requestEmptyTrash()
        vm.dismissConfirmation()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(0, library.emptied)

        vm.requestEmptyTrash()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(1, library.emptied)
        assertEquals(listOf(LibraryMessage.DeletedForever, LibraryMessage.TrashEmptied), messages)
    }
}
