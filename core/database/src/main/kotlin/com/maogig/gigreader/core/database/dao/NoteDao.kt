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

    /** Every live note in any folder, most recently modified first (modified_at index). */
    @Query("SELECT $NOTE_ROW FROM notes WHERE trashed_at IS NULL AND deleted_at IS NULL ORDER BY modified_at DESC")
    fun observeAll(): Flow<List<NoteRow>>

    /** Live favorite notes in any folder, most recently modified first (favorite index). */
    @Query(
        """
        SELECT $NOTE_ROW FROM notes WHERE favorite = 1 AND trashed_at IS NULL AND deleted_at IS NULL
        ORDER BY modified_at DESC
        """,
    )
    fun observeFavorites(): Flow<List<NoteRow>>

    /** [query] is a normalized, LIKE-escaped pattern (SearchKeys.likeQuery); title and body are both searched. */
    @Query(
        """
        SELECT $NOTE_ROW FROM notes
        WHERE search_text LIKE '%' || :query || '%' ESCAPE '\' AND trashed_at IS NULL AND deleted_at IS NULL
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

    /** [searchText] is SearchKeys.note([title], [body]). */
    @Query(
        """
        UPDATE notes SET title = :title, body = :body, search_text = :searchText, modified_at = :now,
          version = version + 1
        WHERE id = :id
        """,
    )
    suspend fun updateContent(id: String, title: String, body: String, searchText: String, now: Long)

    /**
     * Title only: never rewrites the body, so it cannot race with the editor's autosave. The caller
     * computes [searchText] from the current body inside the same write transaction.
     */
    @Query("UPDATE notes SET title = :title, search_text = :searchText, modified_at = :now, version = version + 1 WHERE id = :id")
    suspend fun rename(id: String, title: String, searchText: String, now: Long)

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
        UPDATE notes SET deleted_at = :now, title = '', body = '', search_text = '', trashed_at = NULL, trash_root_id = NULL,
          modified_at = :now, version = version + 1
        WHERE id IN (:ids)
        """,
    )
    suspend fun tombstone(ids: List<String>, now: Long)

    /**
     * Hard-deletes an empty note that never had content (still at version 1, i.e. never written
     * since its creation), so it cannot have been synced anywhere. Returns the number of rows deleted.
     */
    @Query(
        """
        DELETE FROM notes
        WHERE id = :id AND version = 1 AND title = '' AND body = '' AND linked_document_id IS NULL
        """,
    )
    suspend fun deleteIfEmpty(id: String): Int

    /**
     * Turns an empty note that had content at some point into a tombstone, so the deletion can be
     * propagated by sync. Returns the number of rows changed.
     */
    @Query(
        """
        UPDATE notes SET deleted_at = :now, title = '', body = '', search_text = '', trashed_at = NULL,
          trash_root_id = NULL, modified_at = :now, version = version + 1
        WHERE id = :id AND title = '' AND body = '' AND linked_document_id IS NULL AND deleted_at IS NULL
        """,
    )
    suspend fun tombstoneIfEmpty(id: String, now: Long): Int
}
