package com.maogig.gigreader

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.maogig.gigreader.core.data.importer.ImportError
import com.maogig.gigreader.core.data.importer.ImportOutcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// App-level import feedback: the library screen reports the outcomes itself; everywhere else (reader,
// note editor, settings, trash) a file shared from another app or added from the external reader
// would finish silently, so the app shows the result in its own snackbar host.

/** One app-level snackbar about finished imports. */
@Immutable
internal sealed interface ImportMessage {
    data class Imported(val title: String, val documentId: String) : ImportMessage

    data class ImportedMany(val count: Int) : ImportMessage

    data class Duplicate(val name: String, val documentId: String, val inTrash: Boolean) : ImportMessage

    data class DuplicateMany(val count: Int) : ImportMessage

    data class Failed(val name: String, val error: ImportError) : ImportMessage

    data class FailedMany(val count: Int) : ImportMessage
}

/**
 * Turns a burst of outcomes into at most three messages (sharing 20 files shows "Imported 20
 * documents", not 20 snackbars). Cancelled files are not reported: the user asked for it.
 */
internal fun summarizeImportOutcomes(batch: List<ImportOutcome>): List<ImportMessage> {
    val imported = batch.filterIsInstance<ImportOutcome.Imported>()
    val duplicates = batch.filterIsInstance<ImportOutcome.Duplicate>()
    val failed = batch.filterIsInstance<ImportOutcome.Failed>().filter { it.error != ImportError.CANCELLED }
    val messages = ArrayList<ImportMessage>(3)
    when (imported.size) {
        0 -> Unit
        1 -> messages.add(ImportMessage.Imported(imported[0].displayName, imported[0].documentId))
        else -> messages.add(ImportMessage.ImportedMany(imported.size))
    }
    when (duplicates.size) {
        0 -> Unit
        1 -> duplicates[0].let { messages.add(ImportMessage.Duplicate(it.displayName, it.existingId, it.inTrash)) }
        else -> messages.add(ImportMessage.DuplicateMany(duplicates.size))
    }
    when (failed.size) {
        0 -> Unit
        1 -> messages.add(ImportMessage.Failed(failed[0].displayName, failed[0].error))
        else -> messages.add(ImportMessage.FailedMany(failed.size))
    }
    return messages
}

/** Document the snackbar's "Open" action shows, if any (a duplicate in the trash cannot be opened). */
internal fun ImportMessage.openableDocumentId(): String? = when (this) {
    is ImportMessage.Imported -> documentId
    is ImportMessage.Duplicate -> documentId.takeUnless { inTrash }
    else -> null
}

/** Localized text of [message]. Resolved outside composition (snackbars are effects). */
internal fun Context.importMessageText(message: ImportMessage): String {
    val res = resources
    return when (message) {
        is ImportMessage.Imported -> getString(R.string.app_import_imported, message.title)
        is ImportMessage.ImportedMany ->
            res.getQuantityString(R.plurals.app_import_imported_many, message.count, message.count)
        is ImportMessage.Duplicate -> getString(
            if (message.inTrash) R.string.app_import_duplicate_in_trash else R.string.app_import_duplicate,
            message.name,
        )
        is ImportMessage.DuplicateMany ->
            res.getQuantityString(R.plurals.app_import_duplicate_many, message.count, message.count)
        is ImportMessage.Failed -> getString(importErrorText(message.error), message.name)
        is ImportMessage.FailedMany ->
            res.getQuantityString(R.plurals.app_import_failed_many, message.count, message.count)
    }
}

/** Friendly explanation per import error (§50: never a stack trace). */
@StringRes
private fun importErrorText(error: ImportError): Int = when (error) {
    ImportError.NOT_A_PDF -> R.string.app_import_failed_not_pdf
    ImportError.PASSWORD_PROTECTED -> R.string.app_import_failed_password
    ImportError.CORRUPTED -> R.string.app_import_failed_corrupted
    ImportError.NO_SPACE -> R.string.app_import_failed_no_space
    ImportError.UNREADABLE, ImportError.CANCELLED -> R.string.app_import_failed_unreadable
}

/**
 * Shows the import [outcomes] that finish while [reportHere] is true (the current screen is not a
 * library screen, which reports them itself) in [host]. Bursts are summarized; "Open" calls
 * [onOpenDocument]. Suspended while nothing is imported: no work when the user does nothing.
 */
@Composable
internal fun ImportFeedback(
    outcomes: Flow<ImportOutcome>,
    host: SnackbarHostState,
    reportHere: () -> Boolean,
    onOpenDocument: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentContext by rememberUpdatedState(context)
    val currentReportHere by rememberUpdatedState(reportHere)
    val currentOpenDocument by rememberUpdatedState(onOpenDocument)
    LaunchedEffect(outcomes, host) {
        val pending = Channel<ImportOutcome>(Channel.UNLIMITED)
        // The screen is checked when each file finishes: the library shows the ones it was on.
        launch { outcomes.collect { if (currentReportHere()) pending.send(it) } }
        for (first in pending) {
            val batch = ArrayList<ImportOutcome>()
            batch.add(first)
            var next = pending.tryReceive().getOrNull()
            while (next != null) {
                batch.add(next)
                next = pending.tryReceive().getOrNull()
            }
            for (message in summarizeImportOutcomes(batch)) {
                val openId = message.openableDocumentId()
                val result = host.showSnackbar(
                    message = currentContext.importMessageText(message),
                    actionLabel = openId?.let { currentContext.getString(R.string.app_action_open) },
                    duration = if (openId != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed && openId != null) currentOpenDocument(openId)
            }
        }
    }
}
