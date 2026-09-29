package com.maogig.gigreader.core.data.notes

import androidx.room.withTransaction
import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.IdGenerator
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.SearchKeys
import com.maogig.gigreader.core.database.entity.NoteEntity
import com.maogig.gigreader.core.database.toModel
import com.maogig.gigreader.core.model.Note
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface NotesRepository {
    /**
     * Creates an empty note instantly (no folder needed) and returns its id. A [folderId] that is no
     * longer a live folder means the root.
     */
    suspend fun createNote(folderId: String? = null, linkedDocumentId: String? = null, linkedPage: Int? = null): String

    fun observeNote(id: String): Flow<Note?>

    suspend fun note(id: String): Note?

    suspend fun updateContent(id: String, title: String, body: String)

    /**
     * Removes a note left empty (e.g. "+ New note" then back). A note that never had content is
     * deleted outright; one that had content (and may have been synced) becomes a tombstone. Returns
     * true if the note was removed, false if it is not empty (or is linked to a document page).
     */
    suspend fun discardIfEmpty(id: String): Boolean
}

class LocalNotesRepository(
    private val db: GigReaderDatabase,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Uuid,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : NotesRepository {
    private val dao = db.noteDao()

    override suspend fun createNote(folderId: String?, linkedDocumentId: String?, linkedPage: Int?): String {
        val id = ids.newId()
        db.withTransaction {
            val now = clock.now()
            dao.insert(
                NoteEntity(
                    id = id,
                    // A folder trashed meanwhile would hide the new note: create it at the root instead.
                    folderId = folderId?.takeIf { db.folderDao().isLive(it) },
                    title = "", body = "", searchText = SearchKeys.note("", ""), linkedDocumentId = linkedDocumentId,
                    linkedPage = linkedPage, favorite = false, createdAt = now, modifiedAt = now, version = 1,
                    trashedAt = null, trashRootId = null, deletedAt = null,
                ),
            )
        }
        return id
    }

    override fun observeNote(id: String): Flow<Note?> =
        dao.observeById(id).map { it?.takeIf { e -> e.deletedAt == null }?.toModel() }.distinctUntilChanged()

    override suspend fun note(id: String): Note? = dao.getById(id)?.takeIf { it.deletedAt == null }?.toModel()

    override suspend fun updateContent(id: String, title: String, body: String) {
        // Normalizing a long body is real work: never on the caller's (possibly main) thread.
        val searchText = withContext(compute) { SearchKeys.note(title, body) }
        dao.updateContent(id, title, body, searchText, clock.now())
    }

    override suspend fun discardIfEmpty(id: String): Boolean = db.withTransaction {
        dao.deleteIfEmpty(id) > 0 || dao.tombstoneIfEmpty(id, clock.now()) > 0
    }
}
