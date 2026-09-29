package com.maogig.gigreader.core.common

import java.util.UUID

/** Injected time source so repositories and tests agree on "now". */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Uuid = IdGenerator { UUID.randomUUID().toString() }
    }
}

/**
 * Escapes `%`, `_` and the escape character itself for a SQL `LIKE ... ESCAPE '\'` pattern, so user
 * input like "50%" is matched literally.
 */
fun escapeLike(query: String): String = buildString(query.length) {
    for (c in query) {
        if (c == '\\' || c == '%' || c == '_') append('\\')
        append(c)
    }
}
