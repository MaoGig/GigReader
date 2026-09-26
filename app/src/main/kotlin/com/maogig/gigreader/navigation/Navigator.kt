package com.maogig.gigreader.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID

/** Every screen of the app. Arguments are ids only; screens load their data themselves. */
@Immutable
sealed interface Destination {
    data class Library(val folderId: String? = null) : Destination
    data class Reader(val documentId: String) : Destination
    data class ExternalReader(val uri: String) : Destination
    data class NoteEditor(val noteId: String) : Destination
    data object Trash : Destination
    data object Settings : Destination
    data object Diagnostics : Destination
}

@Immutable
data class BackStackEntry(val key: String, val destination: Destination)

/**
 * Minimal navigation: a back stack of [Destination]s saved across process death, one
 * [SaveableStateHolder] slot and one [ViewModelStore] per entry (cleared when the entry is popped).
 * Six destinations do not justify a navigation library (§69/§73).
 */
@Stable
class Navigator internal constructor(initial: List<BackStackEntry>) {
    val backStack = mutableStateListOf<BackStackEntry>().apply { addAll(initial) }

    internal var onEntryRemoved: (BackStackEntry) -> Unit = {}

    val current: BackStackEntry get() = backStack.last()

    val canGoBack: Boolean get() = backStack.size > 1

    fun navigate(destination: Destination) {
        if (current.destination == destination) return
        backStack.add(newEntry(destination))
    }

    /** Returns `false` when already at the root (the caller then lets the system finish). */
    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        onEntryRemoved(backStack.removeAt(backStack.lastIndex))
        return true
    }

    /**
     * Goes to [destination] without duplicating it: if an entry for it is already in the back stack
     * (e.g. an ancestor folder tapped in the breadcrumbs), pops every entry above the topmost one
     * (their saved state and ViewModels are cleared); otherwise pushes it.
     */
    fun navigateOrPopTo(destination: Destination) {
        val index = backStack.indexOfLast { it.destination == destination }
        if (index >= 0) popAbove(index) else backStack.add(newEntry(destination))
    }

    /** Pops every entry above the library root. */
    fun popToRoot() = popAbove(0)

    /** Clears the stack down to the library root and pushes [destination] (benchmark hook). */
    fun resetTo(destination: Destination) {
        popAbove(0)
        if (destination != backStack.first().destination) backStack.add(newEntry(destination))
    }

    private fun popAbove(index: Int) {
        while (backStack.lastIndex > index) onEntryRemoved(backStack.removeAt(backStack.lastIndex))
    }

    companion object {
        fun newEntry(destination: Destination) = BackStackEntry(UUID.randomUUID().toString(), destination)

        val Saver: Saver<Navigator, ArrayList<String>> = Saver(
            save = { nav -> ArrayList(nav.backStack.map { "${it.key}|${encode(it.destination)}" }) },
            restore = { saved ->
                val entries = saved.mapNotNull { line ->
                    val key = line.substringBefore('|')
                    decode(line.substringAfter('|'))?.let { BackStackEntry(key, it) }
                }
                Navigator(entries.ifEmpty { listOf(newEntry(Destination.Library())) })
            },
        )

        private fun encode(d: Destination): String = when (d) {
            is Destination.Library -> "library:${d.folderId.orEmpty()}"
            is Destination.Reader -> "reader:${d.documentId}"
            is Destination.ExternalReader -> "external:${d.uri}"
            is Destination.NoteEditor -> "note:${d.noteId}"
            Destination.Trash -> "trash:"
            Destination.Settings -> "settings:"
            Destination.Diagnostics -> "diagnostics:"
        }

        private fun decode(s: String): Destination? {
            val kind = s.substringBefore(':')
            val arg = s.substringAfter(':')
            return when (kind) {
                "library" -> Destination.Library(arg.ifEmpty { null })
                "reader" -> Destination.Reader(arg)
                "external" -> Destination.ExternalReader(arg)
                "note" -> Destination.NoteEditor(arg)
                "trash" -> Destination.Trash
                "settings" -> Destination.Settings
                "diagnostics" -> Destination.Diagnostics
                else -> null
            }
        }
    }
}

/** Holds one [ViewModelStore] per back stack entry; retained across configuration changes. */
class EntryStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun storeFor(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    /** Drops stores whose entries no longer exist (e.g. after process death restored the stack). */
    fun retainOnly(keys: Set<String>) {
        stores.keys.filter { it !in keys }.forEach { clear(it) }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

@Composable
fun rememberNavigator(): Navigator =
    rememberSaveable(saver = Navigator.Saver) { Navigator(listOf(Navigator.newEntry(Destination.Library()))) }

/**
 * Renders the top entry of [navigator] with its own saveable state and ViewModel store.
 * Entries below the top keep their saved UI state (scroll positions) but are not composed.
 */
@Composable
fun NavHost(navigator: Navigator, content: @Composable (BackStackEntry) -> Unit) {
    val stateHolder: SaveableStateHolder = rememberSaveableStateHolder()
    val stores: EntryStores = viewModel()
    remember(navigator) {
        navigator.onEntryRemoved = { entry ->
            stateHolder.removeState(entry.key)
            stores.clear(entry.key)
        }
        stores.retainOnly(navigator.backStack.map { it.key }.toSet())
        true
    }
    val entry = navigator.current
    val owner = remember(entry.key) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = stores.storeFor(entry.key)
        }
    }
    stateHolder.SaveableStateProvider(entry.key) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            content(entry)
        }
    }
}
