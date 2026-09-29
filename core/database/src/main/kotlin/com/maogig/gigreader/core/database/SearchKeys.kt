package com.maogig.gigreader.core.database

import com.maogig.gigreader.core.common.escapeLike
import com.maogig.gigreader.core.common.text.TextNormalizer

/**
 * Values of the `search_*` columns and the patterns matched against them. Both sides go through
 * [TextNormalizer], so the search ignores case and accents for any script ("relatorio", "Relatório"
 * and "RELATÓRIO" are equal), which SQLite's LIKE alone only does for ASCII case.
 */
object SearchKeys {
    /** `folders.search_name` / `documents.search_title`. */
    fun of(text: String): String = TextNormalizer.normalize(text)

    /**
     * `notes.search_text`: normalized title and body separated by a newline. Normalized queries
     * never contain a newline, so a match cannot start in the title and end in the body.
     */
    fun note(title: String, body: String): String = "${of(title)}\n${of(body)}"

    /**
     * Argument for `search_* LIKE '%' || :query || '%' ESCAPE '\'`: the normalized query with
     * `%`, `_` and `\` escaped. Empty when the query has no searchable characters.
     */
    fun likeQuery(query: String): String = escapeLike(of(query))
}
