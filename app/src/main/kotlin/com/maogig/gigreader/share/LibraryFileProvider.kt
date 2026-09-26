package com.maogig.gigreader.share

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.maogig.gigreader.GigReaderApplication
import com.maogig.gigreader.core.common.text.FileNames
import kotlinx.coroutines.runBlocking

/**
 * The app's FileProvider for managed library files (share / open in another app), declared in the
 * manifest with the same authority and paths as before.
 *
 * Library files are stored as `<documentId>.pdf`, so a plain FileProvider would make the recipient
 * (mail, Drive, …) show a meaningless UUID. This one reports the document's title as
 * [OpenableColumns.DISPLAY_NAME] (sanitized, with `.pdf`). Everything else — paths, grants, MIME
 * type, opening the file — is FileProvider's.
 */
class LibraryFileProvider : FileProvider() {

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val base: Cursor? = super.query(uri, projection, selection, selectionArgs, sortOrder)
        if (base == null) return MatrixCursor(arrayOf<String>(), 0)
        val nameIndex = base.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex < 0) return base
        val displayName = documentDisplayName(uri) ?: return base
        return base.use { withValue(it, nameIndex, displayName) }
    }

    /**
     * "<title>.pdf" of the library document behind [uri], or null to keep the file's own name (not a
     * library document, unknown or deleted row, database error, app not initialized yet).
     *
     * The row is read with a blocking query. Other apps call providers on a binder thread, never on
     * this app's main thread; a call that does arrive on the main thread skips the lookup.
     */
    private fun documentDisplayName(uri: Uri): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val fileName = uri.lastPathSegment ?: return null
        if (!fileName.endsWith(PDF_SUFFIX, ignoreCase = true)) return null
        val documentId = fileName.dropLast(PDF_SUFFIX.length)
        val container = (context?.applicationContext as? GigReaderApplication)?.containerOrNull ?: return null
        val title = try {
            runBlocking { container.database.documentDao().getById(documentId) }
                ?.takeIf { it.deletedAt == null }
                ?.title
        } catch (e: RuntimeException) {
            null // SQLite errors: the recipient still gets the file, under its stored name
        } ?: return null
        return FileNames.ensureExtension(FileNames.sanitize(title), "pdf")
    }

    private fun withValue(source: Cursor, index: Int, value: String): Cursor {
        val copy = MatrixCursor(source.columnNames, source.count)
        while (source.moveToNext()) {
            val row = arrayOfNulls<Any>(source.columnCount)
            for (i in row.indices) {
                row[i] = if (i == index) {
                    value
                } else {
                    when (source.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> source.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> source.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> source.getBlob(i)
                        else -> source.getString(i)
                    }
                }
            }
            copy.addRow(row)
        }
        return copy
    }

    private companion object {
        const val PDF_SUFFIX = ".pdf"
    }
}
