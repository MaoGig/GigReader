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

    /**
     * Deletes covers whose document id is not in [existingIds] (documents that are live or in the
     * trash) and stale `.tmp` files of interrupted renders. Only files untouched for
     * [olderThanMillis] are considered, so the cover of an import whose row is not inserted yet is
     * never at risk. Returns the number of files deleted.
     */
    fun cleanupOrphans(existingIds: Set<String>, nowMillis: Long, olderThanMillis: Long = 60 * 60 * 1000L): Int {
        val files = dir.listFiles() ?: return 0
        return files.count { file ->
            if (!file.isFile || nowMillis - file.lastModified() <= olderThanMillis) return@count false
            val orphan = when {
                file.name.endsWith(".$EXTENSION") -> file.name.removeSuffix(".$EXTENSION") !in existingIds
                file.name.endsWith(TMP_SUFFIX) -> true
                else -> false
            }
            orphan && file.delete()
        }
    }

    companion object {
        const val EXTENSION = "webp"

        /** Suffix of the temporary file a cover is written to before its atomic rename. */
        private const val TMP_SUFFIX = ".tmp"

        /** Cover width in px; covers are drawn at most ~180 dp wide in the grid. */
        const val WIDTH_PX = 360
    }
}
