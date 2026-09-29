package com.maogig.gigreader.core.model

/**
 * A folder of the personal library. `parentId == null` means the folder lives at the root.
 * Folders form a tree; cycles are prevented by the repository when moving.
 */
data class Folder(
    override val id: String,
    val parentId: String?,
    val name: String,
    override val createdAt: Long,
    override val modifiedAt: Long,
    override val version: Long = 1,
    /** Moved to the trash (recoverable). Different from [deletedAt], which is a sync tombstone. */
    val trashedAt: Long? = null,
    override val deletedAt: Long? = null,
) : Syncable

enum class DocumentType { PDF }

/**
 * Where the bytes of a document live.
 *
 * [Managed] files are owned by the app (copied on import into app-private storage), which is the
 * default because it is the only option that guarantees a seekable, always-available file for the
 * PDF engine and a self-contained backup. [Linked] keeps a persisted SAF permission to a file the
 * user did not want to duplicate; it can become unavailable at any time.
 */
sealed interface DocumentSource {
    /** Path relative to the app's library directory (e.g. `"3f2a….pdf"`). */
    data class Managed(val relativePath: String) : DocumentSource

    /** A `content://` URI string with a persisted read permission. */
    data class Linked(val uri: String) : DocumentSource
}

data class Document(
    override val id: String,
    val folderId: String?,
    /** Display name without the `.pdf` extension. */
    val title: String,
    /** Original file name, kept for export and "open in another app". */
    val fileName: String,
    val type: DocumentType,
    val source: DocumentSource,
    /** Lower-case hex SHA-256 of the file content; used for duplicate detection and sync. */
    val contentHash: String,
    val fileSize: Long,
    /** `0` until the document has been opened successfully at least once. */
    val pageCount: Int,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    /** Denormalized count of live annotations (kept in the same transaction as annotation writes). */
    val annotationCount: Int = 0,
    override val createdAt: Long,
    override val modifiedAt: Long,
    val lastOpenedAt: Long? = null,
    override val version: Long = 1,
    val trashedAt: Long? = null,
    override val deletedAt: Long? = null,
) : Syncable

/**
 * Where the user stopped reading. Stored separately from [Document] because it changes far more
 * often than document metadata and must not bump the document's version on every page turn.
 */
data class ReadingPosition(
    val documentId: String,
    /** Zero-based index of the page at the top of the viewport (restores the viewport). */
    val page: Int,
    /** Fraction (0..1) of [page] scrolled past the top of the viewport. */
    val pageOffset: Float = 0f,
    /** Zoom relative to "fit width" (1.0 = fit width). */
    val zoom: Float = 1f,
    /** Horizontal offset divided by the document width (only non-zero when zoomed in). */
    val offsetXFraction: Float = 0f,
    /**
     * Zero-based page shown by the reader's "X / N" indicator (the page at the viewport's vertical
     * center). "Continue reading" shows this page, so both agree.
     */
    val currentPage: Int = page,
    /** Highest page ever visible (bottom edge of the viewport), used for the "% read" indicator. */
    val maxPageReached: Int = currentPage,
    val updatedAt: Long,
    val version: Long = 1,
) {
    fun progress(pageCount: Int): Float =
        if (pageCount <= 0) 0f else ((maxPageReached + 1).toFloat() / pageCount).coerceIn(0f, 1f)
}

/** A quick note. It can live at the root, in a folder, and optionally point at a document page. */
data class Note(
    override val id: String,
    val folderId: String?,
    val title: String,
    val body: String,
    val linkedDocumentId: String? = null,
    val linkedPage: Int? = null,
    val favorite: Boolean = false,
    override val createdAt: Long,
    override val modifiedAt: Long,
    override val version: Long = 1,
    val trashedAt: Long? = null,
    override val deletedAt: Long? = null,
) : Syncable

data class Bookmark(
    override val id: String,
    val documentId: String,
    val page: Int,
    val title: String,
    override val createdAt: Long,
    override val modifiedAt: Long,
    override val version: Long = 1,
    override val deletedAt: Long? = null,
) : Syncable

data class Tag(
    override val id: String,
    /** Normalized (trimmed, lower-case, no leading '#') and unique. */
    val name: String,
    override val createdAt: Long,
    override val modifiedAt: Long,
    override val version: Long = 1,
    override val deletedAt: Long? = null,
) : Syncable {
    companion object {
        fun normalize(raw: String): String = raw.trim().removePrefix("#").trim().lowercase()
    }
}
