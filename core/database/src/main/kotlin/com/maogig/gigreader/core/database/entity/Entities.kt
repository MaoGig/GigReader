package com.maogig.gigreader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema conventions (see docs/ARCHITECTURE.md, "Modelo de dados"):
 * - Every user entity has id (UUID), created_at, modified_at, version and deleted_at (sync tombstone).
 * - Library items (folders, documents, notes) also have trashed_at + trash_root_id: moving a folder
 *   to the trash cascades to its descendants with trash_root_id = folder id, so "restore" brings the
 *   whole subtree back and the Trash screen only lists roots.
 * - "Live" rows are `trashed_at IS NULL AND deleted_at IS NULL`.
 * - Columns used in WHERE/ORDER BY of list queries are indexed.
 */

@Entity(
    tableName = "folders",
    indices = [Index("parent_id"), Index("trash_root_id")],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val version: Long,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long?,
    @ColumnInfo(name = "trash_root_id") val trashRootId: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(
    tableName = "documents",
    indices = [
        Index("folder_id"),
        Index("content_hash"),
        Index("last_opened_at"),
        Index("modified_at"),
        Index("favorite"),
        Index("trash_root_id"),
    ],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    val title: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    /** [com.maogig.gigreader.core.model.DocumentType] name. */
    val type: String,
    /** "managed" (path relative to the library dir) or "linked" (content URI). */
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    @ColumnInfo(name = "source_path") val sourcePath: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    val favorite: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "annotation_count") val annotationCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
    val version: Long,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long?,
    @ColumnInfo(name = "trash_root_id") val trashRootId: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

/** High-frequency reading state, separated from [DocumentEntity] so page turns do not bump its version. */
@Entity(
    tableName = "reading_positions",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingPositionEntity(
    @PrimaryKey @ColumnInfo(name = "document_id") val documentId: String,
    val page: Int,
    @ColumnInfo(name = "page_offset") val pageOffset: Float,
    val zoom: Float,
    @ColumnInfo(name = "max_page_reached") val maxPageReached: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val version: Long,
)

/**
 * Page dimensions (PDF points) measured by the engine, so reopening a 2 000-page document can lay
 * out every page instantly instead of measuring them again. Derived data: never synced.
 */
@Entity(
    tableName = "page_metrics",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PageMetricsEntity(
    @PrimaryKey @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    /** Little-endian float32 pairs (width, height) per page; see [PageSizeCodec]. */
    @ColumnInfo(name = "sizes", typeAffinity = ColumnInfo.BLOB) val sizes: ByteArray,
    /** Number of leading pages whose size was actually measured (the rest are estimates). */
    @ColumnInfo(name = "measured_count") val measuredCount: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is PageMetricsEntity && other.documentId == documentId && other.pageCount == pageCount &&
            other.measuredCount == measuredCount && other.sizes.contentEquals(sizes)

    override fun hashCode(): Int = documentId.hashCode()
}

@Entity(
    tableName = "notes",
    indices = [Index("folder_id"), Index("linked_document_id"), Index("modified_at"), Index("trash_root_id")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "linked_document_id") val linkedDocumentId: String?,
    @ColumnInfo(name = "linked_page") val linkedPage: Int?,
    val favorite: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val version: Long,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long?,
    @ColumnInfo(name = "trash_root_id") val trashRootId: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(
    tableName = "text_annotations",
    indices = [Index(value = ["document_id", "page"])],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TextAnnotationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    val page: Int,
    /** [com.maogig.gigreader.core.model.TextMarkupType] name. */
    val type: String,
    val text: String,
    /** [com.maogig.gigreader.core.model.NormalizedRect.encode] format. */
    val rects: String,
    /** [com.maogig.gigreader.core.model.HighlightColor.key]. */
    val color: String,
    val note: String?,
    @ColumnInfo(name = "char_start") val charStart: Int?,
    @ColumnInfo(name = "char_end") val charEnd: Int?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val version: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["document_id", "page"])],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    val page: Int,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val version: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val version: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(
    tableName = "document_tags",
    primaryKeys = ["document_id", "tag_id"],
    indices = [Index("tag_id")],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DocumentTagEntity(
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
