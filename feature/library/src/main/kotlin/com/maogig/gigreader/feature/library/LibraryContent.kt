package com.maogig.gigreader.feature.library

import com.maogig.gigreader.core.common.FolderTree
import com.maogig.gigreader.core.data.library.FolderContents
import com.maogig.gigreader.core.data.library.ItemKind
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.data.library.LibrarySearchResults
import com.maogig.gigreader.core.data.library.ref
import com.maogig.gigreader.core.model.LibraryFilter
import com.maogig.gigreader.core.model.LibraryItem
import com.maogig.gigreader.core.model.SortOrder
import com.maogig.gigreader.core.model.sortedForDisplay

/** "Recently opened" filter window. */
internal const val RECENT_WINDOW_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

/** Library-wide rows shown only on the Home (root). */
internal data class RootShelves(
    val continueReading: List<LibraryItem.DocumentEntry> = emptyList(),
    val favorites: List<LibraryItem.DocumentEntry> = emptyList(),
    val recentNotes: List<LibraryItem.NoteEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = continueReading.isEmpty() && favorites.isEmpty() && recentNotes.isEmpty()

    companion object {
        val Empty = RootShelves()
    }
}

/** Debounced search: [results] is `null` while no search is active. */
internal data class SearchSnapshot(val query: String, val results: LibrarySearchResults?) {
    companion object {
        val Idle = SearchSnapshot("", null)
    }
}

/**
 * Result of [buildLibraryContent]: the grid rows, the refs "Select all" selects (the main listing,
 * not the shortcut shelves) and every visible item by ref (for selection bookkeeping).
 */
internal class LibraryContent(
    val rows: List<LibraryRow>,
    val selectable: List<ItemRef>,
    val visible: Map<ItemRef, LibraryItem>,
)

internal fun LibraryItem.matches(filter: LibraryFilter, nowMillis: Long): Boolean = when (filter) {
    LibraryFilter.ALL -> true
    LibraryFilter.PDFS -> this is LibraryItem.DocumentEntry
    LibraryFilter.NOTES -> this is LibraryItem.NoteEntry
    LibraryFilter.ANNOTATED -> this is LibraryItem.DocumentEntry && annotationCount > 0
    LibraryFilter.FAVORITES -> isFavorite()
    LibraryFilter.RECENT -> {
        val opened = (this as? LibraryItem.DocumentEntry)?.lastOpenedAt
        opened != null && nowMillis - opened <= RECENT_WINDOW_MILLIS
    }
}

/**
 * Builds the grid rows for one screen. Pure and allocation-bounded; runs on
 * `Dispatchers.Default` (via `flowOn`) so sorting/filtering never happens during composition.
 *
 * - Search active → results grouped as Folders / Documents / Notes (repository relevance order).
 * - Filter active → matching documents and notes (folders hidden). On the Home the pool also
 *   includes the library-wide shelves, so "Favorites" and "Recently opened" are useful at the root.
 * - Otherwise → Home sections (root) or the folder's subfolders, documents and notes.
 */
internal fun buildLibraryContent(
    isRoot: Boolean,
    contents: FolderContents,
    shelves: RootShelves,
    search: SearchSnapshot,
    filter: LibraryFilter,
    sort: SortOrder,
    nowMillis: Long,
): LibraryContent {
    val builder = RowsBuilder()
    val results = search.results
    when {
        results != null -> {
            builder.section(LibrarySection.FOLDERS, results.folders)
            builder.section(LibrarySection.DOCUMENTS, results.documents)
            builder.section(LibrarySection.NOTES, results.notes)
            if (!builder.hasCells) builder.empty(EmptyKind.SEARCH, search.query)
        }
        filter != LibraryFilter.ALL -> {
            builder.add(LibraryRow.ActiveFilter(filter))
            val pool = LinkedHashMap<ItemRef, LibraryItem>()
            contents.documents.forEach { pool.addIfAbsent(it.ref(), it) }
            contents.notes.forEach { pool.addIfAbsent(it.ref(), it) }
            if (isRoot) {
                shelves.continueReading.forEach { pool.addIfAbsent(it.ref(), it) }
                shelves.favorites.forEach { pool.addIfAbsent(it.ref(), it) }
                shelves.recentNotes.forEach { pool.addIfAbsent(it.ref(), it) }
            }
            val matching = pool.values.filter { it.matches(filter, nowMillis) }.sortedForDisplay(sort)
            builder.section(LibrarySection.DOCUMENTS, matching.filter { it is LibraryItem.DocumentEntry })
            builder.section(
                if (isRoot) LibrarySection.QUICK_NOTES else LibrarySection.NOTES,
                matching.filter { it is LibraryItem.NoteEntry },
            )
            if (!builder.hasCells) builder.empty(EmptyKind.FILTER)
        }
        else -> {
            if (isRoot) {
                builder.shelf(LibrarySection.CONTINUE_READING, shelves.continueReading)
                builder.shelf(LibrarySection.FAVORITES, shelves.favorites)
            }
            builder.section(LibrarySection.FOLDERS, contents.folders.sortedForDisplay(sort))
            builder.section(LibrarySection.DOCUMENTS, contents.documents.sortedForDisplay(sort))
            if (isRoot) {
                // Quick notes: notes kept at the root plus the most recently edited ones anywhere.
                val notes = LinkedHashMap<String, LibraryItem.NoteEntry>()
                contents.notes.forEach { notes[it.id] = it }
                shelves.recentNotes.forEach { notes.addIfAbsent(it.id, it) }
                builder.section(LibrarySection.QUICK_NOTES, notes.values.toList().sortedForDisplay(sort))
            } else {
                builder.section(LibrarySection.NOTES, contents.notes.sortedForDisplay(sort))
            }
            if (!builder.hasCells && !builder.hasShelves) {
                builder.empty(if (isRoot) EmptyKind.LIBRARY else EmptyKind.FOLDER)
            }
        }
    }
    return builder.build()
}

/** `Map.putIfAbsent` needs API 24; this works on every supported API level. */
private fun <K, V> MutableMap<K, V>.addIfAbsent(key: K, value: V) {
    if (!containsKey(key)) put(key, value)
}

private class RowsBuilder {
    private val rows = ArrayList<LibraryRow>()
    private val selectable = ArrayList<ItemRef>()
    private val visible = LinkedHashMap<ItemRef, LibraryItem>()
    private val keys = HashSet<String>()
    var hasCells = false
        private set
    var hasShelves = false
        private set

    fun add(row: LibraryRow) {
        rows.add(row)
    }

    fun section(section: LibrarySection, items: List<LibraryItem>) {
        if (items.isEmpty()) return
        val headerIndex = rows.size
        var count = 0
        for (item in items) {
            val cell = LibraryRow.Cell(item)
            // Lazy grid keys must be unique; an item can only be listed once per screen.
            if (!keys.add(cell.key)) continue
            if (count == 0) rows.add(LibraryRow.Header(section, 0))
            rows.add(cell)
            selectable.add(cell.ref)
            visible[cell.ref] = item
            count++
        }
        if (count > 0) {
            rows[headerIndex] = LibraryRow.Header(section, count)
            hasCells = true
        }
    }

    fun shelf(section: LibrarySection, items: List<LibraryItem.DocumentEntry>) {
        if (items.isEmpty()) return
        rows.add(LibraryRow.Shelf(section, items))
        items.forEach { visible.addIfAbsent(it.ref(), it) }
        hasShelves = true
    }

    fun empty(kind: EmptyKind, query: String = "") {
        rows.add(LibraryRow.Empty(kind, query))
    }

    fun build() = LibraryContent(rows, selectable, visible)
}

/**
 * Destinations of the move dialog: the root plus every folder (depth-first, indented). Targets that
 * would create a cycle (a moved folder into itself or a descendant) are disabled.
 */
internal fun buildMoveTargets(tree: FolderTree, items: Collection<ItemRef>): List<MoveTarget> {
    val movingFolders = items.filter { it.kind == ItemKind.FOLDER }.map { it.id }
    val flat = tree.flatten()
    val targets = ArrayList<MoveTarget>(flat.size + 1)
    targets.add(MoveTarget(folderId = null, name = null, depth = 0, enabled = tree.canMoveInto(movingFolders, null)))
    for ((node, depth) in flat) {
        targets.add(
            MoveTarget(
                folderId = node.id,
                name = node.name,
                depth = depth + 1,
                enabled = tree.canMoveInto(movingFolders, node.id),
            ),
        )
    }
    return targets
}

/** Breadcrumbs for [folderId]: the root followed by the path to the folder (inclusive). */
internal fun buildBreadcrumbs(tree: FolderTree, folderId: String?): List<Crumb> {
    if (folderId == null) return emptyList()
    val path = tree.pathTo(folderId)
    val crumbs = ArrayList<Crumb>(path.size + 1)
    crumbs.add(Crumb(null, null))
    path.forEach { crumbs.add(Crumb(it.id, it.name)) }
    return crumbs
}
