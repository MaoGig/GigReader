package com.maogig.gigreader.core.pdf.mupdf

import android.content.ComponentCallbacks2

/** Where a destination URI leads: [page] is -1 when it does not resolve. */
internal data class ResolvedDestination(val page: Int, val yFraction: Float?)

/** A table-of-contents node as MuPDF reports it (title/URI may be missing in broken files). */
internal class RawOutline(val title: String?, val uri: String?, val children: List<RawOutline>)

internal data class ConvertedOutline(
    val title: String,
    val page: Int,
    val yFraction: Float?,
    val children: List<ConvertedOutline>,
)

/** Pure conversion of MuPDF's outline tree, with limits so a hostile file cannot blow the stack or the UI. */
internal object OutlineConverter {
    const val MAX_DEPTH = 32
    const val MAX_ITEMS = 20_000

    /** Converts [nodes]; [resolve] maps a non-blank URI to its page/position (page -1 if unresolvable). */
    fun convert(nodes: List<RawOutline>, resolve: (String) -> ResolvedDestination): List<ConvertedOutline> {
        var budget = MAX_ITEMS
        fun walk(list: List<RawOutline>, depth: Int): List<ConvertedOutline> {
            if (depth >= MAX_DEPTH || budget <= 0) return emptyList()
            val out = ArrayList<ConvertedOutline>(list.size)
            for (n in list) {
                if (budget <= 0) break
                budget--
                val uri = n.uri
                val dest = if (uri.isNullOrBlank()) NO_DESTINATION else resolve(uri)
                out += ConvertedOutline(
                    title = n.title?.trim().orEmpty(),
                    page = if (dest.page >= 0) dest.page else -1,
                    yFraction = if (dest.page >= 0) dest.yFraction else null,
                    children = walk(n.children, depth + 1),
                )
            }
            return out
        }
        return walk(nodes, 0)
    }

    private val NO_DESTINATION = ResolvedDestination(-1, null)
}

/** What a link URI on a page means for the reader. */
internal sealed interface LinkTarget {
    /** Points inside the document; must still be resolved to a page. */
    data class Internal(val uri: String) : LinkTarget

    /** http, https or mailto only. */
    data class External(val uri: String) : LinkTarget

    /** Empty, or a scheme the app never opens (file:, javascript:, intent:, tel:...). */
    data object Ignored : LinkTarget
}

internal object LinkClassifier {
    private val externalSchemes = setOf("http", "https", "mailto")

    fun classify(uri: String?): LinkTarget {
        val u = uri?.trim()
        if (u.isNullOrEmpty()) return LinkTarget.Ignored
        val scheme = schemeOf(u) ?: return LinkTarget.Internal(u)
        return if (scheme.lowercase() in externalSchemes) LinkTarget.External(u) else LinkTarget.Ignored
    }

    /** RFC 3986 scheme (`ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` before the first ':'), or `null`. */
    fun schemeOf(uri: String): String? {
        if (uri.isEmpty() || !uri[0].isAsciiLetter()) return null
        for (i in 1 until uri.length) {
            val c = uri[i]
            if (c.isAsciiLetter() || c in '0'..'9' || c == '+' || c == '-' || c == '.') continue
            return if (c == ':') uri.substring(0, i) else null
        }
        return null
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}

/** Page labels: only worth showing when they differ from the plain page number. */
internal object PageLabels {
    /** `null` when [label] is missing, blank, or just the 1-based number of [pageIndex]. */
    fun clean(label: String?, pageIndex: Int): String? {
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == (pageIndex + 1).toString()) return null
        return trimmed
    }
}

/** How much of MuPDF's global resource store to drop for an Android `onTrimMemory` level. */
internal object StoreTrimPolicy {
    /** `TRIM_MEMORY_RUNNING_CRITICAL`: deprecated in the SDK, still delivered on older releases. */
    private const val RUNNING_CRITICAL = 15

    /**
     * Percent of its current size the store is reduced to (`Context.shrinkStore`); 0 = empty it
     * (`Context.emptyStore`); `null` = leave it alone. Hidden UI halves it, real pressure empties it.
     */
    fun shrinkTo(level: Int): Int? = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 0
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> 50
        level >= RUNNING_CRITICAL -> 0
        else -> null
    }
}
