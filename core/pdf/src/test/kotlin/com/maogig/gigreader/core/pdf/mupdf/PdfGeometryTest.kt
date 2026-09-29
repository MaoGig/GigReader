package com.maogig.gigreader.core.pdf.mupdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PdfGeometryTest {
    private val a4 = Box(0f, 0f, 595f, 842f)

    @Test
    fun plainMediaBox() {
        assertEquals(595f to 842f, PdfGeometry.pageSize(a4, null, 0, null))
    }

    @Test
    fun rotationSwapsWidthAndHeight() {
        assertEquals(842f to 595f, PdfGeometry.pageSize(a4, null, 90, null))
        assertEquals(842f to 595f, PdfGeometry.pageSize(a4, null, -90, null))
        assertEquals(595f to 842f, PdfGeometry.pageSize(a4, null, 180, null))
        assertEquals(842f to 595f, PdfGeometry.pageSize(a4, null, 450, null))
    }

    @Test
    fun rotationThatIsNotAMultipleOf90IsIgnored() {
        assertEquals(595f to 842f, PdfGeometry.pageSize(a4, null, 45, null))
        assertEquals(0, PdfGeometry.quarterTurns(45))
        assertEquals(3, PdfGeometry.quarterTurns(-90))
    }

    @Test
    fun cropBoxIsClippedToMediaBox() {
        val crop = Box(10f, 10f, 700f, 500f)
        assertEquals(585f to 490f, PdfGeometry.pageSize(a4, crop, 0, null))
    }

    @Test
    fun emptyOrDisjointCropBoxFallsBackToMediaBox() {
        assertEquals(595f to 842f, PdfGeometry.pageSize(a4, Box(1000f, 1000f, 1100f, 1100f), 0, null))
        assertEquals(595f to 842f, PdfGeometry.pageSize(a4, Box(5f, 5f, 5f, 5f), 0, null))
    }

    @Test
    fun invertedCornersAreAccepted() {
        assertEquals(595f to 842f, PdfGeometry.pageSize(Box(595f, 842f, 0f, 0f), null, 0, null))
    }

    @Test
    fun missingOrEmptyMediaBoxUsesLetter() {
        assertEquals(612f to 792f, PdfGeometry.pageSize(null, null, 0, null))
        assertEquals(612f to 792f, PdfGeometry.pageSize(Box(0f, 0f, 0f, 0f), null, 0, null))
    }

    @Test
    fun userUnitScales() {
        assertEquals(1190f to 1684f, PdfGeometry.pageSize(a4, null, 0, 2f))
        assertEquals(595f to 842f, PdfGeometry.pageSize(a4, null, 0, 0f))
        assertEquals(595f to 842f, PdfGeometry.pageSize(a4, null, 0, Float.NaN))
    }

    @Test
    fun tileMatrixScalesPageToPixels() {
        val m = PdfGeometry.tileMatrix(Box(0f, 0f, 200f, 100f), 400, 300, 0, 0)
        assertEquals(listOf(2f, 0f, 0f, 3f, 0f, 0f), m.toList())
    }

    @Test
    fun tileMatrixTranslatesByTheTileOffset() {
        val m = PdfGeometry.tileMatrix(Box(0f, 0f, 200f, 100f), 400, 300, 256, 128)
        assertEquals(listOf(2f, 0f, 0f, 3f, -256f, -128f), m.toList())
        // Page point (200, 100) is the bottom-right of the scaled page: (400,300) - offset.
        assertEquals(400f - 256f, m[0] * 200f + m[4])
        assertEquals(300f - 128f, m[3] * 100f + m[5])
    }

    @Test
    fun tileMatrixHandlesBoundsNotAtOrigin() {
        val m = PdfGeometry.tileMatrix(Box(10f, 20f, 110f, 120f), 200, 200, 0, 0)
        assertEquals(0f, m[0] * 10f + m[4])
        assertEquals(0f, m[3] * 20f + m[5])
        assertEquals(200f, m[0] * 110f + m[4])
        assertEquals(200f, m[3] * 120f + m[5])
    }

    @Test
    fun tileMatrixSurvivesDegenerateBounds() {
        val m = PdfGeometry.tileMatrix(Box(0f, 0f, 0f, 0f), 100, 100, 0, 0)
        assertEquals(1f, m[0])
        assertEquals(1f, m[3])
    }

    @Test
    fun yFraction() {
        assertEquals(0.25f, PdfGeometry.yFraction(200f, 800f))
        assertEquals(1f, PdfGeometry.yFraction(900f, 800f))
        assertEquals(0f, PdfGeometry.yFraction(-5f, 800f))
        assertNull(PdfGeometry.yFraction(Float.NaN, 800f))
        assertNull(PdfGeometry.yFraction(10f, 0f))
        assertNull(PdfGeometry.yFraction(Float.POSITIVE_INFINITY, 800f))
    }

    @Test
    fun normalizeUsesPageBoundsAndClamps() {
        val page = Box(0f, 0f, 200f, 100f)
        assertEquals(NormRect(0.25f, 0.5f, 0.5f, 1f), PdfGeometry.normalize(Box(50f, 50f, 100f, 100f), page))
        assertEquals(NormRect(0f, 0f, 1f, 1f), PdfGeometry.normalize(Box(-10f, -10f, 300f, 300f), page))
        assertEquals(NormRect(0.25f, 0.5f, 0.5f, 1f), PdfGeometry.normalize(Box(100f, 100f, 50f, 50f), page))
    }
}
