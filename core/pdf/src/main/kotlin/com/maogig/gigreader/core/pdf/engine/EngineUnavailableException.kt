package com.maogig.gigreader.core.pdf.engine

/**
 * The engine itself cannot run on this device (e.g. the native library of a bundled engine failed to
 * load), as opposed to a problem with one document ([PdfOpenException]). It is thrown before the
 * engine touches its input, so a descriptor passed to `open` is still open and owned by the caller;
 * [com.maogig.gigreader.core.pdf.FallbackPdfEngine] relies on that to retry with another engine.
 */
class EngineUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
