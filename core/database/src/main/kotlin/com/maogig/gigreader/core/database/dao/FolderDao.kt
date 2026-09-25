package com.maogig.gigreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maogig.gigreader.core.database.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query(
        """
        SELECT f.id, f.name, f.parent_id AS parentId, f.created_at AS createdAt, f.modified_at AS modifiedAt,
          (SELECT COUNT(*) FROM folders c WHERE c.parent_id = f.id AND c.trashed_at IS NULL AND c.deleted_at IS NULL)
          + (SELECT COUNT(*) FROM documents d WHERE d.folder_id = f.id AND d.trashed_at IS NULL AND d.deleted_at IS NULL AND d.archived = 0)
          + (SELECT COUNT(*) FROM notes n WHERE n.folder_id = f.id AND n.trashed_at IS NULL AND n.deleted_at IS NULL)
          AS childCount
        FROM folders f
        WHERE f.parent_id IS :parentId AND f.trashed_at IS NULL AND f.deleted_at IS NULL
        """,
    )
    fun observeChildren(parentId: String?): Flow<List<FolderRow>>

    @Query("SELECT id, name, parent_id AS parentId FROM folders WHERE trashed_at IS NULL AND deleted_at IS NULL")
    fun observeTree(): Flow<List<FolderNode>>

    @Query("SELECT id, name, parent_id AS parentId FROM folders WHERE trashed_at IS NULL AND deleted_at IS NULL")
    suspend fun tree(): List<FolderNode>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :id")
    fun observeById(id: String): Flow<FolderEntity?>

    @Query(
        """
        SELECT f.id, f.name, f.parent_id AS parentId, f.created_at AS createdAt, f.modified_at AS modifiedAt, 0 AS childCount
        FROM folders f
        WHERE f.name LIKE '%' || :query || '%' ESCAPE '\' AND f.trashed_at IS NULL AND f.deleted_at IS NULL
        ORDER BY f.name COLLATE NOCASE LIMIT :limit
        """,
    )
    suspend fun searchByName(query: String, limit: Int): List<FolderRow>

    @Query("SELECT id, parent_id AS parentId FROM folders WHERE id IN (:ids)")
    suspend fun parents(ids: List<String>): List<IdParent>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name, modified_at = :now, version = version + 1 WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE folders SET parent_id = :parentId, modified_at = :now, version = version + 1 WHERE id IN (:ids)")
    suspend fun move(ids: List<String>, parentId: String?, now: Long)

    /**
     * Ids of [rootId] and all its live descendants (recursive). UNION (not UNION ALL) drops rows
     * already visited, so even a corrupted tree with a parent cycle terminates instead of spinning.
     */
    @Query(
        """
        WITH RECURSIVE sub(id) AS (
          SELECT :rootId
          UNION
          SELECT f.id FROM folders f JOIN sub ON f.parent_id = sub.id
          WHERE f.trashed_at IS NULL AND f.deleted_at IS NULL
        )
        SELECT id FROM sub
        """,
    )
    suspend fun subtreeIds(rootId: String): List<String>

    /** Ids of [rootId] and all descendants that were trashed together with it. */
    @Query("SELECT id FROM folders WHERE trash_root_id = :rootId")
    suspend fun idsTrashedWith(rootId: String): List<String>

    @Query(
        """
        UPDATE folders SET trashed_at = :now, trash_root_id = :rootId, modified_at = :now, version = version + 1
        WHERE id IN (:ids) AND trashed_at IS NULL AND deleted_at IS NULL
        """,
    )
    suspend fun trash(ids: List<String>, rootId: String, now: Long)

    @Query(
        """
        UPDATE folders SET trashed_at = NULL, trash_root_id = NULL, modified_at = :now, version = version + 1
        WHERE trash_root_id = :rootId
        """,
    )
    suspend fun restore(rootId: String, now: Long)

    @Query(
        """
        SELECT id, name AS title, 'folder' AS kind, trashed_at AS trashedAt FROM folders
        WHERE trashed_at IS NOT NULL AND trash_root_id = id AND deleted_at IS NULL
        """,
    )
    fun observeTrashRoots(): Flow<List<TrashRow>>

    /** Turns trashed folders into tombstones (content cleared, row kept for sync). */
    @Query(
        """
        UPDATE folders SET deleted_at = :now, name = '', trashed_at = NULL, trash_root_id = NULL,
          modified_at = :now, version = version + 1
        WHERE id IN (:ids)
        """,
    )
    suspend fun tombstone(ids: List<String>, now: Long)

    @Query("SELECT COUNT(*) FROM folders WHERE trashed_at IS NULL AND deleted_at IS NULL")
    suspend fun liveCount(): Int
}
