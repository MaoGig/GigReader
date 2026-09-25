package com.maogig.gigreader.core.model

/**
 * Rectangle in normalized page space: (0,0) is the top-left corner of the unrotated page and
 * (1,1) the bottom-right. Normalized coordinates are independent of zoom, density and render
 * resolution, so an annotation can be drawn at any scale and exported to PDF points by multiplying
 * with the page size.
 */
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    companion object {
        /** Compact, locale-independent encoding: `l,t,r,b;l,t,r,b`. */
        fun encode(rects: List<NormalizedRect>): String =
            rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

        fun decode(encoded: String): List<NormalizedRect> {
            if (encoded.isBlank()) return emptyList()
            return encoded.split(';').mapNotNull { part ->
                val v = part.split(',')
                if (v.size != 4) return@mapNotNull null
                val l = v[0].toFloatOrNull() ?: return@mapNotNull null
                val t = v[1].toFloatOrNull() ?: return@mapNotNull null
                val r = v[2].toFloatOrNull() ?: return@mapNotNull null
                val b = v[3].toFloatOrNull() ?: return@mapNotNull null
                NormalizedRect(l, t, r, b)
            }
        }
    }
}

/**
 * Text markup kinds. Handwriting and drawing are deliberately NOT part of this enum: they will be
 * separate models (stroke based) so that text semantics and ink data never get mixed.
 */
enum class TextMarkupType { HIGHLIGHT, UNDERLINE, STRIKEOUT }

/**
 * A text annotation anchored to the content of a page (not a bitmap drawn over it).
 *
 * [text] is the selected text at creation time and [rects] its geometry. [charStart]/[charEnd]
 * (indices into the page text as reported by the PDF engine) allow the annotation to be re-anchored
 * or exported semantically; they are `null` when the engine could not provide text indices.
 */
data class TextAnnotation(
    override val id: String,
    val documentId: String,
    val page: Int,
    val type: TextMarkupType,
    val text: String,
    val rects: List<NormalizedRect>,
    val color: HighlightColor,
    /** Optional note attached to the highlight. */
    val note: String? = null,
    val charStart: Int? = null,
    val charEnd: Int? = null,
    override val createdAt: Long,
    override val modifiedAt: Long,
    override val version: Long = 1,
    override val deletedAt: Long? = null,
) : Syncable

/** Palette of highlight colors. Stored by [key] so the palette can be restyled without migrations. */
enum class HighlightColor(val key: String, val argb: Long) {
    YELLOW("yellow", 0xFFFFE066),
    GREEN("green", 0xFF8CE99A),
    BLUE("blue", 0xFF74C0FC),
    PINK("pink", 0xFFFAA2C1),
    ORANGE("orange", 0xFFFFC078),
    PURPLE("purple", 0xFFB197FC);

    companion object {
        fun fromKey(key: String): HighlightColor = entries.firstOrNull { it.key == key } ?: YELLOW
    }
}
