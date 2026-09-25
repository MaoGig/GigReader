package com.maogig.gigreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.maogig.gigreader.core.database.entity.BookmarkEntity
import com.maogig.gigreader.core.database.entity.PageMetricsEntity
import com.maogig.gigreader.core.database.entity.ReadingPositionEntity
import com.maogig.gigreader.core.database.entity.TextAnnotationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingPositionDao {
    @Query("SELECT * FROM reading_positions WHERE document_id = :documentId")
    suspend fun get(documentId: String): ReadingPositionEntity?

    @Upsert
    suspend fun upsert(position: ReadingPositionEntity)
}

@Dao
interface PageMetricsDao {
    @Query("SELECT * FROM page_metrics WHERE document_id = :documentId")
    suspend fun get(documentId: String): PageMetricsEntity?

    @Upsert
    suspend fun upsert(metrics: PageMetricsEntity)
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM text_annotations WHERE document_id = :documentId AND deleted_at IS NULL ORDER BY page, created_at")
    fun observeForDocument(documentId: String): Flow<List<TextAnnotationEntity>>

    @Query("SELECT * FROM text_annotations WHERE document_id = :documentId AND page = :page AND deleted_at IS NULL")
    suspend fun forPage(documentId: String, page: Int): List<TextAnnotationEntity>

    @Query("SELECT DISTINCT page FROM text_annotations WHERE document_id = :documentId AND deleted_at IS NULL ORDER BY page")
    fun observeAnnotatedPages(documentId: String): Flow<List<Int>>

    @Query("SELECT * FROM text_annotations WHERE id = :id")
    suspend fun getById(id: String): TextAnnotationEntity?

    @Query("SELECT * FROM text_annotations WHERE document_id = :documentId AND deleted_at IS NULL")
    suspend fun allForDocument(documentId: String): List<TextAnnotationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(annotations: List<TextAnnotationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(annotation: TextAnnotationEntity)

    @Query("UPDATE text_annotations SET note = :note, modified_at = :now, version = version + 1 WHERE id = :id")
    suspend fun setNote(id: String, note: String?, now: Long)

    @Query("UPDATE text_annotations SET color = :color, modified_at = :now, version = version + 1 WHERE id = :id")
    suspend fun setColor(id: String, color: String, now: Long)

    @Query("UPDATE text_annotations SET deleted_at = :now, modified_at = :now, version = version + 1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun markDeleted(id: String, now: Long): Int

    @Query("UPDATE text_annotations SET deleted_at = NULL, modified_at = :now, version = version + 1 WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun unmarkDeleted(id: String, now: Long): Int

    @Query("DELETE FROM text_annotations WHERE document_id IN (:documentIds)")
    suspend fun deleteForDocuments(documentIds: List<String>)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE document_id = :documentId AND deleted_at IS NULL ORDER BY page")
    fun observeForDocument(documentId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE document_id = :documentId AND deleted_at IS NULL")
    suspend fun allForDocument(documentId: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(bookmarks: List<BookmarkEntity>)

    @Query("UPDATE bookmarks SET title = :title, modified_at = :now, version = version + 1 WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE bookmarks SET deleted_at = :now, modified_at = :now, version = version + 1 WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long)

    @Query("DELETE FROM bookmarks WHERE document_id IN (:documentIds)")
    suspend fun deleteForDocuments(documentIds: List<String>)
}
