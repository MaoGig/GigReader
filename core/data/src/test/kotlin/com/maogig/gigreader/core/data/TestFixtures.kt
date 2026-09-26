package com.maogig.gigreader.core.data

import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.IdGenerator
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.SOURCE_MANAGED
import com.maogig.gigreader.core.database.SearchKeys
import com.maogig.gigreader.core.database.entity.DocumentEntity

/** Manually advanced clock; starts at the real time so file ages (lastModified) compare sensibly. */
internal class TestClock(var millis: Long = System.currentTimeMillis()) : Clock {
    override fun now(): Long = millis
}

/** Predictable ids: "id-1", "id-2", … ([prefix] keeps two generators from producing the same id). */
internal class SequentialIds(private val prefix: String = "id-") : IdGenerator {
    private var next = 0

    override fun newId(): String = "$prefix${++next}"
}

/**
 * Inserts a managed document the way the importer does, with a real file of [content] in [files]
 * (so duplicate/delete can touch it). Returns the document id.
 */
internal suspend fun GigReaderDatabase.insertDocument(
    files: DocumentFileStore,
    id: String,
    title: String = id,
    folderId: String? = null,
    pageCount: Int = 10,
    content: String = "%PDF-1.7 $id",
    now: Long = 1,
): String {
    val incoming = files.newIncomingFile()
    files.openForWrite(incoming).use { it.write(content.toByteArray()) }
    val path = files.commit(incoming, id)
    documentDao().insert(
        DocumentEntity(
            id = id, folderId = folderId, title = title, searchTitle = SearchKeys.of(title), fileName = "$title.pdf",
            type = "PDF", sourceKind = SOURCE_MANAGED, sourcePath = path, contentHash = "hash-$id",
            fileSize = content.length.toLong(), pageCount = pageCount, favorite = false, archived = false,
            annotationCount = 0, createdAt = now, modifiedAt = now, lastOpenedAt = null, version = 1,
            trashedAt = null, trashRootId = null, deletedAt = null,
        ),
    )
    return id
}
