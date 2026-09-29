package com.maogig.gigreader.feature.library

import android.net.Uri
import com.maogig.gigreader.core.common.FolderTree
import com.maogig.gigreader.core.common.TreeNode
import com.maogig.gigreader.core.common.undo.UndoableAction
import com.maogig.gigreader.core.data.importer.ImportOutcome
import com.maogig.gigreader.core.data.importer.ImportProgress
import com.maogig.gigreader.core.data.library.FolderContents
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.data.library.LibraryRepository
import com.maogig.gigreader.core.data.library.LibrarySearchResults
import com.maogig.gigreader.core.data.library.MoveResult
import com.maogig.gigreader.core.data.library.TrashEntry
import com.maogig.gigreader.core.data.notes.NotesRepository
import com.maogig.gigreader.core.data.settings.SettingsRepository
import com.maogig.gigreader.core.model.AppSettings
import com.maogig.gigreader.core.model.Document
import com.maogig.gigreader.core.model.LibraryItem
import com.maogig.gigreader.core.model.Note
import com.maogig.gigreader.core.model.SortField
import com.maogig.gigreader.core.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal const val DAY = 24L * 60 * 60 * 1000

internal fun doc(
    id: String,
    title: String = id,
    folderId: String? = null,
    favorite: Boolean = false,
    lastOpenedAt: Long? = null,
    annotations: Int = 0,
    created: Long = 0,
    size: Long = 1_000,
    pageCount: Int = 10,
) = LibraryItem.DocumentEntry(
    id = id,
    title = title,
    folderId = folderId,
    fileSize = size,
    pageCount = pageCount,
    lastPage = null,
    maxPageReached = null,
    favorite = favorite,
    annotationCount = annotations,
    lastOpenedAt = lastOpenedAt,
    createdAt = created,
    modifiedAt = created,
)

internal fun folder(id: String, title: String = id, parentId: String? = null, children: Int = 0) =
    LibraryItem.FolderEntry(id = id, title = title, parentId = parentId, childCount = children, createdAt = 0, modifiedAt = 0)

internal fun note(id: String, title: String = id, folderId: String? = null, favorite: Boolean = false, modified: Long = 0) =
    LibraryItem.NoteEntry(
        id = id,
        title = title,
        preview = "",
        folderId = folderId,
        favorite = favorite,
        createdAt = modified,
        modifiedAt = modified,
    )

/** In-memory [LibraryRepository] that records calls. */
internal class FakeLibraryRepository : LibraryRepository {
    private val contents = HashMap<String?, MutableStateFlow<FolderContents>>()
    val continueReading = MutableStateFlow<List<LibraryItem.DocumentEntry>>(emptyList())
    val favorites = MutableStateFlow<List<LibraryItem.DocumentEntry>>(emptyList())
    val recentNotes = MutableStateFlow<List<LibraryItem.NoteEntry>>(emptyList())

    /** Library-wide lists (every folder); `subscriptionCount` tells which ones a screen observes. */
    val allDocuments = MutableStateFlow<List<LibraryItem.DocumentEntry>>(emptyList())
    val allNotes = MutableStateFlow<List<LibraryItem.NoteEntry>>(emptyList())
    val favoriteNotes = MutableStateFlow<List<LibraryItem.NoteEntry>>(emptyList())
    val favoriteLimits = mutableListOf<Int>()
    val openedSinceCalls = mutableListOf<Long>()
    val tree = MutableStateFlow(FolderTree(emptyList()))
    val trash = MutableStateFlow<List<TrashEntry>>(emptyList())
    val documents = HashMap<String, Document>()

    var searchResults = LibrarySearchResults(emptyList(), emptyList(), emptyList())
    val searches = mutableListOf<String>()
    val createdFolders = mutableListOf<Pair<String?, String>>()
    val renames = mutableListOf<Pair<ItemRef, String>>()
    val moves = mutableListOf<Pair<List<ItemRef>, String?>>()
    var nextMoveResult: MoveResult? = null
    val favoriteCalls = mutableListOf<Pair<List<ItemRef>, Boolean>>()
    val trashed = mutableListOf<List<ItemRef>>()
    val restored = mutableListOf<List<ItemRef>>()
    val deletedForever = mutableListOf<List<ItemRef>>()
    val duplicated = mutableListOf<Pair<String, String>>()
    var emptied = 0
    var purged = 0
    val undone = mutableListOf<String>()

    fun setContents(folderId: String?, value: FolderContents) {
        contentsOf(folderId).value = value
    }

    fun setTree(vararg nodes: TreeNode) {
        tree.value = FolderTree(nodes.toList())
    }

    fun contentsOf(folderId: String?) = contents.getOrPut(folderId) { MutableStateFlow(FolderContents.Empty) }

    private fun action(label: String) = object : UndoableAction {
        override val label: String = label

        override suspend fun undo() {
            undone.add(label)
        }

        override suspend fun redo() = Unit
    }

    override fun observeFolder(folderId: String?): Flow<FolderContents> = contentsOf(folderId)

    override fun observeContinueReading(limit: Int): Flow<List<LibraryItem.DocumentEntry>> = continueReading

    override fun observeFavorites(limit: Int): Flow<List<LibraryItem.DocumentEntry>> {
        favoriteLimits.add(limit)
        return favorites
    }

    override fun observeRecentNotes(limit: Int): Flow<List<LibraryItem.NoteEntry>> = recentNotes

    override fun observeAllNotes(): Flow<List<LibraryItem.NoteEntry>> = allNotes

    override fun observeAllDocuments(): Flow<List<LibraryItem.DocumentEntry>> = allDocuments

    override fun observeFavoriteNotes(): Flow<List<LibraryItem.NoteEntry>> = favoriteNotes

    /** Filters [allDocuments] like the real query (opened at or after [sinceMillis], newest first). */
    override fun observeOpenedSince(sinceMillis: Long): Flow<List<LibraryItem.DocumentEntry>> {
        openedSinceCalls.add(sinceMillis)
        return allDocuments.map { documents ->
            documents.filter { (it.lastOpenedAt ?: Long.MIN_VALUE) >= sinceMillis }.sortedByDescending { it.lastOpenedAt }
        }
    }

    override fun observeFolderTree(): Flow<FolderTree> = tree

    override fun observeTrash(): Flow<List<TrashEntry>> = trash

    override fun observeDocument(id: String): Flow<Document?> = flowOf(documents[id])

    override suspend fun document(id: String): Document? = documents[id]

    override suspend fun search(query: String, limit: Int): LibrarySearchResults {
        searches.add(query)
        return searchResults
    }

    override suspend fun createFolder(parentId: String?, name: String): String {
        createdFolders.add(parentId to name)
        return "new-folder"
    }

    override suspend fun rename(item: ItemRef, newName: String): UndoableAction? {
        renames.add(item to newName)
        return action("rename")
    }

    override suspend fun move(items: List<ItemRef>, targetFolderId: String?): MoveResult {
        moves.add(items to targetFolderId)
        return nextMoveResult ?: MoveResult.Moved(action("move"))
    }

    override suspend fun setFavorite(items: List<ItemRef>, favorite: Boolean): UndoableAction? {
        favoriteCalls.add(items to favorite)
        return if (items.isEmpty()) null else action("favorite")
    }

    override suspend fun moveToTrash(items: List<ItemRef>): UndoableAction? {
        trashed.add(items)
        return action("trash")
    }

    override suspend fun restoreFromTrash(items: List<ItemRef>) {
        restored.add(items)
    }

    override suspend fun deleteForever(items: List<ItemRef>) {
        deletedForever.add(items)
    }

    override suspend fun emptyTrash() {
        emptied++
    }

    var purgeFailure: Exception? = null

    override suspend fun purgeExpiredTrash(maxAgeMillis: Long) {
        purgeFailure?.let { throw it }
        purged++
    }

    override suspend fun duplicateDocument(documentId: String, copyTitle: String): String {
        duplicated.add(documentId to copyTitle)
        return "$documentId-copy"
    }
}

internal class FakeNotesRepository : NotesRepository {
    val created = mutableListOf<String?>()

    override suspend fun createNote(folderId: String?, linkedDocumentId: String?, linkedPage: Int?): String {
        created.add(folderId)
        return "note-${created.size}"
    }

    override fun observeNote(id: String): Flow<Note?> = flowOf(null)

    override suspend fun note(id: String): Note? = null

    override suspend fun updateContent(id: String, title: String, body: String) = Unit

    override suspend fun discardIfEmpty(id: String): Boolean = false
}

internal class FakeSettingsRepository(
    initial: AppSettings = AppSettings(librarySort = SortOrder(SortField.NAME, ascending = true)),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    val current: AppSettings get() = state.value

    override val settings: Flow<AppSettings> = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}

internal class FakeImportGateway : ImportGateway {
    override val progress: StateFlow<ImportProgress> = MutableStateFlow(ImportProgress())
    override val outcomes = MutableSharedFlow<ImportOutcome>()
    val requests = mutableListOf<Pair<List<Uri>, String?>>()
    var cancelled = 0

    override fun import(uris: List<Uri>, folderId: String?) {
        requests.add(uris to folderId)
    }

    override fun cancelAll() {
        cancelled++
    }
}
