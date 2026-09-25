package com.maogig.gigreader.core.data.notes

import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.IdGenerator
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.entity.NoteEntity
import com.maogig.gigreader.core.database.toModel
import com.maogig.gigreader.core.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface NotesRepository {
    /** Creates an empty note instantly (no folder needed) and returns its id. */
    suspend fun createNote(folderId: String? = null, linkedDocumentId: String? = null, linkedPage: Int? = null): String

    fun observeNote(id: String): Flow<Note?>

    suspend fun note(id: String): Note?

    suspend fun updateContent(id: String, title: String, body: String)

    /** Removes a note that was created and left empty (e.g. "+ New note" then back). */
    suspend fun discardIfEmpty(id: String): Boolean
}

class LocalNotesRepository(
    db: GigReaderDatabase,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Uuid,
) : NotesRepository {
    private val dao = db.noteDao()

    override suspend fun createNote(folderId: String?, linkedDocumentId: String?, linkedPage: Int?): String {
        val now = clock.now()
        val id = ids.newId()
        dao.insert(
            NoteEntity(
                id = id, folderId = folderId, title = "", body = "", linkedDocumentId = linkedDocumentId,
                linkedPage = linkedPage, favorite = false, createdAt = now, modifiedAt = now, version = 1,
                trashedAt = null, trashRootId = null, deletedAt = null,
            ),
        )
        return id
    }

    override fun observeNote(id: String): Flow<Note?> =
        dao.observeById(id).map { it?.takeIf { e -> e.deletedAt == null }?.toModel() }.distinctUntilChanged()

    override suspend fun note(id: String): Note? = dao.getById(id)?.takeIf { it.deletedAt == null }?.toModel()

    override suspend fun updateContent(id: String, title: String, body: String) =
        dao.updateContent(id, title, body, clock.now())

    override suspend fun discardIfEmpty(id: String): Boolean = dao.deleteIfEmpty(id) > 0
}
