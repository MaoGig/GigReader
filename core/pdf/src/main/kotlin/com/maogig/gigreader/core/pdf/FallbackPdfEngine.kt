package com.maogig.gigreader.core.pdf

import android.os.ParcelFileDescriptor
import com.maogig.gigreader.core.pdf.engine.EngineUnavailableException
import com.maogig.gigreader.core.pdf.engine.PdfDocument
import com.maogig.gigreader.core.pdf.engine.PdfEngine
import java.io.File

/**
 * Uses [primary] (the bundled MuPDF) and switches, once and for good, to [fallback] (the framework
 * renderer) when the primary reports that it cannot run at all: [EngineUnavailableException], or a
 * [LinkageError] (UnsatisfiedLinkError, ExceptionInInitializerError, NoClassDefFoundError after a
 * failed static initializer) escaping from it.
 *
 * The primary is tried until the first such failure and never again after it, so a broken native
 * library costs one failed attempt per process. Failures about a document (corrupted, password) are
 * NOT retried on the fallback: another engine would only hide the problem and give a second,
 * different behaviour for the same file.
 *
 * Documents already opened keep the engine that opened them.
 */
class FallbackPdfEngine(
    private val primary: PdfEngine,
    private val fallback: PdfEngine,
) : PdfEngine {
    @Volatile
    private var primaryFailed = false

    /** Whether the fallback is in use (diagnostics). `false` until the primary has failed. */
    val isUsingFallback: Boolean get() = primaryFailed

    private val active: PdfEngine get() = if (primaryFailed) fallback else primary

    /** Id of the engine in use; before the first `open` this is the primary's (the one that will be tried). */
    override val id: String get() = active.id

    override val supportsPasswords: Boolean get() = active.supportsPasswords

    override suspend fun open(file: File, password: String?): PdfDocument = withFallback { it.open(file, password) }

    override suspend fun open(descriptor: ParcelFileDescriptor, password: String?): PdfDocument =
        withFallback { it.open(descriptor, password) }

    private suspend inline fun withFallback(open: (PdfEngine) -> PdfDocument): PdfDocument {
        if (!primaryFailed) {
            try {
                return open(primary)
            } catch (_: EngineUnavailableException) {
                primaryFailed = true
            } catch (_: LinkageError) {
                primaryFailed = true
            }
        }
        return open(fallback)
    }
}
