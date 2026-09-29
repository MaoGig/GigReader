package com.maogig.gigreader.feature.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.content.FileProvider
import com.maogig.gigreader.core.data.importer.ImportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal const val PDF_MIME_TYPE = "application/pdf"

/** Authority of the app's FileProvider for managed library files (declared by the app module). */
internal fun Context.libraryFileProviderAuthority(): String = packageName + ".files"

/** Localized text of a [LibraryMessage]. Resolved outside composition (snackbars are effects). */
internal fun Context.libraryMessageText(message: LibraryMessage): String {
    val res = resources
    return when (message) {
        is LibraryMessage.Moved -> res.getQuantityString(R.plurals.library_msg_moved, message.count, message.count)
        LibraryMessage.MoveCycle -> getString(R.string.library_msg_move_cycle)
        is LibraryMessage.Trashed -> res.getQuantityString(R.plurals.library_msg_trashed, message.count, message.count)
        is LibraryMessage.Favorited ->
            res.getQuantityString(R.plurals.library_msg_favorited, message.count, message.count)
        is LibraryMessage.Unfavorited ->
            res.getQuantityString(R.plurals.library_msg_unfavorited, message.count, message.count)
        LibraryMessage.Renamed -> getString(R.string.library_msg_renamed)
        LibraryMessage.Duplicated -> getString(R.string.library_msg_duplicated)
        LibraryMessage.ShareNothing -> getString(R.string.library_msg_share_nothing)
        LibraryMessage.ShareFailed -> getString(R.string.library_msg_share_failed)
        LibraryMessage.Failed -> getString(R.string.library_msg_error)
        is LibraryMessage.Imported -> getString(R.string.library_msg_imported, message.title)
        is LibraryMessage.ImportedMany ->
            res.getQuantityString(R.plurals.library_msg_imported_many, message.count, message.count)
        is LibraryMessage.AlreadyInLibrary -> getString(
            if (message.inTrash) R.string.library_msg_duplicate_in_trash else R.string.library_msg_duplicate,
        )
        is LibraryMessage.AlreadyInLibraryMany ->
            res.getQuantityString(R.plurals.library_msg_duplicate_many, message.count, message.count)
        is LibraryMessage.ImportFailed -> getString(importErrorText(message.error), message.name)
        is LibraryMessage.ImportFailedMany ->
            res.getQuantityString(R.plurals.library_msg_failed_many, message.count, message.count)
        is LibraryMessage.Restored -> getString(
            R.string.library_msg_restored,
            message.title.ifBlank { getString(R.string.library_untitled_note) },
        )
        LibraryMessage.RestoreWithFolder -> getString(R.string.library_msg_restore_with_folder)
        LibraryMessage.DeletedForever -> getString(R.string.library_msg_deleted_forever)
        LibraryMessage.TrashEmptied -> getString(R.string.library_msg_trash_emptied)
    }
}

/** Friendly explanation per import error (§50: never a stack trace). */
private fun importErrorText(error: ImportError): Int = when (error) {
    ImportError.NOT_A_PDF -> R.string.library_msg_failed_not_pdf
    ImportError.PASSWORD_PROTECTED -> R.string.library_msg_failed_password
    ImportError.CORRUPTED -> R.string.library_msg_failed_corrupted
    ImportError.NO_SPACE -> R.string.library_msg_failed_no_space
    ImportError.UNREADABLE, ImportError.CANCELLED -> R.string.library_msg_failed_unreadable
}

/** Document a snackbar can open ("Imported X · Open"), if any. */
internal fun LibraryMessage.openableDocumentId(): String? = when (this) {
    is LibraryMessage.Imported -> documentId
    is LibraryMessage.AlreadyInLibrary -> if (inTrash) null else documentId
    else -> null
}

/** Trashed document a snackbar can bring back ("Already in your library (in the trash) · Restore"). */
internal fun LibraryMessage.restorableDocumentId(): String? =
    if (this is LibraryMessage.AlreadyInLibrary && inTrash) documentId else null

/**
 * Shows [message] and runs its action: "Undo" for undoable edits, "Open" for imports, "Restore" for
 * a duplicate that sits in the trash. Suspends until the snackbar is dismissed; cancelling the
 * caller dismisses it.
 */
internal suspend fun showLibraryMessage(
    host: SnackbarHostState,
    context: Context,
    message: LibraryMessage,
    undoable: Boolean,
    onUndo: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onRestoreDocument: (String) -> Unit = {},
) {
    val openId = message.openableDocumentId()
    val restoreId = message.restorableDocumentId()
    val actionLabel = when {
        undoable -> context.getString(R.string.library_action_undo)
        openId != null -> context.getString(R.string.library_action_open)
        restoreId != null -> context.getString(R.string.library_action_restore)
        else -> null
    }
    val result = host.showSnackbar(
        message = context.libraryMessageText(message),
        actionLabel = actionLabel,
        duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) {
        when {
            undoable -> onUndo()
            openId != null -> onOpenDocument(openId)
            restoreId != null -> onRestoreDocument(restoreId)
        }
    }
}

/**
 * Shares managed PDFs through the app's FileProvider with a read grant. Returns `false` when the
 * files cannot be exposed (provider missing) or no app can receive them. Building the URIs
 * canonicalizes paths and parses the provider's XML: done on [Dispatchers.IO], never on the main
 * thread; only the chooser is started from the caller's (main) thread.
 */
internal suspend fun Context.shareDocuments(files: List<File>): Boolean {
    val authority = libraryFileProviderAuthority()
    val uris = try {
        withContext(Dispatchers.IO) {
            files.mapTo(ArrayList<Uri>(files.size)) { FileProvider.getUriForFile(this@shareDocuments, authority, it) }
        }
    } catch (e: IllegalArgumentException) {
        return false
    }
    return try {
        startActivity(Intent.createChooser(shareIntent(uris), getString(R.string.library_share_title)))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private fun shareIntent(uris: ArrayList<Uri>): Intent {
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = PDF_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = PDF_MIME_TYPE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return send
}
