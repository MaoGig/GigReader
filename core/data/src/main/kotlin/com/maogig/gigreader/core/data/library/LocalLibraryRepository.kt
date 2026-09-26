package com.maogig.gigreader.core.data.library

import androidx.room.withTransaction
import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.FolderTree
import com.maogig.gigreader.core.common.IdGenerator
import com.maogig.gigreader.core.common.TreeNode
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.common.undo.UndoableAction
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.SOURCE_MANAGED
import com.maogig.gigreader.core.database.SearchKeys
import com.maogig.gigreader.core.database.dao.IdFlag
import com.maogig.gigreader.core.database.dao.IdParent
import com.maogig.gigreader.core.database.dao.TrashRow
import com.maogig.gigreader.core.database.entity.DocumentEntity
import com.maogig.gigreader.core.database.entity.FolderEntity
import com.maogig.gigreader.core.database.toItem
import com.maogig.gigreader.core.database.toModel
import com.maogig.gigreader.core.model.Document
import com.maogig.gigreader.core.model.LibraryItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** SQLite limits bound variables per statement; stay well below the lowest limit (999). */
private const val CHUNK = 500

class LocalLibraryRepository(
    private val db: GigReaderDatabase,
    private val files: DocumentFileStore,
    private val covers: CoverStore,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Uuid,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : LibraryRepository {
    private val folderDao = db.folderDao()
    private val documentDao = db.documentDao()
    private val noteDao = db.noteDao()
    private val annotationDao = db.annotationDao()
    private val bookmarkDao = db.bookmarkDao()
    private val pageMetricsDao = db.pageMetricsDao()
    private val readingPositionDao = db.readingPositionDao()

    override fun observeFolder(folderId: String?): Flow<FolderContents> = combine(
        folderDao.observeChildren(folderId),
        documentDao.observeInFolder(folderId),
        noteDao.observeInFolder(folderId),
    ) { folders, documents, notes ->
        FolderContents(folders.map { it.toItem() }, documents.map { it.toItem() }, notes.map { it.toItem() })
    }.distinctUntilChanged().flowOn(compute)

    override fun observeContinueReading(limit: Int): Flow<List<LibraryItem.DocumentEntry>> =
        documentDao.observeRecent(limit).map { rows -> rows.map { it.toItem() } }.distinctUntilChanged()

    override fun observeFavorites(limit: Int): Flow<List<LibraryItem.DocumentEntry>> =
        documentDao.observeFavorites(limit).map { rows -> rows.map { it.toItem() } }.distinctUntilChanged()

    override fun observeRecentNotes(limit: Int): Flow<List<LibraryItem.NoteEntry>> =
        noteDao.observeRecent(limit).map { rows -> rows.map { it.toItem() } }.distinctUntilChanged()

    // Library-wide lists can hold thousands of rows: mapped off the main thread like observeFolder.
    override fun observeAllNotes(): Flow<List<LibraryItem.NoteEntry>> =
        noteDao.observeAll().map { rows -> rows.map { it.toItem() } }.distinctUntilChanged().flowOn(compute)

    override fun observeAllDocuments(): Flow<List<LibraryItem.DocumentEntry>> =
        documentDao.observeAll().map { rows -> rows.map { it.toItem() } }.distinctUntilChanged().flowOn(compute)

    override fun observeFavoriteNotes(): Flow<List<LibraryItem.NoteEntry>> =
        noteDao.observeFavorites().map { rows -> rows.map { it.toItem() } }.distinctUntilChanged().flowOn(compute)

    override fun observeOpenedSince(sinceMillis: Long): Flow<List<LibraryItem.DocumentEntry>> =
        documentDao.observeOpenedSince(sinceMillis).map { rows -> rows.map { it.toItem() } }
            .distinctUntilChanged().flowOn(compute)

    override fun observeFolderTree(): Flow<FolderTree> =
        folderDao.observeTree()
            .distinctUntilChanged()
            .map { nodes -> FolderTree(nodes.map { TreeNode(it.id, it.parentId, it.name) }) }
            .flowOn(compute)

    override fun observeTrash(): Flow<List<TrashEntry>> = combine(
        folderDao.observeTrashRoots(),
        documentDao.observeTrashRoots(),
        noteDao.observeTrashRoots(),
    ) { folders, documents, notes ->
        (folders + documents + notes).map { it.toEntry() }.sortedByDescending { it.trashedAt }
    }

    override fun observeDocument(id: String): Flow<Document?> =
        documentDao.observeById(id).map { it?.takeIf { e -> e.deletedAt == null }?.toModel() }.distinctUntilChanged()

    override suspend fun document(id: String): Document? =
        documentDao.getById(id)?.takeIf { it.deletedAt == null }?.toModel()

    override suspend fun search(query: String, limit: Int): LibrarySearchResults {
        // Matched against the normalized search_* columns: case- and accent-insensitive for pt-BR too.
        val q = SearchKeys.likeQuery(query)
        if (q.isEmpty()) return LibrarySearchResults(emptyList(), emptyList(), emptyList())
        return LibrarySearchResults(
            folders = folderDao.searchByName(q, limit).map { it.toItem() },
            documents = documentDao.searchByTitle(q, limit).map { it.toItem() },
            notes = noteDao.search(q, limit).map { it.toItem() },
        )
    }

    override suspend fun createFolder(parentId: String?, name: String): String {
        val id = ids.newId()
        val folderName = name.trim().ifEmpty { "New folder" }
        db.withTransaction {
            val now = clock.now()
            folderDao.insert(
                FolderEntity(
                    id = id, parentId = liveFolderOrRoot(parentId), name = folderName, searchName = SearchKeys.of(folderName),
                    createdAt = now, modifiedAt = now, version = 1, trashedAt = null, trashRootId = null, deletedAt = null,
                ),
            )
        }
        return id
    }

    /**
     * [folderId] if it is a live folder, otherwise the root (null). A live item under a trashed or
     * deleted folder would show up nowhere, so every write that places an item checks its target with
     * this inside its transaction.
     */
    private suspend fun liveFolderOrRoot(folderId: String?): String? = folderId?.takeIf { folderDao.isLive(it) }

    override suspend fun rename(item: ItemRef, newName: String): UndoableAction? {
        val name = newName.trim()
        if (name.isEmpty()) return null
        val previous: String = when (item.kind) {
            ItemKind.FOLDER -> folderDao.getById(item.id)?.name
            ItemKind.DOCUMENT -> documentDao.getById(item.id)?.title
            ItemKind.NOTE -> noteDao.getById(item.id)?.title
        } ?: return null
        if (previous == name) return null
        applyRename(item, name)
        return object : UndoableAction {
            override val label = "Renamed"
            override suspend fun undo() = applyRename(item, previous)
            override suspend fun redo() = applyRename(item, name)
        }
    }

    private suspend fun applyRename(item: ItemRef, name: String) {
        val now = clock.now()
        when (item.kind) {
            ItemKind.FOLDER -> folderDao.rename(item.id, name, SearchKeys.of(name), now)
            ItemKind.DOCUMENT -> documentDao.rename(item.id, name, SearchKeys.of(name), now)
            // Title-only update: rewriting the body here could overwrite text the note editor
            // autosaved in between. The body is only read to index it, inside the write transaction,
            // so no autosave can land between the read and the write.
            ItemKind.NOTE -> db.withTransaction {
                val body = noteDao.getById(item.id)?.body ?: return@withTransaction
                noteDao.rename(item.id, name, SearchKeys.note(name, body), now)
            }
        }
    }

    override suspend fun move(items: List<ItemRef>, targetFolderId: String?): MoveResult {
        val folderIds = items.filter { it.kind == ItemKind.FOLDER }.map { it.id }
        val documentIds = items.filter { it.kind == ItemKind.DOCUMENT }.map { it.id }
        val noteIds = items.filter { it.kind == ItemKind.NOTE }.map { it.id }
        if (items.isEmpty()) return MoveResult.NothingToMove
        // The cycle and target checks run inside the write transaction: two concurrent moves (A into B
        // and B into A) must not both pass a check made before either of them wrote, and the target
        // must not be trashed between the check and the move.
        return db.withTransaction<MoveResult> {
            if (!canMoveFolders(folderIds, targetFolderId)) return@withTransaction MoveResult.WouldCreateCycle
            val origins = Origins(
                folders = folderIds.chunked(CHUNK).flatMap { folderDao.parents(it) },
                documents = documentIds.chunked(CHUNK).flatMap { documentDao.parents(it) },
                notes = noteIds.chunked(CHUNK).flatMap { noteDao.parents(it) },
            )
            // A target trashed meanwhile (e.g. from another window): the items stay where they are.
            if (!moveAll(folderIds, documentIds, noteIds, targetFolderId)) return@withTransaction MoveResult.NothingToMove
            val count = items.size
            MoveResult.Moved(
                object : UndoableAction {
                    override val label = if (count == 1) "Moved 1 item" else "Moved $count items"
                    override suspend fun undo() = db.withTransaction { origins.restore() }
                    override suspend fun redo() = db.withTransaction {
                        // Other screens may have reorganized folders since; never create a cycle on redo.
                        if (canMoveFolders(folderIds, targetFolderId)) moveAll(folderIds, documentIds, noteIds, targetFolderId)
                    }
                },
            )
        }
    }

    /** Whether [folderIds] can move into [target] without creating a cycle (reads the current tree). */
    private suspend fun canMoveFolders(folderIds: List<String>, target: String?): Boolean =
        folderIds.isEmpty() ||
            FolderTree(folderDao.tree().map { TreeNode(it.id, it.parentId, it.name) }).canMoveInto(folderIds, target)

    /**
     * Moves the items into [target]. Returns false, moving nothing, if [target] is no longer a live
     * folder: the items stay where they are, still visible. Call inside a transaction.
     */
    private suspend fun moveAll(folderIds: List<String>, documentIds: List<String>, noteIds: List<String>, target: String?): Boolean {
        if (target != null && !folderDao.isLive(target)) return false
        val now = clock.now()
        folderIds.chunked(CHUNK).forEach { folderDao.move(it, target, now) }
        documentIds.chunked(CHUNK).forEach { documentDao.move(it, target, now) }
        noteIds.chunked(CHUNK).forEach { noteDao.move(it, target, now) }
        return true
    }

    private inner class Origins(val folders: List<IdParent>, val documents: List<IdParent>, val notes: List<IdParent>) {
        /** Puts items back into their old folders; those trashed or deleted since mean the root. */
        suspend fun restore() {
            val now = clock.now()
            folders.groupBy { it.parentId }.forEach { (parent, list) ->
                val ids = list.map { it.id }
                val target = liveFolderOrRoot(parent)
                // The old parent may have been moved under one of these folders meanwhile.
                if (canMoveFolders(ids, target)) ids.chunked(CHUNK).forEach { folderDao.move(it, target, now) }
            }
            documents.groupBy { it.parentId }.forEach { (parent, list) ->
                val target = liveFolderOrRoot(parent)
                list.map { it.id }.chunked(CHUNK).forEach { documentDao.move(it, target, now) }
            }
            notes.groupBy { it.parentId }.forEach { (parent, list) ->
                val target = liveFolderOrRoot(parent)
                list.map { it.id }.chunked(CHUNK).forEach { noteDao.move(it, target, now) }
            }
        }
    }

    override suspend fun setFavorite(items: List<ItemRef>, favorite: Boolean): UndoableAction? {
        val documentIds = items.filter { it.kind == ItemKind.DOCUMENT }.map { it.id }
        val noteIds = items.filter { it.kind == ItemKind.NOTE }.map { it.id }
        if (documentIds.isEmpty() && noteIds.isEmpty()) return null
        val (previousDocs, previousNotes) = db.withTransaction {
            val d = documentIds.chunked(CHUNK).flatMap { documentDao.favorites(it) }
            val n = noteIds.chunked(CHUNK).flatMap { noteDao.favorites(it) }
            applyFavorite(documentIds, noteIds, favorite)
            d to n
        }
        return object : UndoableAction {
            override val label = if (favorite) "Added to favorites" else "Removed from favorites"
            override suspend fun undo() = db.withTransaction {
                restoreFlags(previousDocs) { ids, flag -> applyFavorite(ids, emptyList(), flag) }
                restoreFlags(previousNotes) { ids, flag -> applyFavorite(emptyList(), ids, flag) }
            }
            override suspend fun redo() = db.withTransaction { applyFavorite(documentIds, noteIds, favorite) }
        }
    }

    private suspend fun applyFavorite(documentIds: List<String>, noteIds: List<String>, favorite: Boolean) {
        val now = clock.now()
        documentIds.chunked(CHUNK).forEach { documentDao.setFavorite(it, favorite, now) }
        noteIds.chunked(CHUNK).forEach { noteDao.setFavorite(it, favorite, now) }
    }

    private suspend fun restoreFlags(flags: List<IdFlag>, apply: suspend (List<String>, Boolean) -> Unit) {
        flags.groupBy { it.flag }.forEach { (flag, list) -> apply(list.map { it.id }, flag) }
    }

    override suspend fun moveToTrash(items: List<ItemRef>): UndoableAction? {
        if (items.isEmpty()) return null
        db.withTransaction { trashAll(items) }
        val count = items.size
        return object : UndoableAction {
            override val label = if (count == 1) "Moved to trash" else "$count items moved to trash"
            override suspend fun undo() = restoreFromTrash(items)
            override suspend fun redo() = db.withTransaction { trashAll(items) }
        }
    }

    private suspend fun trashAll(items: List<ItemRef>) {
        val now = clock.now()
        for (folder in items.filter { it.kind == ItemKind.FOLDER }) {
            val subtree = folderDao.subtreeIds(folder.id)
            subtree.chunked(CHUNK).forEach { chunk ->
                folderDao.trash(chunk, folder.id, now)
                documentDao.liveIdsInFolders(chunk).chunked(CHUNK).forEach { documentDao.trash(it, folder.id, now) }
                noteDao.liveIdsInFolders(chunk).chunked(CHUNK).forEach { noteDao.trash(it, folder.id, now) }
            }
        }
        items.filter { it.kind == ItemKind.DOCUMENT }.map { it.id }.chunked(CHUNK).forEach { documentDao.trashEach(it, now) }
        items.filter { it.kind == ItemKind.NOTE }.map { it.id }.chunked(CHUNK).forEach { noteDao.trashEach(it, now) }
    }

    override suspend fun restoreFromTrash(items: List<ItemRef>) {
        db.withTransaction {
            val now = clock.now()
            for (item in items) {
                folderDao.restore(item.id, now)
                documentDao.restore(item.id, now)
                noteDao.restore(item.id, now)
                reattachIfParentGone(item, now)
            }
        }
    }

    /** A restored root whose parent folder is itself trashed or deleted goes back to the root. */
    private suspend fun reattachIfParentGone(item: ItemRef, now: Long) {
        val parentId = when (item.kind) {
            ItemKind.FOLDER -> folderDao.getById(item.id)?.parentId
            ItemKind.DOCUMENT -> documentDao.getById(item.id)?.folderId
            ItemKind.NOTE -> noteDao.getById(item.id)?.folderId
        } ?: return
        val parent = folderDao.getById(parentId)
        if (parent != null && parent.trashedAt == null && parent.deletedAt == null) return
        when (item.kind) {
            ItemKind.FOLDER -> folderDao.move(listOf(item.id), null, now)
            ItemKind.DOCUMENT -> documentDao.move(listOf(item.id), null, now)
            ItemKind.NOTE -> noteDao.move(listOf(item.id), null, now)
        }
    }

    override suspend fun deleteForever(items: List<ItemRef>) {
        if (items.isEmpty()) return
        // The user asked to free this space. Once started, cancelling the caller (leaving the Trash
        // screen) must not skip the file deletion: a cancelled coroutine would even lose the list of
        // files of an already committed transaction, because withTransaction rethrows the
        // cancellation when it resumes. So the whole operation is non-cancellable.
        withContext(NonCancellable) { deleteForeverNow(items) }
    }

    private suspend fun deleteForeverNow(items: List<ItemRef>) {
        val filesToDelete = db.withTransaction {
            val now = clock.now()
            val paths = ArrayList<Pair<String, String>>() // documentId to relative path
            val deletedFolders = ArrayList<String>()
            for (root in items) {
                val folderIds = folderDao.idsTrashedWith(root.id)
                val documentIds = documentDao.idsTrashedWith(root.id)
                val noteIds = noteDao.idsTrashedWith(root.id)
                documentIds.chunked(CHUNK).forEach { chunk ->
                    documentDao.getByIds(chunk)
                        .filter { it.sourceKind == SOURCE_MANAGED && it.sourcePath.isNotEmpty() }
                        .forEach { paths.add(it.id to it.sourcePath) }
                    annotationDao.deleteForDocuments(chunk)
                    bookmarkDao.deleteForDocuments(chunk)
                    // Derived/device-local rows would otherwise outlive the tombstone forever.
                    pageMetricsDao.deleteForDocuments(chunk)
                    readingPositionDao.deleteForDocuments(chunk)
                    documentDao.tombstone(chunk, now)
                }
                noteIds.chunked(CHUNK).forEach { noteDao.tombstone(it, now) }
                folderIds.chunked(CHUNK).forEach { folderDao.tombstone(it, now) }
                deletedFolders.addAll(folderIds)
            }
            reattachLiveChildren(deletedFolders, now)
            paths
        }
        // Files go only after the database commit: a crash in between can leave an orphan file
        // (see sweepLeftovers), never a row pointing at a missing file.
        withContext(NonCancellable + io) {
            for ((documentId, path) in filesToDelete) {
                runCatching { files.delete(path) }
                runCatching { covers.delete(documentId) }
            }
            // Also frees what earlier crashes or cancelled deletions left behind.
            sweepLeftovers(db, files, covers, clock.now())
        }
    }

    /**
     * Moves live items whose parent is one of [folderIds] (just turned into tombstones) to the root.
     * They were not trashed with that folder (e.g. written into it after it was trashed), so without
     * this they would stay live forever under a folder that no listing shows.
     */
    private suspend fun reattachLiveChildren(folderIds: List<String>, now: Long) {
        folderIds.chunked(CHUNK).forEach { chunk ->
            folderDao.liveIdsWithParents(chunk).chunked(CHUNK).forEach { folderDao.move(it, null, now) }
            documentDao.liveIdsInFolders(chunk).chunked(CHUNK).forEach { documentDao.move(it, null, now) }
            noteDao.liveIdsInFolders(chunk).chunked(CHUNK).forEach { noteDao.move(it, null, now) }
        }
    }

    override suspend fun emptyTrash() {
        deleteForever(observeTrash().first().map { it.ref })
    }

    override suspend fun purgeExpiredTrash(maxAgeMillis: Long) {
        val cutoff = clock.now() - maxAgeMillis
        val expired = observeTrash().first().filter { it.trashedAt < cutoff }.map { it.ref }
        if (expired.isNotEmpty()) deleteForever(expired)
    }

    override suspend fun duplicateDocument(documentId: String, copyTitle: String): String {
        // Tombstones have an empty source path (fileFor("") would throw), so treat them as missing.
        val source = documentDao.getById(documentId)?.takeIf { it.deletedAt == null } ?: error("document not found")
        require(source.sourceKind == SOURCE_MANAGED) { "only library documents can be duplicated" }
        val newId = ids.newId()
        val relativePath = withContext(io) {
            val incoming = files.newIncomingFile()
            try {
                files.fileFor(source.sourcePath).inputStream().use { input ->
                    files.openForWrite(incoming).use { output -> input.copyTo(output, 256 * 1024) }
                }
                files.commit(incoming, newId)
            } catch (e: Exception) {
                incoming.delete()
                throw e
            }
        }
        val now = clock.now()
        try {
            insertDuplicate(source, documentId, newId, relativePath, copyTitle.trim().ifEmpty { source.title }, now)
        } catch (e: Exception) {
            // The copy is already in the library directory; without its row it would be an orphan.
            withContext(NonCancellable + io) { runCatching { files.delete(relativePath) } }
            throw e
        }
        withContext(io) { runCatching { covers.copy(documentId, newId) } }
        return newId
    }

    private suspend fun insertDuplicate(
        source: DocumentEntity,
        documentId: String,
        newId: String,
        relativePath: String,
        title: String,
        now: Long,
    ) {
        db.withTransaction {
            documentDao.insert(
                source.copy(
                    id = newId,
                    // The source may have been trashed with its folder meanwhile: never go live under it.
                    folderId = liveFolderOrRoot(source.folderId),
                    title = title,
                    searchTitle = SearchKeys.of(title),
                    sourcePath = relativePath,
                    createdAt = now,
                    modifiedAt = now,
                    lastOpenedAt = null,
                    version = 1,
                    favorite = false,
                    trashedAt = null,
                    trashRootId = null,
                    deletedAt = null,
                ),
            )
            annotationDao.insertAll(
                annotationDao.allForDocument(documentId).map {
                    it.copy(id = ids.newId(), documentId = newId, createdAt = now, modifiedAt = now, version = 1)
                },
            )
            bookmarkDao.insertAll(
                bookmarkDao.allForDocument(documentId).map {
                    it.copy(id = ids.newId(), documentId = newId, createdAt = now, modifiedAt = now, version = 1)
                },
            )
            pageMetricsDao.get(documentId)?.let { pageMetricsDao.upsert(it.copy(documentId = newId)) }
        }
    }

    private fun TrashRow.toEntry() = TrashEntry(
        ref = ItemRef(
            id,
            when (kind) {
                "folder" -> ItemKind.FOLDER
                "note" -> ItemKind.NOTE
                else -> ItemKind.DOCUMENT
            },
        ),
        title = title,
        trashedAt = trashedAt,
    )
}
