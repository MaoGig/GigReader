package com.maogig.gigreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maogig.gigreader.core.database.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

private const val NOTE_ROW = """
    id, title, substr(body, 1, 160) AS preview, folder_id AS folderId, favorite,
    created_at AS createdAt, modified_at AS modifiedAt
"""

@Dao
interface NoteDao {
    @Query("SELECT $NOTE_ROW FROM notes WHERE folder_id IS :folderId AND trashed_at IS NULL AND deleted_at IS NULL")
    fun observeInFolder(folderId: String?): Flow<List<NoteRow>>

    @Query("SELECT $NOTE_ROW FROM notes WHERE trashed_at IS NULL AND deleted_at IS NULL ORDER BY modified_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NoteRow>>

    @Query(
        """
        SELECT $NOTE_ROW FROM notes
        WHERE (title LIKE '%' || :query || '%' ESCAPE '\' OR body LIKE '%' || :query || '%' ESCAPE '\')
          AND trashed_at IS NULL AND deleted_at IS NULL
        ORDER BY modified_at DESC LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<NoteRow>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT id, folder_id AS parentId FROM notes WHERE id IN (:ids)")
    suspend fun parents(ids: List<String>): List<IdParent>

    @Query("SELECT id, favorite AS flag FROM notes WHERE id IN (:ids)")
    suspend fun favorites(ids: List<String>): List<IdFlag>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity)

    @Query("UPDATE notes SET title = :title, body = :body, modified_at = :now, version = version + 1 WHERE id = :id")
    suspend fun updateContent(id: String, title: String, body: String, now: Long)

    @Query("UPDATE notes SET folder_id = :folderId, modified_at = :now, version = version + 1 WHERE id IN (:ids)")
    suspend fun move(ids: List<String>, folderId: String?, now: Long)

    @Query("UPDATE notes SET favorite = :favorite, modified_at = :now, version = version + 1 WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<String>, favorite: Boolean, now: Long)

    @Query("SELECT id FROM notes WHERE folder_id IN (:folderIds) AND trashed_at IS NULL AND deleted_at IS NULL")
    suspend fun liveIdsInFolders(folderIds: List<String>): List<String>

    @Query(
        """
        UPDATE notes SET trashed_at = :now, trash_root_id = :rootId, modified_at = :now, version = version + 1
        WHERE id IN (:ids) AND trashed_at IS NULL AND deleted_at IS NULL
        """,
    )
    suspend fun trash(ids: List<String>, rootId: String, now: Long)

    @Query(
        """
        UPDATE notes SET trashed_at = :now, trash_root_id = id, modified_at = :now, version = version + 1
        WHERE id IN (:ids) AND trashed_at IS NULL AND deleted_at IS NULL
        """,
    )
    suspend fun trashEach(ids: List<String>, now: Long)

    @Query(
        """
        UPDATE notes SET trashed_at = NULL, trash_root_id = NULL, modified_at = :now, version = version + 1
        WHERE trash_root_id = :rootId
        """,
    )
    suspend fun restore(rootId: String, now: Long)

    @Query("SELECT id FROM notes WHERE trash_root_id = :rootId")
    suspend fun idsTrashedWith(rootId: String): List<String>

    @Query(
        """
        SELECT id, title, 'note' AS kind, trashed_at AS trashedAt FROM notes
        WHERE trashed_at IS NOT NULL AND trash_root_id = id AND deleted_at IS NULL
        """,
    )
    fun observeTrashRoots(): Flow<List<TrashRow>>

    @Query(
        """
        UPDATE notes SET deleted_at = :now, title = '', body = '', trashed_at = NULL, trash_root_id = NULL,
          modified_at = :now, version = version + 1
        WHERE id IN (:ids)
        """,
    )
    suspend fun tombstone(ids: List<String>, now: Long)

    @Query("DELETE FROM notes WHERE id = :id AND title = '' AND body = '' AND linked_document_id IS NULL")
    suspend fun deleteIfEmpty(id: String): Int
}
