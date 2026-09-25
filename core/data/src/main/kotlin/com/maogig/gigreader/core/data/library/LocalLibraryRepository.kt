package com.maogig.gigreader.core.data.library

import androidx.room.withTransaction
import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.FolderTree
import com.maogig.gigreader.core.common.IdGenerator
import com.maogig.gigreader.core.common.TreeNode
import com.maogig.gigreader.core.common.escapeLike
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.common.undo.UndoableAction
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.SOURCE_MANAGED
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
        val q = escapeLike(query.trim())
        if (q.isEmpty()) return LibrarySearchResults(emptyList(), emptyList(), emptyList())
        return LibrarySearchResults(
            folders = folderDao.searchByName(q, limit).map { it.toItem() },
            documents = documentDao.searchByTitle(q, limit).map { it.toItem() },
            notes = noteDao.search(q, limit).map { it.toItem() },
        )
    }

    override suspend fun createFolder(parentId: String?, name: String): String {
        val now = clock.now()
        val id = ids.newId()
        folderDao.insert(
            FolderEntity(
                id = id, parentId = parentId, name = name.trim().ifEmpty { "New folder" },
                createdAt = now, modifiedAt = now, version = 1, trashedAt = null, trashRootId = null, deletedAt = null,
            ),
        )
        return id
    }

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
            ItemKind.FOLDER -> folderDao.rename(item.id, name, now)
            ItemKind.DOCUMENT -> documentDao.rename(item.id, name, now)
            // Title-only update: a read-modify-write of the body here could overwrite text the note
            // editor autosaved in between.
            ItemKind.NOTE -> noteDao.rename(item.id, name, now)
        }
    }

    override suspend fun move(items: List<ItemRef>, targetFolderId: String?): MoveResult {
        val folderIds = items.filter { it.kind == ItemKind.FOLDER }.map { it.id }
        val documentIds = items.filter { it.kind == ItemKind.DOCUMENT }.map { it.id }
        val noteIds = items.filter { it.kind == ItemKind.NOTE }.map { it.id }
        if (items.isEmpty()) return MoveResult.NothingToMove
        // The cycle check runs inside the write transaction: two concurrent moves (A into B and B into
        // A) must not both pass a check made before either of them wrote.
        val origins = db.withTransaction {
            if (!canMoveFolders(folderIds, targetFolderId)) return@withTransaction null
            val o = Origins(
                folders = folderIds.chunked(CHUNK).flatMap { folderDao.parents(it) },
                documents = documentIds.chunked(CHUNK).flatMap { documentDao.parents(it) },
                notes = noteIds.chunked(CHUNK).flatMap { noteDao.parents(it) },
            )
            moveAll(folderIds, documentIds, noteIds, targetFolderId)
            o
        } ?: return MoveResult.WouldCreateCycle
        val count = items.size
        return MoveResult.Moved(
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

    /** Whether [folderIds] can move into [target] without creating a cycle (reads the current tree). */
    private suspend fun canMoveFolders(folderIds: List<String>, target: String?): Boolean =
        folderIds.isEmpty() ||
            FolderTree(folderDao.tree().map { TreeNode(it.id, it.parentId, it.name) }).canMoveInto(folderIds, target)

    private suspend fun moveAll(folderIds: List<String>, documentIds: List<String>, noteIds: List<String>, target: String?) {
        val now = clock.now()
        folderIds.chunked(CHUNK).forEach { folderDao.move(it, target, now) }
        documentIds.chunked(CHUNK).forEach { documentDao.move(it, target, now) }
        noteIds.chunked(CHUNK).forEach { noteDao.move(it, target, now) }
    }

    private inner class Origins(val folders: List<IdParent>, val documents: List<IdParent>, val notes: List<IdParent>) {
        suspend fun restore() {
            val now = clock.now()
            folders.groupBy { it.parentId }.forEach { (parent, list) ->
                val ids = list.map { it.id }
                // The old parent may have been moved under one of these folders meanwhile.
                if (canMoveFolders(ids, parent)) ids.chunked(CHUNK).forEach { folderDao.move(it, parent, now) }
            }
            documents.groupBy { it.parentId }.forEach { (parent, list) ->
                list.map { it.id }.chunked(CHUNK).forEach { documentDao.move(it, parent, now) }
            }
            notes.groupBy { it.parentId }.forEach { (parent, list) ->
                list.map { it.id }.chunked(CHUNK).forEach { noteDao.move(it, parent, now) }
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
        val filesToDelete = db.withTransaction {
            val now = clock.now()
            val paths = ArrayList<Pair<String, String>>() // documentId to relative path
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
            }
            paths
        }
        // Files go only after the database commit: a crash in between can leave an orphan file
        // (see DocumentFileStore.cleanupOrphans), never a row pointing at a missing file.
        withContext(io) {
            for ((documentId, path) in filesToDelete) {
                runCatching { files.delete(path) }
                covers.delete(documentId)
            }
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

    override suspend fun duplicateDocument(documentId: String): String {
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
            insertDuplicate(source, documentId, newId, relativePath, now)
        } catch (e: Exception) {
            // The copy is already in the library directory; without its row it would be an orphan.
            withContext(NonCancellable + io) { runCatching { files.delete(relativePath) } }
            throw e
        }
        withContext(io) { runCatching { covers.copy(documentId, newId) } }
        return newId
    }

    private suspend fun insertDuplicate(source: DocumentEntity, documentId: String, newId: String, relativePath: String, now: Long) {
        db.withTransaction {
            documentDao.insert(
                source.copy(
                    id = newId,
                    title = "${source.title} (copy)",
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
