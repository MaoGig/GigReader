package com.maogig.gigreader.core.model

enum class SortField { NAME, CREATED, LAST_OPENED, MODIFIED, SIZE, TYPE }

data class SortOrder(val field: SortField = SortField.NAME, val ascending: Boolean = true) {
    companion object {
        val Default = SortOrder()
    }
}

enum class LibraryFilter {
    ALL,
    PDFS,
    NOTES,
    ANNOTATED,
    FAVORITES,
    RECENT,
}

/** One row of a folder listing. Lightweight projection: no annotation or page data. */
sealed interface LibraryItem {
    val id: String
    val title: String
    val createdAt: Long
    val modifiedAt: Long

    data class FolderEntry(
        override val id: String,
        override val title: String,
        val parentId: String?,
        val childCount: Int,
        override val createdAt: Long,
        override val modifiedAt: Long,
    ) : LibraryItem

    data class DocumentEntry(
        override val id: String,
        override val title: String,
        val folderId: String?,
        val fileSize: Long,
        val pageCount: Int,
        val lastPage: Int?,
        val maxPageReached: Int?,
        val favorite: Boolean,
        val annotationCount: Int,
        val lastOpenedAt: Long?,
        override val createdAt: Long,
        override val modifiedAt: Long,
    ) : LibraryItem {
        /** Reading progress in 0..1, or `null` if the document was never opened. */
        val progress: Float?
            get() = if (maxPageReached == null || pageCount <= 0) null
            else ((maxPageReached + 1).toFloat() / pageCount).coerceIn(0f, 1f)
    }

    data class NoteEntry(
        override val id: String,
        override val title: String,
        val preview: String,
        val folderId: String?,
        val favorite: Boolean,
        override val createdAt: Long,
        override val modifiedAt: Long,
    ) : LibraryItem
}

/**
 * Sorts a mixed listing in memory. Folders always come first (like every file manager), then
 * the rest ordered by [order]. Database queries already return each type sorted; this merges them.
 */
fun List<LibraryItem>.sortedForDisplay(order: SortOrder): List<LibraryItem> {
    val byName = compareBy<LibraryItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
    val primary: Comparator<LibraryItem> = when (order.field) {
        SortField.NAME -> byName
        SortField.CREATED -> compareBy { it.createdAt }
        SortField.MODIFIED -> compareBy { it.modifiedAt }
        SortField.LAST_OPENED -> compareBy { (it as? LibraryItem.DocumentEntry)?.lastOpenedAt ?: 0L }
        SortField.SIZE -> compareBy { (it as? LibraryItem.DocumentEntry)?.fileSize ?: 0L }
        SortField.TYPE -> compareBy { it.typeRank() }
    }
    val directed = if (order.ascending) primary else primary.reversed()
    return sortedWith(compareBy<LibraryItem> { if (it is LibraryItem.FolderEntry) 0 else 1 }.then(directed).then(byName))
}

private fun LibraryItem.typeRank(): Int = when (this) {
    is LibraryItem.FolderEntry -> 0
    is LibraryItem.DocumentEntry -> 1
    is LibraryItem.NoteEntry -> 2
}
