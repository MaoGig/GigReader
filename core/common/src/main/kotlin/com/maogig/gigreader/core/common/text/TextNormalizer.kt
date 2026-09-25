package com.maogig.gigreader.core.common.text

import java.text.Normalizer

/**
 * Normalization shared by indexing and querying so that "difusão", "Difusao" and "DIFUSÃO" match:
 * Unicode NFD decomposition, removal of combining marks, lower-casing and whitespace collapsing.
 */
object TextNormalizer {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")

    fun normalize(input: String): String {
        if (input.isEmpty()) return input
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return combiningMarks.replace(decomposed, "").lowercase().replace(whitespace, " ").trim()
    }

    /** Splits a normalized query into search terms, dropping empty tokens. */
    fun terms(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }
}

object FileNames {
    private val invalidChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /** Title shown in the library for an imported file: name without extension, never blank. */
    fun titleFromFileName(fileName: String?): String {
        val base = fileName?.substringAfterLast('/')?.trim().orEmpty()
        val withoutExt = if (base.lowercase().endsWith(".pdf")) base.dropLast(4) else base
        return withoutExt.trim().ifEmpty { "Untitled" }
    }

    /** File name safe for export targets (SAF will also de-duplicate names itself). */
    fun sanitize(name: String, fallback: String = "document"): String {
        var cleaned = invalidChars.replace(name, "_").trim().trim('.')
        if (cleaned.length > 120) {
            cleaned = cleaned.take(120)
            if (cleaned.last().isHighSurrogate()) cleaned = cleaned.dropLast(1) // never split an emoji
        }
        return cleaned.ifEmpty { fallback }
    }

    fun ensureExtension(name: String, extension: String): String =
        if (name.lowercase().endsWith(".$extension")) name else "$name.$extension"
}
