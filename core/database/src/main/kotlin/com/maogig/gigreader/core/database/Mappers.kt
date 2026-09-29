package com.maogig.gigreader.core.database

import com.maogig.gigreader.core.database.dao.DocumentRow
import com.maogig.gigreader.core.database.dao.FolderRow
import com.maogig.gigreader.core.database.dao.NoteRow
import com.maogig.gigreader.core.database.entity.BookmarkEntity
import com.maogig.gigreader.core.database.entity.DocumentEntity
import com.maogig.gigreader.core.database.entity.FolderEntity
import com.maogig.gigreader.core.database.entity.NoteEntity
import com.maogig.gigreader.core.database.entity.ReadingPositionEntity
import com.maogig.gigreader.core.database.entity.TextAnnotationEntity
import com.maogig.gigreader.core.model.Bookmark
import com.maogig.gigreader.core.model.Document
import com.maogig.gigreader.core.model.DocumentSource
import com.maogig.gigreader.core.model.DocumentType
import com.maogig.gigreader.core.model.Folder
import com.maogig.gigreader.core.model.HighlightColor
import com.maogig.gigreader.core.model.LibraryItem
import com.maogig.gigreader.core.model.NormalizedRect
import com.maogig.gigreader.core.model.Note
import com.maogig.gigreader.core.model.ReadingPosition
import com.maogig.gigreader.core.model.TextAnnotation
import com.maogig.gigreader.core.model.TextMarkupType

const val SOURCE_MANAGED = "managed"
const val SOURCE_LINKED = "linked"

fun FolderEntity.toModel() = Folder(
    id = id, parentId = parentId, name = name, createdAt = createdAt, modifiedAt = modifiedAt,
    version = version, trashedAt = trashedAt, deletedAt = deletedAt,
)

fun DocumentEntity.toModel() = Document(
    id = id,
    folderId = folderId,
    title = title,
    fileName = fileName,
    type = runCatching { DocumentType.valueOf(type) }.getOrDefault(DocumentType.PDF),
    source = if (sourceKind == SOURCE_LINKED) DocumentSource.Linked(sourcePath) else DocumentSource.Managed(sourcePath),
    contentHash = contentHash,
    fileSize = fileSize,
    pageCount = pageCount,
    favorite = favorite,
    archived = archived,
    annotationCount = annotationCount,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt,
    version = version,
    trashedAt = trashedAt,
    deletedAt = deletedAt,
)

fun Document.toEntity(trashRootId: String? = null) = DocumentEntity(
    id = id,
    folderId = folderId,
    title = title,
    searchTitle = SearchKeys.of(title),
    fileName = fileName,
    type = type.name,
    sourceKind = when (source) {
        is DocumentSource.Managed -> SOURCE_MANAGED
        is DocumentSource.Linked -> SOURCE_LINKED
    },
    sourcePath = when (val s = source) {
        is DocumentSource.Managed -> s.relativePath
        is DocumentSource.Linked -> s.uri
    },
    contentHash = contentHash,
    fileSize = fileSize,
    pageCount = pageCount,
    favorite = favorite,
    archived = archived,
    annotationCount = annotationCount,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt,
    version = version,
    trashedAt = trashedAt,
    trashRootId = trashRootId,
    deletedAt = deletedAt,
)

fun ReadingPositionEntity.toModel() = ReadingPosition(
    documentId = documentId, page = page, pageOffset = pageOffset, zoom = zoom,
    offsetXFraction = offsetXFraction, currentPage = currentPage, maxPageReached = maxPageReached,
    updatedAt = updatedAt, version = version,
)

fun NoteEntity.toModel() = Note(
    id = id, folderId = folderId, title = title, body = body, linkedDocumentId = linkedDocumentId,
    linkedPage = linkedPage, favorite = favorite, createdAt = createdAt, modifiedAt = modifiedAt,
    version = version, trashedAt = trashedAt, deletedAt = deletedAt,
)

fun TextAnnotationEntity.toModel() = TextAnnotation(
    id = id,
    documentId = documentId,
    page = page,
    type = runCatching { TextMarkupType.valueOf(type) }.getOrDefault(TextMarkupType.HIGHLIGHT),
    text = text,
    rects = NormalizedRect.decode(rects),
    color = HighlightColor.fromKey(color),
    note = note,
    charStart = charStart,
    charEnd = charEnd,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    version = version,
    deletedAt = deletedAt,
)

fun TextAnnotation.toEntity() = TextAnnotationEntity(
    id = id,
    documentId = documentId,
    page = page,
    type = type.name,
    text = text,
    rects = NormalizedRect.encode(rects),
    color = color.key,
    note = note,
    charStart = charStart,
    charEnd = charEnd,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    version = version,
    deletedAt = deletedAt,
)

fun BookmarkEntity.toModel() = Bookmark(
    id = id, documentId = documentId, page = page, title = title, createdAt = createdAt,
    modifiedAt = modifiedAt, version = version, deletedAt = deletedAt,
)

fun FolderRow.toItem() = LibraryItem.FolderEntry(
    id = id, title = name, parentId = parentId, childCount = childCount, createdAt = createdAt, modifiedAt = modifiedAt,
)

fun DocumentRow.toItem() = LibraryItem.DocumentEntry(
    id = id, title = title, folderId = folderId, fileSize = fileSize, pageCount = pageCount, lastPage = lastPage,
    maxPageReached = maxPageReached, favorite = favorite, annotationCount = annotationCount,
    lastOpenedAt = lastOpenedAt, createdAt = createdAt, modifiedAt = modifiedAt,
)

fun NoteRow.toItem() = LibraryItem.NoteEntry(
    id = id, title = title, preview = preview, folderId = folderId, favorite = favorite,
    createdAt = createdAt, modifiedAt = modifiedAt,
)
