package com.maogig.gigreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maogig.gigreader.core.database.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

private const val ROW_COLUMNS = """
    d.id, d.title, d.folder_id AS folderId, d.file_size AS fileSize, d.page_count AS pageCount,
    rp.page AS lastPage, rp.max_page_reached AS maxPageReached, d.favorite,
    d.annotation_count AS annotationCount, d.last_opened_at AS lastOpenedAt,
    d.created_at AS createdAt, d.modified_at AS modifiedAt
"""

private const val FROM_LIVE = """
    FROM documents d LEFT JOIN reading_positions rp ON rp.document_id = d.id
    WHERE d.trashed_at IS NULL AND d.deleted_at IS NULL
"""

@Dao
interface DocumentDao {
    @Query("SELECT $ROW_COLUMNS $FROM_LIVE AND d.folder_id IS :folderId AND d.archived = 0")
    fun observeInFolder(folderId: String?): Flow<List<DocumentRow>>

    /** "Continue reading": most recently opened first; served by the last_opened_at index. */
    @Query("SELECT $ROW_COLUMNS $FROM_LIVE AND d.last_opened_at IS NOT NULL ORDER BY d.last_opened_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DocumentRow>>

    @Query("SELECT $ROW_COLUMNS $FROM_LIVE AND d.favorite = 1 ORDER BY d.title COLLATE NOCASE LIMIT :limit")
    fun observeFavorites(limit: Int): Flow<List<DocumentRow>>

    @Query("SELECT $ROW_COLUMNS $FROM_LIVE AND d.archived = 1 ORDER BY d.title COLLATE NOCASE")
    fun observeArchived(): Flow<List<DocumentRow>>

    @Query(
        """
        SELECT $ROW_COLUMNS $FROM_LIVE AND d.title LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY d.last_opened_at IS NULL, d.last_opened_at DESC, d.title COLLATE NOCASE LIMIT :limit
        """,
    )
    suspend fun searchByTitle(query: String, limit: Int): List<DocumentRow>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeById(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<DocumentEntity>

    /** Existing, not purged document with identical content (duplicate detection on import). */
    @Query("SELECT * FROM documents WHERE content_hash = :hash AND deleted_at IS NULL LIMIT 1")
    suspend fun findByHash(hash: String): DocumentEntity?

    @Query("SELECT id, folder_id AS parentId FROM documents WHERE id IN (:ids)")
    suspend fun parents(ids: List<String>): List<IdParent>

    @Query("SELECT id, favorite AS flag FROM documents WHERE id IN (:ids)")
    suspend fun favorites(ids: List<String>): List<IdFlag>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(document: DocumentEntity)

    @Query("UPDATE documents SET title = :title, modified_at = :now, version = version + 1 WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE documents SET folder_id = :folderId, modified_at = :now, version = version + 1 WHERE id IN (:ids)")
    suspend fun move(ids: List<String>, folderId: String?, now: Long)

    @Query("UPDATE documents SET favorite = :favorite, modified_at = :now, version = version + 1 WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<String>, favorite: Boolean, now: Long)

    @Query("UPDATE documents SET archived = :archived, modified_at = :now, version = version + 1 WHERE id IN (:ids)")
    suspend fun setArchived(ids: List<String>, archived: Boolean, now: Long)

    /**
     * Opening a document is device-local state: it does not bump [DocumentEntity.version] so that
     * merely reading never produces sync traffic.
     */
    @Query("UPDATE documents SET last_opened_at = :now WHERE id = :id")
    suspend fun markOpened(id: String, now: Long)

    @Query("UPDATE documents SET page_count = :pageCount WHERE id = :id AND page_count != :pageCount")
    suspend fun setPageCount(id: String, pageCount: Int)

    @Query("UPDATE documents SET annotation_count = annotation_count + :delta, modified_at = :now WHERE id = :id")
    suspend fun addAnnotationCount(id: String, delta: Int, now: Long)

    @Query("SELECT id FROM documents WHERE folder_id IN (:folderIds) AND trashed_at IS NULL AND deleted_at IS NULL")
    suspend fun liveIdsInFolders(folderIds: List<String>): List<String>

    @Query("SELECT id FROM documents WHERE trash_root_id = :rootId")
    suspend fun idsTrashedWith(rootId: String): List<String>

    @Query(
        """
        UPDATE documents SET trashed_at = :now, trash_root_id = :rootId, modified_at = :now, version = version + 1
        WHERE id IN (:ids) AND trashed_at IS NULL AND deleted_at IS NULL
        """,
    )
    suspend fun trash(ids: List<String>, rootId: String, now: Long)

    /** Trashes each document as its own trash root (multi-selection delete). */
    @Query(
        """
        UPDATE documents SET trashed_at = :now, trash_root_id = id, modified_at = :now, version = version + 1
        WHERE id IN (:ids) AND trashed_at IS NULL AND deleted_at IS NULL
        """,
    )
    suspend fun trashEach(ids: List<String>, now: Long)

    @Query(
        """
        UPDATE documents SET trashed_at = NULL, trash_root_id = NULL, modified_at = :now, version = version + 1
        WHERE trash_root_id = :rootId
        """,
    )
    suspend fun restore(rootId: String, now: Long)

    @Query(
        """
        SELECT id, title, 'document' AS kind, trashed_at AS trashedAt FROM documents
        WHERE trashed_at IS NOT NULL AND trash_root_id = id AND deleted_at IS NULL
        """,
    )
    fun observeTrashRoots(): Flow<List<TrashRow>>

    @Query("SELECT * FROM documents WHERE trashed_at IS NOT NULL AND trashed_at < :before AND deleted_at IS NULL")
    suspend fun trashedBefore(before: Long): List<DocumentEntity>

    /** Purges content and keeps a tombstone row for sync. The file itself is deleted by the caller. */
    @Query(
        """
        UPDATE documents SET deleted_at = :now, title = '', file_name = '', source_path = '', favorite = 0,
          annotation_count = 0, trashed_at = NULL, trash_root_id = NULL, modified_at = :now, version = version + 1
        WHERE id IN (:ids)
        """,
    )
    suspend fun tombstone(ids: List<String>, now: Long)

    @Query("SELECT source_path FROM documents WHERE source_kind = 'managed' AND deleted_at IS NULL")
    suspend fun managedPaths(): List<String>

    @Query("SELECT COUNT(*) FROM documents WHERE deleted_at IS NULL")
    suspend fun count(): Int
}
