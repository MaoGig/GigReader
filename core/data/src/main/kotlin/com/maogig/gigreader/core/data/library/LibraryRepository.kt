package com.maogig.gigreader.core.data.library

import com.maogig.gigreader.core.common.FolderTree
import com.maogig.gigreader.core.common.undo.UndoableAction
import com.maogig.gigreader.core.model.Document
import com.maogig.gigreader.core.model.LibraryItem
import kotlinx.coroutines.flow.Flow

enum class ItemKind { FOLDER, DOCUMENT, NOTE }

data class ItemRef(val id: String, val kind: ItemKind)

fun LibraryItem.ref(): ItemRef = when (this) {
    is LibraryItem.FolderEntry -> ItemRef(id, ItemKind.FOLDER)
    is LibraryItem.DocumentEntry -> ItemRef(id, ItemKind.DOCUMENT)
    is LibraryItem.NoteEntry -> ItemRef(id, ItemKind.NOTE)
}

/** Everything directly inside one folder (or the root), unsorted: the UI applies sort/filter. */
data class FolderContents(
    val folders: List<LibraryItem.FolderEntry>,
    val documents: List<LibraryItem.DocumentEntry>,
    val notes: List<LibraryItem.NoteEntry>,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && documents.isEmpty() && notes.isEmpty()

    companion object {
        val Empty = FolderContents(emptyList(), emptyList(), emptyList())
    }
}

data class LibrarySearchResults(
    val folders: List<LibraryItem.FolderEntry>,
    val documents: List<LibraryItem.DocumentEntry>,
    val notes: List<LibraryItem.NoteEntry>,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && documents.isEmpty() && notes.isEmpty()
}

data class TrashEntry(val ref: ItemRef, val title: String, val trashedAt: Long)

sealed interface MoveResult {
    data class Moved(val undo: UndoableAction) : MoveResult

    /** A folder cannot be moved into itself or one of its descendants. */
    data object WouldCreateCycle : MoveResult

    data object NothingToMove : MoveResult
}

/**
 * Library organization: folders, documents and notes, favorites, trash. Implementations are
 * local-first (Room); a future sync engine observes the same tables instead of hooking into the UI.
 *
 * Every mutating method that the user can regret returns an [UndoableAction] describing its inverse;
 * callers record it in the screen's [com.maogig.gigreader.core.common.undo.UndoManager].
 */
interface LibraryRepository {
    fun observeFolder(folderId: String?): Flow<FolderContents>

    fun observeContinueReading(limit: Int = 12): Flow<List<LibraryItem.DocumentEntry>>

    fun observeFavorites(limit: Int = 50): Flow<List<LibraryItem.DocumentEntry>>

    fun observeRecentNotes(limit: Int = 12): Flow<List<LibraryItem.NoteEntry>>

    /** Every live note in any folder, most recently modified first (library-wide "Notes" view). */
    fun observeAllNotes(): Flow<List<LibraryItem.NoteEntry>>

    /** Every live, non-archived document in any folder (library-wide "PDFs" view), unsorted. */
    fun observeAllDocuments(): Flow<List<LibraryItem.DocumentEntry>>

    /** Live favorite notes in any folder, most recently modified first (library-wide "Favorites"). */
    fun observeFavoriteNotes(): Flow<List<LibraryItem.NoteEntry>>

    /**
     * Live, non-archived documents in any folder opened at or after [sinceMillis] (epoch ms), most
     * recently opened first (library-wide "Recently opened").
     */
    fun observeOpenedSince(sinceMillis: Long): Flow<List<LibraryItem.DocumentEntry>>

    fun observeFolderTree(): Flow<FolderTree>

    fun observeTrash(): Flow<List<TrashEntry>>

    fun observeDocument(id: String): Flow<Document?>

    suspend fun document(id: String): Document?

    /** Name search ignoring case and accents ("relatorio" finds "RELATÓRIO"); note bodies included. */
    suspend fun search(query: String, limit: Int = 50): LibrarySearchResults

    /** Returns the new folder id. A [parentId] that is no longer a live folder means the root. */
    suspend fun createFolder(parentId: String?, name: String): String

    suspend fun rename(item: ItemRef, newName: String): UndoableAction?

    /**
     * Moves [items] into [targetFolderId] (null = root). Returns [MoveResult.NothingToMove] (and moves
     * nothing) if the target is no longer a live folder, e.g. trashed meanwhile from another window.
     * Undo puts items back where they were, or at the root if that folder is gone.
     */
    suspend fun move(items: List<ItemRef>, targetFolderId: String?): MoveResult

    suspend fun setFavorite(items: List<ItemRef>, favorite: Boolean): UndoableAction?

    suspend fun moveToTrash(items: List<ItemRef>): UndoableAction?

    suspend fun restoreFromTrash(items: List<ItemRef>)

    /**
     * Permanently deletes the given trashed roots (files and covers included). Not undoable. Once
     * started it runs to completion even if the caller is cancelled (the user asked to free the
     * space). Live items still inside a deleted folder are moved to the root.
     */
    suspend fun deleteForever(items: List<ItemRef>)

    suspend fun emptyTrash()

    /** Purges items that have been in the trash longer than [maxAgeMillis]. */
    suspend fun purgeExpiredTrash(maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1000)

    /**
     * Creates an independent copy of a document (file, metadata and annotations) titled [copyTitle]
     * (localized by the caller, e.g. "Paper (cópia)"; blank keeps the original title). Returns the
     * new document id.
     */
    suspend fun duplicateDocument(documentId: String, copyTitle: String): String
}
