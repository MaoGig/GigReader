package com.maogig.gigreader.core.pdf

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * System-trace sections consumed by Macrobenchmark's TraceSectionMetric (see
 * docs/PERFORMANCE_AND_POWER.md). They cost a few nanoseconds when no trace is being recorded, so
 * they stay in release builds.
 *
 * [async] is used for work that suspends and may resume on another thread (synchronous sections
 * must begin and end on the same thread).
 */
object PerfTrace {
    const val OPEN_DOCUMENT = "GigReader.openDocument"
    const val RENDER_PAGE = "GigReader.renderPage"
    const val CLOSE_DOCUMENT = "GigReader.closeDocument"
    const val FIRST_PAGE = "GigReader.firstPageRendered"

    private val cookies = AtomicInteger()

    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    suspend fun <T> async(name: String, block: suspend () -> T): T {
        val cookie = begin(name)
        try {
            return block()
        } finally {
            end(name, cookie)
        }
    }

    /** Starts an async section and returns its cookie; pair with [end]. */
    fun begin(name: String): Int {
        val cookie = cookies.incrementAndGet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.beginAsyncSection(name, cookie)
        return cookie
    }

    fun end(name: String, cookie: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.endAsyncSection(name, cookie)
    }
}
