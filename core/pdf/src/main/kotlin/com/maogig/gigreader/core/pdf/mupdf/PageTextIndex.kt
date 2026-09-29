package com.maogig.gigreader.core.pdf.mupdf

import java.text.Normalizer

/** A rectangle in normalized (0..1) page coordinates, top-left origin. */
internal data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One search hit: [rects] has one rectangle per text line the hit spans; [textStartIndex] see [PageTextIndex]. */
internal class TextHit(val textStartIndex: Int, val rects: List<NormRect>)

/**
 * The text of one page, folded for searching, with the geometry of every character (pure Kotlin: no
 * MuPDF or Android types, so it is unit-testable on the JVM).
 *
 * Build with [Builder] by feeding the page's characters in reading order, line by line. Folding is
 * the app's TextNormalizer (NFD, strip combining marks, lower-case, collapse whitespace) plus
 * compatibility decomposition, so "fi"/"ﬁ" ligatures and "difusão"/"DIFUSAO" all match; a line break
 * counts as a space, so a phrase split across two lines is found (as two rectangles).
 *
 * "Source index" = position in the sequence of the page's characters where each [Builder.addChar]
 * counts one and each line break between lines counts one; [TextHit.textStartIndex] is that position
 * of the first matched character.
 */
internal class PageTextIndex private constructor(
    private val folded: String,
    /** For each char of [folded]: the source index that produced it. */
    private val sourceOf: IntArray,
    /** Per source char: box in normalized page coordinates (4 floats), NaN left for line breaks. */
    private val boxes: FloatArray,
    private val lineOf: IntArray,
) {
    /**
     * All non-overlapping hits of [query] in reading order (empty when the folded query is empty).
     * Matching resumes after the end of each hit: "aa" occurs twice in "aaaa" and once in "aaa".
     */
    fun find(query: String): List<TextHit> {
        val needle = fold(query)
        if (needle.isEmpty() || needle.length > folded.length) return emptyList()
        val hits = ArrayList<TextHit>()
        var from = 0
        while (true) {
            val start = folded.indexOf(needle, from)
            if (start < 0) break
            val end = start + needle.length // exclusive
            hits += hit(sourceOf[start], sourceOf[end - 1])
            from = end
        }
        return hits
    }

    private fun hit(firstSource: Int, lastSource: Int): TextHit {
        val rects = ArrayList<NormRect>(1)
        var line = -1
        var l = 0f
        var t = 0f
        var r = 0f
        var b = 0f
        for (i in firstSource..lastSource) {
            if (boxes[i * 4].isNaN()) continue // line break / char without geometry
            val cl = boxes[i * 4]
            val ct = boxes[i * 4 + 1]
            val cr = boxes[i * 4 + 2]
            val cb = boxes[i * 4 + 3]
            if (lineOf[i] != line) {
                if (line >= 0) rects += NormRect(l, t, r, b)
                line = lineOf[i]
                l = cl; t = ct; r = cr; b = cb
            } else {
                if (cl < l) l = cl
                if (ct < t) t = ct
                if (cr > r) r = cr
                if (cb > b) b = cb
            }
        }
        if (line >= 0) rects += NormRect(l, t, r, b)
        return TextHit(textStartIndex = firstSource, rects = rects)
    }

    /**
     * Collects characters. [originX]/[originY]/[width]/[height] describe the page in the same
     * coordinate space as the character boxes; boxes are converted to normalized coordinates and
     * clamped to 0..1.
     */
    class Builder(
        private val originX: Float,
        private val originY: Float,
        private val width: Float,
        private val height: Float,
    ) {
        private val folded = StringBuilder()
        private var sourceOf = IntArray(INITIAL)
        private var boxes = FloatArray(INITIAL * 4)
        private var lineOf = IntArray(INITIAL)
        private var sourceCount = 0
        private var foldedCount = 0
        private var line = -1
        private var linesStarted = 0

        /** Starts a new text line. Between two lines a separator (a space) is implied. */
        fun beginLine() {
            if (linesStarted > 0) {
                val source = nextSource()
                lineOf[source] = line
                appendFolded(' ', source)
            }
            linesStarted++
            line++
        }

        /**
         * Adds one character of the current line with its box (any two opposite corners; typically
         * the bounds of the glyph quad). [codePoint] is the character's Unicode code point.
         */
        fun addChar(codePoint: Int, left: Float, top: Float, right: Float, bottom: Float) {
            if (line < 0) beginLine()
            val source = nextSource()
            lineOf[source] = line
            val w = if (width > 0f) width else 1f
            val h = if (height > 0f) height else 1f
            val b = source * 4
            boxes[b] = clamp01((minOf(left, right) - originX) / w)
            boxes[b + 1] = clamp01((minOf(top, bottom) - originY) / h)
            boxes[b + 2] = clamp01((maxOf(left, right) - originX) / w)
            boxes[b + 3] = clamp01((maxOf(top, bottom) - originY) / h)
            if (isSpace(codePoint)) {
                appendFolded(' ', source)
            } else {
                val f = foldOne(codePoint)
                for (ch in f) appendFolded(ch, source)
            }
        }

        fun build(): PageTextIndex = PageTextIndex(
            folded = folded.toString(),
            sourceOf = sourceOf.copyOf(foldedCount),
            boxes = boxes.copyOf(sourceCount * 4),
            lineOf = lineOf.copyOf(sourceCount),
        )

        private fun nextSource(): Int {
            val i = sourceCount++
            if (i >= lineOf.size) {
                val n = lineOf.size * 2
                lineOf = lineOf.copyOf(n)
                boxes = boxes.copyOf(n * 4)
            }
            boxes[i * 4] = Float.NaN // set by addChar; stays NaN for line breaks
            return i
        }

        private fun appendFolded(c: Char, source: Int) {
            // Collapse runs of whitespace into one space and never start with one, like TextNormalizer.
            if (c == ' ' && (foldedCount == 0 || folded[foldedCount - 1] == ' ')) return
            if (foldedCount >= sourceOf.size) sourceOf = sourceOf.copyOf(sourceOf.size * 2)
            folded.append(c)
            sourceOf[foldedCount++] = source
        }

        private companion object {
            const val INITIAL = 256

            fun clamp01(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
        }
    }

    companion object {
        private val combiningMarks = Regex("\\p{Mn}+")
        private val whitespace = Regex("\\s+")

        internal fun isSpace(codePoint: Int): Boolean =
            Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

        /** Folds a whole query: same rules as characters, whitespace collapsed and trimmed. */
        internal fun fold(input: String): String {
            if (input.isEmpty()) return input
            val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)
            return combiningMarks.replace(decomposed, "").lowercase()
                .let { combiningMarks.replace(it, "") }
                .replace(whitespace, " ")
                .trim()
        }

        /** Folds one character (may yield several chars, e.g. a ligature or "İ"; may yield none). */
        internal fun foldOne(codePoint: Int): String {
            val s = String(Character.toChars(codePoint))
            val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
            val stripped = combiningMarks.replace(decomposed, "").lowercase()
            return combiningMarks.replace(stripped, "")
        }
    }
}
