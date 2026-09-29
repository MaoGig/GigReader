package com.maogig.gigreader.core.database.dao

/*
 * Lightweight projections for list screens: they carry only the columns a row displays, so scrolling
 * a large library never loads note bodies, annotation data or anything else it does not draw.
 */

data class FolderRow(
    val id: String,
    val name: String,
    val parentId: String?,
    val childCount: Int,
    val createdAt: Long,
    val modifiedAt: Long,
)

/** Minimal folder data for building the folder tree (move dialog, breadcrumbs). */
data class FolderNode(
    val id: String,
    val name: String,
    val parentId: String?,
)

data class DocumentRow(
    val id: String,
    val title: String,
    val folderId: String?,
    val fileSize: Long,
    val pageCount: Int,
    val lastPage: Int?,
    val maxPageReached: Int?,
    val favorite: Boolean,
    val annotationCount: Int,
    val lastOpenedAt: Long?,
    val createdAt: Long,
    val modifiedAt: Long,
)

data class NoteRow(
    val id: String,
    val title: String,
    /** First characters of the body, cut in SQL so long notes are never loaded for a list. */
    val preview: String,
    val folderId: String?,
    val favorite: Boolean,
    val createdAt: Long,
    val modifiedAt: Long,
)

/** An item the user moved to the trash (only roots are listed; descendants follow their root). */
data class TrashRow(
    val id: String,
    val title: String,
    /** "folder", "document" or "note". */
    val kind: String,
    val trashedAt: Long,
)

/** Id and parent (folder) id of an item, used to remember where moved items came from (undo). */
data class IdParent(
    val id: String,
    val parentId: String?,
)

/** Favorite flag of an item, used to undo favorite changes. */
data class IdFlag(
    val id: String,
    val flag: Boolean,
)
