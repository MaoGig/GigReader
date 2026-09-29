package com.maogig.gigreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.maogig.gigreader.core.database.entity.DocumentTagEntity
import com.maogig.gigreader.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE deleted_at IS NULL ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity)

    /** Replaces the whole row: when reviving an unlinked pair, pass the previous version + 1. */
    @Upsert
    suspend fun link(link: DocumentTagEntity)

    @Query(
        """
        UPDATE document_tags SET deleted_at = :now, modified_at = :now, version = version + 1
        WHERE document_id = :documentId AND tag_id = :tagId AND deleted_at IS NULL
        """,
    )
    suspend fun unlink(documentId: String, tagId: String, now: Long)

    @Query(
        """
        SELECT t.* FROM tags t JOIN document_tags dt ON dt.tag_id = t.id
        WHERE dt.document_id = :documentId AND dt.deleted_at IS NULL AND t.deleted_at IS NULL ORDER BY t.name
        """,
    )
    fun observeForDocument(documentId: String): Flow<List<TagEntity>>
}
