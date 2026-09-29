package com.maogig.gigreader.core.data.library

import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.database.GigReaderDatabase
import kotlinx.coroutines.CancellationException

/**
 * Removes what interrupted imports or permanent deletions may have left behind: stale temp files,
 * library files without a row and covers of documents that no longer exist. Runs on explicit
 * triggers only (the importer waking up, a permanent deletion), never at app start. Call it on an
 * I/O dispatcher.
 *
 * Every step only touches files old enough that no import in progress can still own them. Library
 * files are additionally only swept when the database holds documents: if SQLite ever had to recreate
 * an empty database after corruption, the files are the only copy left and must not be touched.
 * Covers are regenerable, so they need no such guard.
 */
internal suspend fun sweepLeftovers(db: GigReaderDatabase, files: DocumentFileStore, covers: CoverStore, now: Long) {
    val dao = db.documentDao()
    bestEffort { files.cleanupIncoming(now) }
    bestEffort { if (dao.count() > 0) files.cleanupOrphans(dao.managedPaths().toHashSet(), now) }
    bestEffort { covers.cleanupOrphans(dao.existingIds().toHashSet(), now) }
}

private inline fun bestEffort(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A failed sweep is retried on the next trigger; it must never fail the caller.
    }
}
