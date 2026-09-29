package com.maogig.gigreader.feature.library

import androidx.compose.runtime.Immutable
import com.maogig.gigreader.core.data.importer.ImportError
import com.maogig.gigreader.core.data.library.ItemKind
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.data.library.ref
import com.maogig.gigreader.core.model.LibraryFilter
import com.maogig.gigreader.core.model.LibraryItem
import com.maogig.gigreader.core.model.LibraryLayout
import com.maogig.gigreader.core.model.SortOrder
import java.io.File

/** Sections of the Home and of a folder listing, in display order. */
internal enum class LibrarySection { CONTINUE_READING, FAVORITES, FOLDERS, DOCUMENTS, QUICK_NOTES, NOTES }

internal enum class EmptyKind {
    /** Nothing at all in the library (root, no search, no filter). */
    LIBRARY,

    /** The open folder has no children. */
    FOLDER,

    /** The search query matched nothing. */
    SEARCH,

    /** The active filter hides everything. */
    FILTER,
}

internal const val CONTENT_TYPE_HEADER = "header"
internal const val CONTENT_TYPE_SHELF = "shelf"
internal const val CONTENT_TYPE_FOLDER = "folder"
internal const val CONTENT_TYPE_DOCUMENT = "document"
internal const val CONTENT_TYPE_NOTE = "note"
internal const val CONTENT_TYPE_FILTER = "filter"
internal const val CONTENT_TYPE_EMPTY = "empty"

/**
 * One entry of the library grid. The whole screen is a single lazy grid: headers, horizontal
 * shelves and empty states span the full line, items take one cell. Keys and content types are
 * computed once, when the row is built off the main thread.
 */
@Immutable
internal sealed interface LibraryRow {
    val key: String
    val contentType: String
    val fullSpan: Boolean

    data class Header(val section: LibrarySection, val count: Int) : LibraryRow {
        override val key: String = "header:" + section.name
        override val contentType: String get() = CONTENT_TYPE_HEADER
        override val fullSpan: Boolean get() = true
    }

    /** Horizontal row of documents ("Continue reading", "Favorites"). */
    data class Shelf(val section: LibrarySection, val items: List<LibraryItem.DocumentEntry>) : LibraryRow {
        override val key: String = "shelf:" + section.name
        override val contentType: String get() = CONTENT_TYPE_SHELF
        override val fullSpan: Boolean get() = true
    }

    data class Cell(val item: LibraryItem) : LibraryRow {
        val ref: ItemRef = item.ref()
        override val key: String = itemKey(ref)
        override val contentType: String = when (ref.kind) {
            ItemKind.FOLDER -> CONTENT_TYPE_FOLDER
            ItemKind.DOCUMENT -> CONTENT_TYPE_DOCUMENT
            ItemKind.NOTE -> CONTENT_TYPE_NOTE
        }
        override val fullSpan: Boolean get() = false
    }

    data class ActiveFilter(val filter: LibraryFilter) : LibraryRow {
        override val key: String get() = "filter"
        override val contentType: String get() = CONTENT_TYPE_FILTER
        override val fullSpan: Boolean get() = true
    }

    data class Empty(val kind: EmptyKind, val query: String = "") : LibraryRow {
        override val key: String get() = "empty"
        override val contentType: String get() = CONTENT_TYPE_EMPTY
        override val fullSpan: Boolean get() = true
    }
}

internal fun itemKey(ref: ItemRef): String = when (ref.kind) {
    ItemKind.FOLDER -> "folder:" + ref.id
    ItemKind.DOCUMENT -> "doc:" + ref.id
    ItemKind.NOTE -> "note:" + ref.id
}

/** One breadcrumb; `folderId == null` is the library root. */
@Immutable
internal data class Crumb(val folderId: String?, val name: String?)

/** One folder of the folder panel (expanded screens); `depth` 0 = directly under the root. */
@Immutable
internal data class FolderPanelRow(val id: String, val name: String, val depth: Int)

@Immutable
internal data class MoveTarget(
    /** `null` = library root. */
    val folderId: String?,
    val name: String?,
    val depth: Int,
    val enabled: Boolean,
)

@Immutable
internal sealed interface LibraryDialog {
    data object NewFolder : LibraryDialog

    data class Rename(val ref: ItemRef, val currentName: String) : LibraryDialog

    data class Move(val items: List<ItemRef>, val targets: List<MoveTarget>) : LibraryDialog

    data class Info(val document: LibraryItem.DocumentEntry) : LibraryDialog
}

/** The single, immutable state of the library screen. */
@Immutable
internal data class LibraryUiState(
    val folderId: String?,
    val loading: Boolean = true,
    val folderName: String? = null,
    val breadcrumbs: List<Crumb> = emptyList(),
    val layout: LibraryLayout = LibraryLayout.GRID,
    val sort: SortOrder = SortOrder.Default,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val searchActive: Boolean = false,
    val rows: List<LibraryRow> = emptyList(),
    val selection: Set<ItemRef> = emptySet(),
    val selectionHasDocuments: Boolean = false,
    val selectionCanFavorite: Boolean = false,
    val selectionAllFavorite: Boolean = false,
    val dialog: LibraryDialog? = null,
    val nowMillis: Long = 0L,
) {
    val isRoot: Boolean get() = folderId == null
    val selectionMode: Boolean get() = selection.isNotEmpty()
}

/** Where a drag of library items can be released. */
@Immutable
internal sealed interface DropTarget {
    data class Folder(val id: String) : DropTarget

    data object Root : DropTarget

    data object Favorites : DropTarget
}

/** Entries of an item's context menu (overflow button, right click). */
internal enum class ItemAction { OPEN, RENAME, MOVE, FAVORITE, UNFAVORITE, SHARE, INFO, DUPLICATE, DELETE }

internal fun LibraryItem.menuActions(): List<ItemAction> = when (this) {
    is LibraryItem.DocumentEntry -> listOf(
        ItemAction.OPEN,
        ItemAction.RENAME,
        ItemAction.MOVE,
        if (favorite) ItemAction.UNFAVORITE else ItemAction.FAVORITE,
        ItemAction.SHARE,
        ItemAction.INFO,
        ItemAction.DUPLICATE,
        ItemAction.DELETE,
    )
    is LibraryItem.FolderEntry -> listOf(ItemAction.OPEN, ItemAction.RENAME, ItemAction.MOVE, ItemAction.DELETE)
    is LibraryItem.NoteEntry -> listOf(
        ItemAction.OPEN,
        ItemAction.RENAME,
        ItemAction.MOVE,
        if (favorite) ItemAction.UNFAVORITE else ItemAction.FAVORITE,
        ItemAction.DELETE,
    )
}

internal fun LibraryItem.isFavorite(): Boolean = when (this) {
    is LibraryItem.DocumentEntry -> favorite
    is LibraryItem.NoteEntry -> favorite
    is LibraryItem.FolderEntry -> false
}

/** User-facing messages; the UI turns them into localized text (never stack traces, §50). */
internal sealed interface LibraryMessage {
    data class Moved(val count: Int) : LibraryMessage

    data object MoveCycle : LibraryMessage

    data class Trashed(val count: Int) : LibraryMessage

    data class Favorited(val count: Int) : LibraryMessage

    data class Unfavorited(val count: Int) : LibraryMessage

    data object Renamed : LibraryMessage

    data object Duplicated : LibraryMessage

    data object ShareNothing : LibraryMessage

    data object ShareFailed : LibraryMessage

    data object Failed : LibraryMessage

    data class Imported(val title: String, val documentId: String) : LibraryMessage

    data class ImportedMany(val count: Int) : LibraryMessage

    data class AlreadyInLibrary(val name: String, val documentId: String, val inTrash: Boolean) : LibraryMessage

    data class AlreadyInLibraryMany(val count: Int) : LibraryMessage

    data class ImportFailed(val name: String, val error: ImportError) : LibraryMessage

    data class ImportFailedMany(val count: Int) : LibraryMessage

    data class Restored(val title: String) : LibraryMessage

    /** A trashed document cannot be restored alone: it went to the trash with its folder. */
    data object RestoreWithFolder : LibraryMessage

    data object DeletedForever : LibraryMessage

    data object TrashEmptied : LibraryMessage
}

/** One-shot effects of the library screen. */
internal sealed interface LibraryEvent {
    data class Message(val message: LibraryMessage, val undoable: Boolean = false) : LibraryEvent

    data class OpenNote(val noteId: String) : LibraryEvent

    data class Share(val files: List<File>) : LibraryEvent
}
