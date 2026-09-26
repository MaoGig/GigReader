package com.maogig.gigreader.core.data.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.maogig.gigreader.core.common.Clock
import com.maogig.gigreader.core.common.IdGenerator
import com.maogig.gigreader.core.common.io.CopyCancelledException
import com.maogig.gigreader.core.common.io.DocumentFileStore
import com.maogig.gigreader.core.common.io.PageSizeCodec
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.common.io.copyAndHash
import com.maogig.gigreader.core.common.text.FileNames
import com.maogig.gigreader.core.data.library.CoverStore
import com.maogig.gigreader.core.data.library.sweepLeftovers
import com.maogig.gigreader.core.database.GigReaderDatabase
import com.maogig.gigreader.core.database.SOURCE_MANAGED
import com.maogig.gigreader.core.database.SearchKeys
import com.maogig.gigreader.core.database.entity.DocumentEntity
import com.maogig.gigreader.core.database.entity.PageMetricsEntity
import com.maogig.gigreader.core.model.DocumentType
import com.maogig.gigreader.core.pdf.engine.PdfEngine
import com.maogig.gigreader.core.pdf.engine.PdfOpenException
import com.maogig.gigreader.core.pdf.render.CoverRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class ImportError { NOT_A_PDF, PASSWORD_PROTECTED, CORRUPTED, NO_SPACE, UNREADABLE, CANCELLED }

sealed interface ImportOutcome {
    val displayName: String

    /**
     * The document is in the library: a new one, or an existing document with identical content whose
     * managed file had gone missing and was given back ([documentId] is then that document's id).
     * A password-protected PDF is imported too (page count 0, no cover): the reader asks for it.
     */
    data class Imported(val documentId: String, override val displayName: String) : ImportOutcome

    /**
     * Identical content already exists (possibly in the trash); nothing was added. If the existing
     * document's file was missing, the incoming copy has been given back to it anyway.
     */
    data class Duplicate(val existingId: String, override val displayName: String, val inTrash: Boolean) : ImportOutcome

    data class Failed(override val displayName: String, val error: ImportError) : ImportOutcome
}

data class ImportProgress(
    val active: Boolean = false,
    val currentName: String? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val bytesCopied: Long = 0,
    val bytesTotal: Long = 0,
)

/**
 * Imports documents chosen through the Storage Access Framework into the managed library.
 *
 * Pipeline per file (one file at a time, off the main thread): stream-copy into a temp file while
 * hashing (single read of the source) → duplicate check by SHA-256 → validate with the PDF engine and
 * render the cover (the only time the library opens a PDF on its own) → atomic rename → one database
 * transaction. The worker suspends when the queue is empty, so an idle importer costs nothing.
 *
 * [untitledTitle] supplies the (localized) title of a file whose name yields none.
 */
class ImportManager(
    private val resolver: ContentResolver,
    private val db: GigReaderDatabase,
    private val files: DocumentFileStore,
    private val covers: CoverStore,
    private val engine: PdfEngine,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Uuid,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val untitledTitle: () -> String = { "Untitled" },
) {
    private data class Request(val uri: Uri, val folderId: String?, val generation: Int)

    private val queue = Channel<Request>(Channel.UNLIMITED)
    private var worker: Job? = null

    /**
     * Bumped by [cancelAll]. A request queued in an older generation is cancelled; newer imports are
     * unaffected, so "cancel, then import again" can neither resurrect the file being cancelled nor
     * cancel the new ones (a single boolean flag did both, depending on timing).
     */
    @Volatile
    private var generation = 0

    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    private val _outcomes = MutableSharedFlow<ImportOutcome>(extraBufferCapacity = 64)

    /** One event per finished file (for snackbars / "open" actions). */
    val outcomes: SharedFlow<ImportOutcome> = _outcomes.asSharedFlow()

    /** Queues [uris] for import into [folderId] (null = library root). Returns immediately. */
    @Synchronized
    fun import(uris: List<Uri>, folderId: String?) {
        if (uris.isEmpty()) return
        val current = generation
        _progress.update { it.copy(active = true, total = it.total + uris.size) }
        uris.forEach { queue.trySend(Request(it, folderId, current)) }
        if (worker?.isActive != true) {
            worker = scope.launch(io) {
                // Removes what interrupted imports or permanent deletions may have left behind (temp
                // files, library files without a row, orphan covers) when the importer wakes up, never
                // at app start; see sweepLeftovers for the guards.
                sweepLeftovers(db, files, covers, clock.now())
                for (request in queue) {
                    process(request)
                    // Reset once everything queued so far is done (compareAndSet: a concurrent
                    // import() that just raised `total` must not be clobbered).
                    val snapshot = _progress.value
                    if (snapshot.completed >= snapshot.total) _progress.compareAndSet(snapshot, ImportProgress())
                }
            }
        }
    }

    /** Cancels the file being copied and drops everything queued. */
    @Synchronized
    fun cancelAll() {
        generation++
        var dropped = 0
        while (queue.tryReceive().isSuccess) dropped++
        if (dropped > 0) _progress.update { it.copy(total = it.total - dropped) }
    }

    private suspend fun process(request: Request) {
        val meta = queryMeta(request.uri)
        _progress.update { it.copy(currentName = meta.name, bytesCopied = 0, bytesTotal = meta.size ?: 0) }
        val outcome = try {
            importOne(request, meta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportOutcome.Failed(meta.name, ImportError.UNREADABLE)
        }
        _progress.update { it.copy(completed = it.completed + 1) }
        _outcomes.tryEmit(outcome)
    }

    private data class Meta(val name: String, val size: Long?)

    private fun queryMeta(uri: Uri): Meta {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                    if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
                }
            }
        } catch (_: RuntimeException) {
            // Some providers throw for unsupported columns; fall back to the URI.
        }
        val fallback = name ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return Meta(fallback ?: FileNames.ensureExtension(FileNames.sanitize(untitledTitle()), "pdf"), size)
    }

    private fun Request.isCancelled(): Boolean = this.generation != this@ImportManager.generation

    /** Result of opening an incoming file with the engine. */
    private sealed interface Validation {
        data class Valid(val pageCount: Int, val firstWidth: Float, val firstHeight: Float) : Validation

        /** A valid PDF that needs a password: imported as is, the reader asks for the password. */
        data object PasswordProtected : Validation

        data class Invalid(val error: ImportError) : Validation
    }

    /** Opens [file] with the engine and renders its cover as [coverId] (the only time the library opens a PDF). */
    private suspend fun validate(file: File, coverId: String): Validation {
        val document = try {
            engine.open(file)
        } catch (e: PdfOpenException.PasswordRequired) {
            return Validation.PasswordProtected
        } catch (e: PdfOpenException) {
            return Validation.Invalid(e.toImportError())
        }
        try {
            val pageCount = document.pageCount
            if (pageCount <= 0) return Validation.Invalid(ImportError.CORRUPTED)
            val size = document.pageSize(0)
            try {
                CoverRenderer.renderTo(document, covers.fileFor(coverId), CoverStore.WIDTH_PX)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No cover is not a reason to refuse the document; the library regenerates it later.
            }
            return Validation.Valid(pageCount, size.width, size.height)
        } finally {
            document.close()
        }
    }

    /** A managed document whose file is gone (e.g. deleted outside the app): a re-import can repair it. */
    private fun DocumentEntity.managedFileIsMissing(): Boolean =
        sourceKind == SOURCE_MANAGED && sourcePath.isNotEmpty() &&
            runCatching { !files.fileFor(sourcePath).isFile }.getOrDefault(false)

    private suspend fun importOne(request: Request, meta: Meta): ImportOutcome = withContext(io) {
        // Taken off the queue just before cancelAll() drained it.
        if (request.isCancelled()) return@withContext ImportOutcome.Failed(meta.name, ImportError.CANCELLED)
        if (meta.size != null && files.usableSpace() < meta.size + FREE_SPACE_MARGIN) {
            return@withContext ImportOutcome.Failed(meta.name, ImportError.NO_SPACE)
        }
        val incoming = files.newIncomingFile()
        var keepIncoming = false
        val id = ids.newId()
        try {
            val copy = try {
                val input = resolver.openInputStream(request.uri)
                    ?: return@withContext ImportOutcome.Failed(meta.name, ImportError.UNREADABLE)
                input.use { source ->
                    files.openForWrite(incoming).use { out ->
                        copyAndHash(source, out, isCancelled = { request.isCancelled() }) { copied ->
                            _progress.update { it.copy(bytesCopied = copied) }
                        }
                    }
                }
            } catch (e: CopyCancelledException) {
                return@withContext ImportOutcome.Failed(meta.name, ImportError.CANCELLED)
            } catch (e: IOException) {
                val noSpace = e.message?.contains("ENOSPC") == true || files.usableSpace() < FREE_SPACE_MARGIN
                return@withContext ImportOutcome.Failed(meta.name, if (noSpace) ImportError.NO_SPACE else ImportError.UNREADABLE)
            } catch (e: SecurityException) {
                return@withContext ImportOutcome.Failed(meta.name, ImportError.UNREADABLE)
            }

            if (!looksLikePdf(incoming)) return@withContext ImportOutcome.Failed(meta.name, ImportError.NOT_A_PDF)

            db.documentDao().findByHash(copy.sha256)?.let { existing ->
                if (!existing.managedFileIsMissing()) {
                    return@withContext ImportOutcome.Duplicate(existing.id, meta.name, inTrash = existing.trashedAt != null)
                }
                // Same content as a document whose file went missing: give the file back to it (its
                // row, annotations and reading position stay) instead of a dead-end "duplicate". Its
                // cover is replaced by a fresh render (none for a password-protected file).
                covers.delete(existing.id)
                val validation = validate(incoming, coverId = existing.id)
                if (validation is Validation.Invalid) return@withContext ImportOutcome.Failed(meta.name, validation.error)
                files.commitTo(incoming, existing.sourcePath)
                keepIncoming = true
                return@withContext if (existing.trashedAt == null) {
                    ImportOutcome.Imported(existing.id, existing.title.ifEmpty { FileNames.titleFromFileName(meta.name, untitledTitle()) })
                } else {
                    ImportOutcome.Duplicate(existing.id, meta.name, inTrash = true)
                }
            }

            // Validate with the engine and render the cover while the document is open anyway.
            val validation = validate(incoming, coverId = id)
            if (validation is Validation.Invalid) return@withContext ImportOutcome.Failed(meta.name, validation.error)
            val valid = validation as? Validation.Valid

            val relativePath = files.commit(incoming, id)
            keepIncoming = true // it has been renamed into the library
            val now = clock.now()
            val title = FileNames.titleFromFileName(meta.name, untitledTitle())
            try {
                db.withTransaction {
                    // The target folder may have been trashed or deleted while this file waited in the
                    // queue: a live row under it would show up nowhere, so it goes to the root instead.
                    val folderId = request.folderId?.takeIf { db.folderDao().isLive(it) }
                    db.documentDao().insert(
                        DocumentEntity(
                            id = id,
                            folderId = folderId,
                            title = title,
                            searchTitle = SearchKeys.of(title),
                            fileName = meta.name,
                            type = DocumentType.PDF.name,
                            sourceKind = SOURCE_MANAGED,
                            sourcePath = relativePath,
                            contentHash = copy.sha256,
                            fileSize = copy.bytes,
                            // 0 for a password-protected PDF: set once the reader opens it.
                            pageCount = valid?.pageCount ?: 0,
                            favorite = false,
                            archived = false,
                            annotationCount = 0,
                            createdAt = now,
                            modifiedAt = now,
                            lastOpenedAt = null,
                            version = 1,
                            trashedAt = null,
                            trashRootId = null,
                            deletedAt = null,
                        ),
                    )
                    if (valid != null) {
                        val sizes = PageSizes.uniform(valid.pageCount, valid.firstWidth, valid.firstHeight)
                        db.pageMetricsDao().upsert(
                            PageMetricsEntity(id, valid.pageCount, PageSizeCodec.encode(sizes), measuredCount = 1),
                        )
                    }
                }
            } catch (e: Exception) {
                files.delete(relativePath)
                covers.delete(id)
                throw e
            }
            ImportOutcome.Imported(id, title)
        } finally {
            if (!keepIncoming) {
                incoming.delete()
                covers.delete(id)
            }
        }
    }

    /** Cheap magic-number check ("%PDF-" within the first KiB, as tolerated by PDF readers). */
    private fun looksLikePdf(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(1024)
            val n = input.read(header)
            n > 4 && String(header, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
        }
    } catch (_: IOException) {
        false
    }

    private fun PdfOpenException.toImportError(): ImportError = when (this) {
        is PdfOpenException.PasswordRequired -> ImportError.PASSWORD_PROTECTED
        is PdfOpenException.Corrupted -> ImportError.CORRUPTED
        is PdfOpenException.OutOfMemory -> ImportError.CORRUPTED
        is PdfOpenException.NotSeekable, is PdfOpenException.Unreadable -> ImportError.UNREADABLE
    }

    private companion object {
        const val FREE_SPACE_MARGIN = 50L * 1024 * 1024
    }
}
