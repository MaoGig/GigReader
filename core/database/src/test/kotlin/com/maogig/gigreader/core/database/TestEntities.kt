package com.maogig.gigreader.core.database

import com.maogig.gigreader.core.database.entity.DocumentEntity
import com.maogig.gigreader.core.database.entity.FolderEntity
import com.maogig.gigreader.core.database.entity.NoteEntity

/** Row builders with the search columns filled in like the repositories do. */
internal fun folderEntity(
    id: String,
    parentId: String? = null,
    name: String = id,
    modifiedAt: Long = 1,
    trashedAt: Long? = null,
    trashRootId: String? = null,
    deletedAt: Long? = null,
) = FolderEntity(
    id = id, parentId = parentId, name = name, searchName = SearchKeys.of(name), createdAt = 1, modifiedAt = modifiedAt,
    version = 1, trashedAt = trashedAt, trashRootId = trashRootId, deletedAt = deletedAt,
)

internal fun documentEntity(
    id: String,
    folderId: String? = null,
    title: String = id,
    pageCount: Int = 10,
    favorite: Boolean = false,
    archived: Boolean = false,
    lastOpenedAt: Long? = null,
    trashedAt: Long? = null,
    trashRootId: String? = null,
) = DocumentEntity(
    id = id, folderId = folderId, title = title, searchTitle = SearchKeys.of(title), fileName = "$title.pdf",
    type = "PDF", sourceKind = SOURCE_MANAGED, sourcePath = "$id.pdf", contentHash = "hash-$id", fileSize = 100,
    pageCount = pageCount, favorite = favorite, archived = archived, annotationCount = 0, createdAt = 1, modifiedAt = 1,
    lastOpenedAt = lastOpenedAt, version = 1, trashedAt = trashedAt, trashRootId = trashRootId, deletedAt = null,
)

internal fun noteEntity(
    id: String,
    folderId: String? = null,
    title: String = "",
    body: String = "",
    favorite: Boolean = false,
    modifiedAt: Long = 1,
    version: Long = 1,
    linkedDocumentId: String? = null,
) = NoteEntity(
    id = id, folderId = folderId, title = title, body = body, searchText = SearchKeys.note(title, body),
    linkedDocumentId = linkedDocumentId, linkedPage = null, favorite = favorite, createdAt = 1, modifiedAt = modifiedAt,
    version = version, trashedAt = null, trashRootId = null, deletedAt = null,
)
