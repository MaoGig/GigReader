package com.maogig.gigreader.core.pdf.mupdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageTextIndexTest {
    /** Lays out [lines] on a 100 x 100 page: each char 10 wide, each line 10 high (line i at y = 10 * i). */
    private fun index(vararg lines: String): PageTextIndex {
        val b = PageTextIndex.Builder(0f, 0f, 100f, 100f)
        lines.forEachIndexed { row, text ->
            b.beginLine()
            var col = 0
            text.codePoints().forEach { cp ->
                b.addChar(cp, col * 10f, row * 10f, col * 10f + 10f, row * 10f + 10f)
                col++
            }
        }
        return b.build()
    }

    @Test
    fun findsCaseInsensitively() {
        val hits = index("Hello World").find("WORLD")
        assertEquals(1, hits.size)
        assertEquals(6, hits[0].textStartIndex)
        assertEquals(listOf(NormRect(0.6f, 0f, 1f, 0.1f)), hits[0].rects)
    }

    @Test
    fun findsAccentInsensitivelyBothWays() {
        assertEquals(1, index("A difusão rápida").find("DIFUSAO").size)
        assertEquals(1, index("A difusao rapida").find("difusão").size)
        assertEquals(1, index("São João").find("sao joao").size)
    }

    @Test
    fun decomposedAccentsInThePageMatchToo() {
        // "e" followed by a combining acute (two chars in the page stream).
        val b = PageTextIndex.Builder(0f, 0f, 100f, 100f)
        b.beginLine()
        b.addChar('c'.code, 0f, 0f, 10f, 10f)
        b.addChar('a'.code, 10f, 0f, 20f, 10f)
        b.addChar('f'.code, 20f, 0f, 30f, 10f)
        b.addChar('e'.code, 30f, 0f, 40f, 10f)
        b.addChar(0x0301, 30f, 0f, 40f, 10f)
        val hits = b.build().find("café")
        assertEquals(1, hits.size)
        assertEquals(0.4f, hits[0].rects.single().right)
    }

    @Test
    fun ligatureCharacterMatchesItsLetters() {
        val hits = index("o ﬁm", "ﬁnal").find("fi")
        assertEquals(2, hits.size)
        // "ﬁ" is one source char: the box covers the whole glyph.
        assertEquals(NormRect(0.2f, 0f, 0.3f, 0.1f), hits[0].rects.single())
        assertEquals(1, index("shuffle oﬃce").find("office").size)
    }

    @Test
    fun matchInsideALigatureBoxesTheWholeGlyph() {
        val hits = index("ﬁnd").find("in")
        assertEquals(1, hits.size)
        assertEquals(NormRect(0f, 0f, 0.2f, 0.1f), hits[0].rects.single())
    }

    @Test
    fun phraseAcrossALineBreakGivesOneRectPerLine() {
        val hits = index("the quick", "brown fox").find("quick brown")
        assertEquals(1, hits.size)
        val rects = hits[0].rects
        assertEquals(2, rects.size)
        assertEquals(NormRect(0.4f, 0f, 0.9f, 0.1f), rects[0])
        assertEquals(NormRect(0f, 0.1f, 0.5f, 0.2f), rects[1])
        // "the quick" is 9 chars + 1 separator: "brown" starts at 10; "quick" at 4.
        assertEquals(4, hits[0].textStartIndex)
    }

    @Test
    fun separatorCountsInTextStartIndex() {
        val hits = index("ab", "cd").find("cd")
        assertEquals(3, hits.single().textStartIndex)
    }

    @Test
    fun repeatedAndCollapsedWhitespaceInPageAndQuery() {
        val hits = index("a   b").find("a  b")
        assertEquals(1, hits.size)
        assertEquals(NormRect(0f, 0f, 0.5f, 0.1f), hits[0].rects.single())
        assertEquals(1, index("a b").find("a b").size)
    }

    @Test
    fun matchesDoNotOverlap() {
        assertEquals(2, index("aaaa").find("aa").size)
        assertEquals(1, index("aaa").find("aa").size)
        val starts = index("abababab").find("abab").map { it.textStartIndex }
        assertEquals(listOf(0, 4), starts)
    }

    @Test
    fun emptyOrBlankQueryFindsNothing() {
        val idx = index("some text")
        assertTrue(idx.find("").isEmpty())
        assertTrue(idx.find("   ").isEmpty())
    }

    @Test
    fun queryLongerThanTextOrAbsentFindsNothing() {
        assertTrue(index("abc").find("abcdef").isEmpty())
        assertTrue(index("abc").find("x").isEmpty())
        assertTrue(PageTextIndex.Builder(0f, 0f, 1f, 1f).build().find("a").isEmpty())
    }

    @Test
    fun queryWithSurroundingSpacesIsTrimmed() {
        assertEquals(1, index("a b c").find("  b ").size)
    }

    @Test
    fun rectsAreClampedToThePage() {
        val b = PageTextIndex.Builder(10f, 10f, 100f, 50f)
        b.beginLine()
        b.addChar('x'.code, 0f, 0f, 120f, 70f) // sticks out on every side
        val r = b.build().find("x").single().rects.single()
        assertEquals(NormRect(0f, 0f, 1f, 1f), r)
    }

    @Test
    fun pageOriginIsSubtracted() {
        val b = PageTextIndex.Builder(50f, 100f, 100f, 200f)
        b.beginLine()
        b.addChar('x'.code, 60f, 120f, 70f, 140f)
        val r = b.build().find("x").single().rects.single()
        assertEquals(0.1f, r.left, 1e-6f)
        assertEquals(0.2f, r.right, 1e-6f)
        assertEquals(0.1f, r.top, 1e-6f)
        assertEquals(0.2f, r.bottom, 1e-6f)
    }

    @Test
    fun cornersInAnyOrderAreOrdered() {
        val b = PageTextIndex.Builder(0f, 0f, 100f, 100f)
        b.beginLine()
        b.addChar('x'.code, 20f, 30f, 10f, 20f)
        assertEquals(NormRect(0.1f, 0.2f, 0.2f, 0.3f), b.build().find("x").single().rects.single())
    }

    @Test
    fun surrogatePairsAreSingleCharacters() {
        val hits = index("a😀b").find("b")
        assertEquals(2, hits.single().textStartIndex)
    }

    @Test
    fun foldMatchesTheAppNormalizer() {
        for (s in listOf("Difusão  Rápida", "ÇA VA", "  x  y ", "Ünï")) {
            assertEquals(com.maogig.gigreader.core.common.text.TextNormalizer.normalize(s), PageTextIndex.fold(s))
        }
    }
}
