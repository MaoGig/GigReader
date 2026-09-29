package com.maogig.gigreader.core.pdf.mupdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlineAndLinksTest {
    private fun raw(title: String?, uri: String?, vararg children: RawOutline) = RawOutline(title, uri, children.toList())

    private val resolver: (String) -> ResolvedDestination = { uri ->
        when {
            uri.startsWith("#page=") -> ResolvedDestination(uri.removePrefix("#page=").toInt(), 0.5f)
            else -> ResolvedDestination(-1, 0.9f)
        }
    }

    @Test
    fun outlineTreeIsConvertedWithPagesAndPositions() {
        val tree = listOf(
            raw("Chapter 1", "#page=2", raw("Section 1.1", "#page=3"), raw("Section 1.2", "#page=4")),
            raw("Chapter 2", "#page=9"),
        )
        val out = OutlineConverter.convert(tree, resolver)
        assertEquals(2, out.size)
        assertEquals("Chapter 1", out[0].title)
        assertEquals(2, out[0].page)
        assertEquals(0.5f, out[0].yFraction)
        assertEquals(listOf("Section 1.1", "Section 1.2"), out[0].children.map { it.title })
        assertEquals(listOf(3, 4), out[0].children.map { it.page })
        assertEquals(9, out[1].page)
    }

    @Test
    fun unresolvableAndMissingDestinationsGiveMinusOneAndNoPosition() {
        val out = OutlineConverter.convert(
            listOf(raw("A", null), raw("B", ""), raw("C", "nowhere"), raw(null, "#page=1")),
            resolver,
        )
        assertEquals(listOf(-1, -1, -1, 1), out.map { it.page })
        assertTrue(out.take(3).all { it.yFraction == null })
        assertEquals("", out[3].title)
    }

    @Test
    fun headingWithoutDestinationKeepsItsChildren() {
        val out = OutlineConverter.convert(listOf(raw("Part I", null, raw("Ch 1", "#page=5"))), resolver)
        assertEquals(-1, out[0].page)
        assertEquals(5, out[0].children.single().page)
    }

    @Test
    fun titlesAreTrimmed() {
        assertEquals("Intro", OutlineConverter.convert(listOf(raw("  Intro \n", null)), resolver).single().title)
    }

    @Test
    fun depthAndSizeAreLimited() {
        var node = raw("leaf", null)
        repeat(100) { node = raw("n", null, node) }
        var depth = 0
        var cur: List<ConvertedOutline> = OutlineConverter.convert(listOf(node), resolver)
        while (cur.isNotEmpty()) {
            depth++
            cur = cur.single().children
        }
        assertEquals(OutlineConverter.MAX_DEPTH, depth)

        val many = List(OutlineConverter.MAX_ITEMS + 500) { raw("x", null) }
        assertEquals(OutlineConverter.MAX_ITEMS, OutlineConverter.convert(many, resolver).size)
    }

    @Test
    fun emptyOutline() {
        assertTrue(OutlineConverter.convert(emptyList(), resolver).isEmpty())
    }

    @Test
    fun webAndMailLinksAreExternal() {
        assertEquals(LinkTarget.External("https://mupdf.com"), LinkClassifier.classify("https://mupdf.com"))
        assertEquals(LinkTarget.External("HTTP://Example.org/x"), LinkClassifier.classify("HTTP://Example.org/x"))
        assertEquals(LinkTarget.External("mailto:a@b.c"), LinkClassifier.classify("mailto:a@b.c"))
    }

    @Test
    fun otherSchemesAreIgnored() {
        for (u in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "tel:123", "content://x/y", "ftp://x")) {
            assertEquals(LinkTarget.Ignored, LinkClassifier.classify(u), u)
        }
    }

    @Test
    fun documentInternalUrisAreInternal() {
        assertEquals(LinkTarget.Internal("#page=3&zoom=100,0,0"), LinkClassifier.classify("#page=3&zoom=100,0,0"))
        assertEquals(LinkTarget.Internal("#chapter1"), LinkClassifier.classify("#chapter1"))
        assertEquals(LinkTarget.Internal("section.pdf#x"), LinkClassifier.classify("section.pdf#x"))
    }

    @Test
    fun emptyLinksAreIgnored() {
        assertEquals(LinkTarget.Ignored, LinkClassifier.classify(null))
        assertEquals(LinkTarget.Ignored, LinkClassifier.classify(""))
        assertEquals(LinkTarget.Ignored, LinkClassifier.classify("   "))
    }

    @Test
    fun schemeParsing() {
        assertEquals("https", LinkClassifier.schemeOf("https://x"))
        assertEquals("a+b-c.d", LinkClassifier.schemeOf("a+b-c.d:rest"))
        assertNull(LinkClassifier.schemeOf("1http://x"))
        assertNull(LinkClassifier.schemeOf("no scheme"))
        assertNull(LinkClassifier.schemeOf("#page=1"))
        assertNull(LinkClassifier.schemeOf("abc"))
    }

    @Test
    fun pageLabels() {
        assertNull(PageLabels.clean(null, 0))
        assertNull(PageLabels.clean("", 0))
        assertNull(PageLabels.clean("   ", 0))
        assertNull(PageLabels.clean("1", 0))
        assertNull(PageLabels.clean(" 12 ", 11))
        assertEquals("iv", PageLabels.clean("iv", 3))
        assertEquals("A-3", PageLabels.clean("A-3", 2))
        assertEquals("4", PageLabels.clean("4", 0)) // a real label that differs from the position
    }

    @Test
    fun storeTrimPolicy() {
        assertNull(StoreTrimPolicy.shrinkTo(5))
        assertNull(StoreTrimPolicy.shrinkTo(10))
        assertEquals(0, StoreTrimPolicy.shrinkTo(15)) // running critical
        assertEquals(50, StoreTrimPolicy.shrinkTo(20)) // UI hidden
        assertEquals(0, StoreTrimPolicy.shrinkTo(40)) // background
        assertEquals(0, StoreTrimPolicy.shrinkTo(80)) // complete
    }
}
