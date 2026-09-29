package com.maogig.gigreader.feature.library

import android.net.Uri
import com.maogig.gigreader.core.data.importer.ImportError
import com.maogig.gigreader.core.data.importer.ImportManager
import com.maogig.gigreader.core.data.importer.ImportOutcome
import com.maogig.gigreader.core.data.importer.ImportProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The part of [ImportManager] the library screen uses. [ImportManager] is a concrete class bound
 * to Android and the database; this seam keeps [LibraryViewModel] testable with a fake.
 */
internal interface ImportGateway {
    val progress: StateFlow<ImportProgress>
    val outcomes: Flow<ImportOutcome>

    fun import(uris: List<Uri>, folderId: String?)

    fun cancelAll()
}

internal class ImportManagerGateway(private val manager: ImportManager) : ImportGateway {
    override val progress: StateFlow<ImportProgress> get() = manager.progress
    override val outcomes: Flow<ImportOutcome> get() = manager.outcomes

    override fun import(uris: List<Uri>, folderId: String?) = manager.import(uris, folderId)

    override fun cancelAll() = manager.cancelAll()
}

/**
 * Turns a burst of import outcomes into a few snackbar messages: importing 20 files shows
 * "Imported 20 documents" instead of 20 snackbars in a row. Cancelled files are not reported
 * (the user asked for it). Single outcomes keep their "Open" action.
 */
internal fun summarizeImportOutcomes(batch: List<ImportOutcome>): List<LibraryMessage> {
    val imported = batch.filterIsInstance<ImportOutcome.Imported>()
    val duplicates = batch.filterIsInstance<ImportOutcome.Duplicate>()
    val failed = batch.filterIsInstance<ImportOutcome.Failed>().filter { it.error != ImportError.CANCELLED }
    val messages = ArrayList<LibraryMessage>(3)
    when (imported.size) {
        0 -> Unit
        1 -> messages.add(LibraryMessage.Imported(imported[0].displayName, imported[0].documentId))
        else -> messages.add(LibraryMessage.ImportedMany(imported.size))
    }
    when (duplicates.size) {
        0 -> Unit
        1 -> {
            val duplicate = duplicates[0]
            messages.add(LibraryMessage.AlreadyInLibrary(duplicate.displayName, duplicate.existingId, duplicate.inTrash))
        }
        else -> messages.add(LibraryMessage.AlreadyInLibraryMany(duplicates.size))
    }
    when (failed.size) {
        0 -> Unit
        1 -> messages.add(LibraryMessage.ImportFailed(failed[0].displayName, failed[0].error))
        else -> messages.add(LibraryMessage.ImportFailedMany(failed.size))
    }
    return messages
}
