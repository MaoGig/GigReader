package com.maogig.gigreader.core.pdf.mupdf

import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.maogig.gigreader.core.pdf.engine.EngineUnavailableException
import com.maogig.gigreader.core.pdf.engine.PdfDocument
import com.maogig.gigreader.core.pdf.engine.PdfEngine
import com.maogig.gigreader.core.pdf.engine.PdfOpenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Backend over bundled MuPDF (AGPL-3.0, see docs/PDF_ENGINE_COMPARISON.md): search, text, outline,
 * links, passwords on every API level. The native library is loaded on the first `open`, on the
 * engine lane, never at process start; if that fails [EngineUnavailableException] is thrown (before
 * the input is touched) so [com.maogig.gigreader.core.pdf.FallbackPdfEngine] can switch engines.
 *
 * Threading: MuPDF documents are not thread-safe, but different documents are independent (the
 * global resource store is internally locked). So each document gets its OWN serial lane (a
 * single-parallelism view of the IO pool): a cover render during import does not wait for the reader
 * to finish a tile, while one document never runs two MuPDF calls at once. No thread is pinned:
 * when a lane is idle nothing is running.
 */
class MuPdfEngine(
    private val laneFactory: () -> CoroutineDispatcher = { Dispatchers.IO.limitedParallelism(1) },
) : PdfEngine {
    override val id: String = "mupdf"

    override val supportsPasswords: Boolean get() = true

    @Volatile
    private var loaded = false

    @Volatile
    private var loadFailure: Throwable? = null

    override suspend fun open(file: File, password: String?): PdfDocument =
        openOnLane(onNotStarted = {}) { lane -> fromFile(file, password, lane) }

    override suspend fun open(descriptor: ParcelFileDescriptor, password: String?): PdfDocument =
        openOnLane(onNotStarted = { closeQuietly(descriptor) }) { lane -> fromDescriptor(descriptor, password, lane) }

    /**
     * Frees memory held by MuPDF's global resource store for an `onTrimMemory` [level]. Does nothing
     * (and never loads the native library) when MuPDF has not been used yet.
     */
    fun trimMemory(level: Int) {
        if (!loaded) return
        val target = StoreTrimPolicy.shrinkTo(level) ?: return
        try {
            if (target == 0) Context.emptyStore() else Context.shrinkStore(target)
        } catch (_: RuntimeException) {
            // Best effort: a failing trim must never crash the process while it is short of memory.
        }
    }

    /**
     * Runs [block] on a fresh lane. Like the framework engine: `withContext` may drop a finished
     * result when the caller is cancelled, which would leak a native document and its descriptor,
     * so a document that was opened but not delivered is closed here.
     */
    private suspend fun openOnLane(
        onNotStarted: () -> Unit,
        block: (CoroutineDispatcher) -> MuPdfDocument,
    ): PdfDocument {
        val lane = laneFactory()
        var opened: MuPdfDocument? = null
        try {
            return withContext(lane) {
                val document = block(lane)
                opened = document
                document
            }
        } catch (e: CancellationException) {
            val document = opened
            if (document != null) {
                document.close()
            } else {
                onNotStarted()
            }
            throw e
        }
    }

    /** Loads the native library once; later calls are a volatile read. Throws [EngineUnavailableException]. */
    private fun ensureLoaded() {
        if (loaded) return
        loadFailure?.let { throw EngineUnavailableException("MuPDF native library is unavailable", it) }
        try {
            Context.init()
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            fail(e)
        } catch (e: ExceptionInInitializerError) {
            fail(e)
        } catch (e: NoClassDefFoundError) {
            fail(e)
        } catch (e: RuntimeException) {
            fail(e) // "cannot initialize mupdf library"
        }
    }

    private fun fail(cause: Throwable): Nothing {
        loadFailure = cause
        throw EngineUnavailableException("MuPDF native library is unavailable", cause)
    }

    /** Must run on [lane]. On failure nothing stays open, except that an unavailable engine leaves [File] untouched. */
    private fun fromFile(file: File, password: String?, lane: CoroutineDispatcher): MuPdfDocument {
        ensureLoaded()
        if (!file.isFile || !file.canRead()) throw PdfOpenException.Unreadable()
        // By path when the extension lets MuPDF recognise the format (the fast, native-I/O path);
        // otherwise (import temp files are ".part") through a stream with the format forced.
        return if (file.name.endsWith(".pdf", ignoreCase = true)) {
            create(password, lane, release = {}) { Document.openDocument(file.path) }
        } else {
            val stream = FileInputStream(file)
            try {
                val channel = stream.channel
                create(password, lane, release = { closeQuietly(stream) }) {
                    Document.openDocument(ChannelSeekableStream(channel), PDF_MAGIC)
                }
            } catch (e: Throwable) {
                closeQuietly(stream)
                throw e
            }
        }
    }

    /** Must run on [lane]. Takes ownership of [descriptor] once the native library is available. */
    private fun fromDescriptor(descriptor: ParcelFileDescriptor, password: String?, lane: CoroutineDispatcher): MuPdfDocument {
        ensureLoaded() // before anything is consumed: the fallback engine reuses the descriptor
        val channel: FileChannel
        try {
            channel = FileInputStream(descriptor.fileDescriptor).channel
        } catch (e: RuntimeException) {
            closeQuietly(descriptor)
            throw PdfOpenException.Unreadable(e)
        }
        if (!ChannelSeekableStream.isSeekable(channel)) {
            closeQuietly(descriptor)
            throw PdfOpenException.NotSeekable()
        }
        // The stream over the descriptor is never closed itself (that would close the fd twice);
        // closing the descriptor is what releases it.
        return create(password, lane, release = { closeQuietly(descriptor) }) {
            Document.openDocument(ChannelSeekableStream(channel), PDF_MAGIC)
        }
    }

    /** Opens, authenticates and wraps; maps every MuPDF failure. [release] runs on failure and on close. */
    private fun create(
        password: String?,
        lane: CoroutineDispatcher,
        release: () -> Unit,
        open: () -> Document,
    ): MuPdfDocument {
        var document: Document? = null
        try {
            document = open()
            if (document.needsPassword()) {
                if (password.isNullOrEmpty() || !document.authenticatePassword(password)) {
                    throw PdfOpenException.PasswordRequired()
                }
            }
            val pages = document.countPages()
            if (pages <= 0) throw PdfOpenException.Corrupted()
            return MuPdfDocument(document, pages, lane, release)
        } catch (e: Throwable) {
            document?.destroy()
            release()
            throw mapOpenFailure(e)
        }
    }

    private fun mapOpenFailure(e: Throwable): Throwable = when (e) {
        is PdfOpenException, is CancellationException -> e
        is OutOfMemoryError -> PdfOpenException.OutOfMemory(e)
        is IOException -> PdfOpenException.Unreadable(e)
        is RuntimeException -> PdfOpenException.Corrupted(e)
        else -> e // other Errors (LinkageError...) must not be disguised as a bad document
    }

    private companion object {
        const val PDF_MAGIC = "application/pdf"

        fun closeQuietly(d: ParcelFileDescriptor) {
            try {
                d.close()
            } catch (_: IOException) {
            }
        }

        fun closeQuietly(s: java.io.Closeable) {
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }
}
