package com.maogig.gigreader.core.data.library

import java.io.File

/**
 * Disk location of library covers (first page, small, compressed). Covers are generated once at
 * import time so the library never opens PDFs while scrolling; they live in the cache directory and
 * are regenerated on demand if the system clears it.
 */
class CoverStore(private val dir: File) {
    fun fileFor(documentId: String): File = File(dir, "$documentId.$EXTENSION")

    fun exists(documentId: String): Boolean = fileFor(documentId).isFile

    fun delete(documentId: String) {
        fileFor(documentId).delete()
    }

    fun copy(fromDocumentId: String, toDocumentId: String) {
        val source = fileFor(fromDocumentId)
        if (source.isFile) source.copyTo(fileFor(toDocumentId), overwrite = true)
    }

    fun ensureDir(): File = dir.apply { mkdirs() }

    fun totalSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        const val EXTENSION = "webp"

        /** Cover width in px; covers are drawn at most ~180 dp wide in the grid. */
        const val WIDTH_PX = 360
    }
}
