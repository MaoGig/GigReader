package com.maogig.gigreader.core.common.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingLayoutTest {
    private val vw = 1000f
    private val vh = 1600f
    private val zoomLimits = ViewportMath(1f, 8f)

    private fun uniform(n: Int, w: Float = 600f, h: Float = 800f) = FloatArray(n) { w } to FloatArray(n) { h }

    private fun build(
        mode: ReadingMode,
        n: Int,
        cover: Boolean = true,
        fit: PageFit = PageFit.PAGE,
        gap: Float = 20f,
        padding: Float = 0f,
        widths: FloatArray = uniform(n).first,
        heights: FloatArray = uniform(n).second,
    ) = ReadingLayout.build(mode, widths, heights, vw, vh, gap, padding, cover, fit)

    private fun assertNear(expected: Float, actual: Float, eps: Float = 0.05f) =
        assertTrue(kotlin.math.abs(expected - actual) <= eps, "expected $expected but was $actual")

    // --- building / page geometry ---

    @Test
    fun pagedFitsWholePageCenteredInItsCell() {
        // 600x800 page in 1000x1600: width-limited (scale 1.667) -> 1000x1333, centred vertically.
        val l = build(ReadingMode.PAGED, 3)
        assertEquals(3, l.unitCount)
        assertNear(1000f, l.pageWidth(0))
        assertNear(1333.33f, l.pageHeight(0))
        assertNear((vh - l.pageHeight(0)) / 2f, l.pageTop(0))
        assertNear(0f, l.pageLeft(0))
        assertNear(vw + 20f, l.pageLeft(1))
        assertNear(1.3333f, l.pageAspect(2), 1e-3f)
        assertNear(3 * vw + 2 * 20f, l.totalWidth)
    }

    @Test
    fun landscapePageIsHeightLimitedAndPillarboxed() {
        // 800x400 page in 1000x1600 -> scale 1.25, 1000x500: still width-limited; use a tall viewport instead.
        val l = ReadingLayout.build(ReadingMode.PAGED, floatArrayOf(400f), floatArrayOf(800f), 1600f, 1000f, 0f)
        assertNear(500f, l.pageWidth(0))
        assertNear(1000f, l.pageHeight(0))
        assertNear(550f, l.pageLeft(0)) // centred: (1600-500)/2
    }

    @Test
    fun fitWidthLetsTallPagesOverflowAndScrollVertically() {
        val l = build(ReadingMode.PAGED, 2, fit = PageFit.WIDTH, widths = floatArrayOf(400f, 400f), heights = floatArrayOf(1000f, 1000f))
        assertNear(1000f, l.pageWidth(0))
        assertNear(2500f, l.pageHeight(0))
        assertNear(0f, l.pageTop(0))
        val t0 = l.transformForUnit(0)
        assertNear(0f, t0.offsetY)
        // Scrolling down is possible up to the bottom of the page, no further.
        val down = l.clamp(ViewportTransform(1f, t0.offsetX, 99999f), 1f, 8f)
        assertNear(2500f - vh, down.offsetY)
        val up = l.clamp(ViewportTransform(1f, t0.offsetX, -500f), 1f, 8f)
        assertNear(0f, up.offsetY)
    }

    @Test
    fun paddingShrinksTheFittedPage() {
        val l = build(ReadingMode.PAGED, 1, padding = 50f)
        assertNear(900f, l.pageWidth(0))
        assertNear(50f, l.pageLeft(0))
    }

    @Test
    fun emptyDocumentIsSafeInEveryMode() {
        for (mode in ReadingMode.values()) {
            val l = build(mode, 0)
            assertEquals(0, l.pageCount)
            assertEquals(0, l.unitCount)
            assertEquals(-1, l.pageAtPoint(1f, 1f))
            assertTrue(l.pagesIn(DocRect(0f, 0f, 10f, 10f)).isEmpty())
            assertEquals(PagePosition(0, 0f), l.positionOf(ViewportTransform()))
            assertEquals(0, l.settleUnit(ViewportTransform()))
            l.clamp(ViewportTransform(2f, 5f, 5f), 1f, 8f)
            l.transformForUnit(0)
        }
    }

    @Test
    fun invalidPageSizesFallBackToA4() {
        val l = ReadingLayout.build(ReadingMode.PAGED, floatArrayOf(0f), floatArrayOf(-3f), vw, vh, 0f)
        assertNear(842f / 595f, l.pageAspect(0), 1e-3f)
    }

    // --- two-page spreads ---

    @Test
    fun coverIsAloneAndSpreadsPairAfterIt() {
        val l = build(ReadingMode.TWO_PAGE, 6)
        // Units: [0] [1,2] [3,4] [5]
        assertEquals(4, l.unitCount)
        assertEquals(listOf(0, 1, 1, 2, 2, 3), (0 until 6).map { l.unitOfPage(it) })
        assertEquals(listOf(0, 1, 3, 5), (0 until 4).map { l.firstPageOfUnit(it) })
        assertEquals(listOf(0, 2, 4, 5), (0 until 4).map { l.lastPageOfUnit(it) })
    }

    @Test
    fun withoutCoverPagesPairFromTheStart() {
        val l = build(ReadingMode.TWO_PAGE, 5, cover = false)
        // Units: [0,1] [2,3] [4]
        assertEquals(3, l.unitCount)
        assertEquals(listOf(0, 0, 1, 1, 2), (0 until 5).map { l.unitOfPage(it) })
        assertEquals(4, l.firstPageOfUnit(2))
        assertEquals(4, l.lastPageOfUnit(2))
    }

    @Test
    fun unitCountsForSmallOddAndEvenDocuments() {
        assertEquals(listOf(0, 1, 2, 2, 3, 3, 4), (0..6).map { build(ReadingMode.TWO_PAGE, it).unitCount })
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3), (0..6).map { build(ReadingMode.TWO_PAGE, it, cover = false).unitCount })
    }

    @Test
    fun spreadIsFittedAsOneAndPagesTouch() {
        val l = build(ReadingMode.TWO_PAGE, 3)
        // Spread (1,2): 1200x800 pts -> scale min(1000/1200, 1600/800) = 0.8333: two 500x666.67 pages.
        assertNear(500f, l.pageWidth(1))
        assertNear(l.pageLeft(1) + l.pageWidth(1), l.pageLeft(2))
        assertNear(1000f + 20f, l.pageLeft(1))
        assertNear(1000f + 20f + 1000f, l.pageLeft(2) + l.pageWidth(2))
        // Lone cover: same scale rule on a single page (fit width), centred in cell 0.
        assertNear(1000f, l.pageWidth(0))
    }

    @Test
    fun mixedHeightsInASpreadAreVerticallyCentered() {
        // Page 1: 600x800, page 2: 600x400 (landscape-ish half height).
        val l = ReadingLayout.build(
            ReadingMode.TWO_PAGE,
            floatArrayOf(600f, 600f, 600f), floatArrayOf(800f, 800f, 400f), vw, vh, 20f, 0f, true, PageFit.PAGE,
        )
        val hTall = l.pageHeight(1)
        val hShort = l.pageHeight(2)
        assertNear(hTall / 2f, hShort)
        assertNear(l.pageTop(1) + hTall / 2f, l.pageTop(2) + hShort / 2f)
        assertNear(vh / 2f, l.pageTop(1) + hTall / 2f)
        val b = l.unitBounds(1)
        assertNear(l.pageTop(1), b.top)
        assertNear(l.pageTop(1) + hTall, b.bottom)
        assertNear(l.pageLeft(1), b.left)
        assertNear(l.pageLeft(2) + l.pageWidth(2), b.right)
    }

    @Test
    fun mixedOrientationSpreadKeepsPhysicalSizes() {
        // Portrait 600x800 next to landscape 800x600: same scale for both.
        val l = ReadingLayout.build(
            ReadingMode.TWO_PAGE,
            floatArrayOf(600f, 600f, 800f), floatArrayOf(800f, 800f, 600f), vw, vh, 0f,
        )
        assertNear(l.pageWidth(1) / 600f, l.pageWidth(2) / 800f, 1e-3f)
        assertNear(l.pageHeight(1) / 800f, l.pageHeight(2) / 600f, 1e-3f)
        // Fitted to the width (1400 pts wide).
        assertNear(1000f, l.pageWidth(1) + l.pageWidth(2))
    }

    // --- hit testing ---

    @Test
    fun pageAtPointFindsPagesAndRejectsMarginsAndGaps() {
        val l = build(ReadingMode.TWO_PAGE, 4)
        val cx1 = l.pageLeft(1) + 10f
        val cy = vh / 2f
        assertEquals(1, l.pageAtPoint(cx1, cy))
        assertEquals(2, l.pageAtPoint(l.pageLeft(2) + 10f, cy))
        assertEquals(2, l.pageAtPoint(l.pageLeft(2), cy), "shared edge belongs to the right page")
        assertEquals(-1, l.pageAtPoint(l.pageLeft(1) + 10f, 1f), "above the page")
        assertEquals(-1, l.pageAtPoint(1005f, cy), "gap between cells")
        assertEquals(-1, l.pageAtPoint(-5f, cy))
        assertEquals(-1, l.pageAtPoint(1e6f, cy))
    }

    @Test
    fun pageAtPointOnContinuousChecksBothAxes() {
        val l = build(ReadingMode.CONTINUOUS, 5, padding = 100f)
        val y = l.pageTop(2) + 10f
        assertEquals(2, l.pageAtPoint(500f, y))
        assertEquals(-1, l.pageAtPoint(50f, y), "left margin")
        assertEquals(-1, l.pageAtPoint(500f, l.pageTop(2) - 5f), "gap")
    }

    @Test
    fun pagesInUsesHorizontalExtentInPagedModes() {
        val l = build(ReadingMode.PAGED, 10)
        assertEquals(3..3, l.pagesIn(DocRect(l.unitLeft(3), 0f, l.unitLeft(3) + vw, vh)))
        // Mid-swipe: half of 3 and half of 4.
        val a = l.unitLeft(3) + 500f
        assertEquals(3..4, l.pagesIn(DocRect(a, 0f, a + vw, vh)))
        assertTrue(l.pagesIn(DocRect(5f, 5f, 5f, 9f)).isEmpty())
        assertEquals(0..9, l.pagesIn(DocRect(-1e5f, 0f, 1e6f, vh)))
        // A rect wholly in the gap between two cells touches no page.
        assertTrue(l.pagesIn(DocRect(1002f, 0f, 1018f, vh)).isEmpty())
    }

    @Test
    fun pagesInForSpreadsReturnsBothPagesOfTheSpread() {
        val l = build(ReadingMode.TWO_PAGE, 7)
        val u = 2
        assertEquals(3..4, l.pagesIn(DocRect(l.unitLeft(u), 0f, l.unitLeft(u) + vw, vh)))
    }

    @Test
    fun continuousAdapterMatchesDocumentLayout() {
        val doc = DocumentLayout.build(FloatArray(20) { 595f }, FloatArray(20) { 842f }, vw, 0f, 20f)
        val l = ContinuousReadingLayout(doc, vh)
        assertEquals(doc.pagesIn(1000f, 5000f), l.pagesIn(DocRect(0f, 1000f, vw, 5000f)))
        assertEquals(doc.pageAt(4000f), l.unitAt(0f, 4000f))
        assertEquals(20, l.unitCount)
        assertEquals(-1, l.nextUnit(19))
        assertEquals(-1, l.previousUnit(0))
        val t = ViewportTransform(2f, 100f, 3000f)
        assertEquals(zoomLimits.clamp(t, doc, vw, vh), l.clamp(t, 1f, 8f))
        assertEquals(zoomLimits.topPosition(t, doc), l.positionOf(t))
        assertEquals(zoomLimits.transformFor(PagePosition(5, 0f), 1f, doc, vw, vh), l.transformForUnit(5))
        assertEquals(l.currentUnit(t), l.settleUnit(t, -5000f))
    }

    // --- units, snapping, navigation ---

    @Test
    fun unitAtUsesTheNearestCell() {
        val l = build(ReadingMode.PAGED, 5)
        assertEquals(0, l.unitAt(-300f, 0f))
        assertEquals(0, l.unitAt(1009f, 0f))
        assertEquals(1, l.unitAt(1011f, 0f))
        assertEquals(4, l.unitAt(1e7f, 0f))
    }

    @Test
    fun nextAndPreviousUnit() {
        val l = build(ReadingMode.TWO_PAGE, 5)
        assertEquals(1, l.nextUnit(0))
        assertEquals(-1, l.nextUnit(l.unitCount - 1))
        assertEquals(-1, l.previousUnit(0))
        assertEquals(1, l.previousUnit(2))
    }

    @Test
    fun snapTargetPutsTheUnitExactlyOnScreen() {
        val l = build(ReadingMode.PAGED, 6)
        for (u in 0 until 6) {
            val t = l.transformForUnit(u)
            assertEquals(1f, t.zoom)
            assertNear(l.unitLeft(u), t.offsetX)
            assertNear(0f, t.offsetY)
            assertEquals(u, l.currentUnit(t))
        }
    }

    @Test
    fun snapTargetOfASpreadCentersTheSpreadOnScreen() {
        val l = build(ReadingMode.TWO_PAGE, 5)
        val t = l.transformForUnit(1)
        val b = l.unitBounds(1)
        // Spread centre lands on the viewport centre.
        assertNear(vw / 2f, t.toScreenX((b.left + b.right) / 2f))
        assertNear(vh / 2f, t.toScreenY((b.top + b.bottom) / 2f))
    }

    @Test
    fun settleUnitSnapsToNearestOrFlingsToNeighbour() {
        val l = build(ReadingMode.PAGED, 6)
        val stride = vw + 20f
        fun at(progress: Float) = ViewportTransform(1f, progress * stride, 0f)
        assertEquals(2, l.settleUnit(at(2.3f)))
        assertEquals(3, l.settleUnit(at(2.6f)))
        assertEquals(1, l.settleUnit(at(1.4f)))
        // Small drag towards next (offset increased 2 -> 2.1) with a fling towards next: turn.
        assertEquals(3, l.settleUnit(at(2.1f), velocityX = -2000f))
        // Dragged 2 -> 1.9 (towards previous) but flung back towards next: back to 2, not 3.
        assertEquals(2, l.settleUnit(at(1.9f), velocityX = -2000f))
        assertEquals(1, l.settleUnit(at(2.0f), velocityX = 2000f))
        assertEquals(2, l.settleUnit(at(2.1f), velocityX = 2000f))
        // Slow release keeps the page.
        assertEquals(2, l.settleUnit(at(2.1f), velocityX = -100f))
        // Clamped at the ends.
        assertEquals(0, l.settleUnit(at(0f), velocityX = 5000f))
        assertEquals(5, l.settleUnit(at(5f), velocityX = -5000f))
    }

    @Test
    fun zoomedViewNeverTurnsThePageOnSettle() {
        val l = build(ReadingMode.PAGED, 6)
        val t = l.clamp(ViewportTransform(3f, l.unitLeft(2) + 400f, 500f), 1f, 8f)
        assertEquals(2, l.settleUnit(t, velocityX = -5000f))
    }

    // --- clamping ---

    @Test
    fun atZoomOneSwipingIsFreeAcrossCellsButVerticalIsLocked() {
        val l = build(ReadingMode.PAGED, 4)
        val mid = l.clamp(ViewportTransform(1f, 1500f, 300f), 1f, 8f)
        assertNear(1500f, mid.offsetX)
        assertNear(0f, mid.offsetY)
        assertNear(0f, l.clamp(ViewportTransform(1f, -400f, 0f), 1f, 8f).offsetX)
        assertNear(l.totalWidth - vw, l.clamp(ViewportTransform(1f, 1e6f, 0f), 1f, 8f).offsetX)
    }

    @Test
    fun zoomedPanIsBoundedToTheCurrentPage() {
        val l = build(ReadingMode.PAGED, 4)
        val u = 2
        val visW = vw / 2f
        val left = l.pageLeft(u)
        val right = left + l.pageWidth(u)
        val farRight = l.clamp(ViewportTransform(2f, 1e6f, 0f), 1f, 8f)
        // Centre must remain inside the page it was on... clamp picks the unit at the visible centre (last one) here.
        assertNear(l.pageLeft(3) + l.pageWidth(3) - visW, farRight.offsetX)
        val t = l.clamp(ViewportTransform(2f, left + 100f, 100f), 1f, 8f)
        val pushed = l.clamp(ViewportTransform(2f, right + 800f - visW, 100f), 1f, 8f)
        assertTrue(t.offsetX >= left - 1e-3f)
        // Panning right with a huge delta stays inside the page under the centre: never past its right edge.
        val u2 = l.unitAt(pushed.offsetX + visW / 2f, 0f)
        assertTrue(pushed.offsetX + visW <= l.pageLeft(u2) + l.pageWidth(u2) + 1e-2f)
        assertTrue(pushed.offsetX >= l.pageLeft(u2) - 1e-2f)
    }

    @Test
    fun zoomedPanCannotLeaveTheUnitViaPanBy() {
        val l = build(ReadingMode.PAGED, 4)
        var t = zoomLimits.zoomAround(l.transformForUnit(1), 2f, vw / 2f, vh / 2f, l)
        assertNear(2f, t.zoom)
        repeat(50) { t = zoomLimits.panBy(t, -300f, 0f, l) }
        val visW = vw / t.zoom
        assertNear(l.pageLeft(1) + l.pageWidth(1) - visW, t.offsetX)
        assertEquals(1, l.currentUnit(t))
        repeat(50) { t = zoomLimits.panBy(t, 300f, 0f, l) }
        assertNear(l.pageLeft(1), t.offsetX)
        assertEquals(1, l.currentUnit(t))
        repeat(50) { t = zoomLimits.panBy(t, 0f, 300f, l) }
        assertNear(l.pageTop(1), t.offsetY)
        repeat(50) { t = zoomLimits.panBy(t, 0f, -300f, l) }
        assertNear(l.pageTop(1) + l.pageHeight(1) - vh / 2f, t.offsetY)
    }

    @Test
    fun pageNarrowerThanViewportIsCenteredWhenSlightlyZoomed() {
        // 400x800 page in 1000x1600: fit-height = 0.. scale min(2.5, 2.0) = 2 -> 800x1600, pillarboxed.
        val l = ReadingLayout.build(ReadingMode.PAGED, FloatArray(3) { 400f }, FloatArray(3) { 800f }, vw, vh, 20f)
        assertNear(800f, l.pageWidth(1))
        val t = l.clamp(ViewportTransform(1.1f, l.unitLeft(1) + 3f, 40f), 1f, 8f)
        val centre = t.offsetX + vw / 1.1f / 2f
        assertNear(l.pageLeft(1) + 400f, centre)
        // Zoom in enough that the page overflows: bounded to the page, not the cell.
        val z = l.clamp(ViewportTransform(2f, 0f, 0f).copy(offsetX = l.unitLeft(1)), 1f, 8f)
        assertTrue(z.offsetX >= l.pageLeft(1) - 1e-2f)
    }

    @Test
    fun zoomLimitsApply() {
        val l = build(ReadingMode.PAGED, 3)
        assertEquals(8f, l.clamp(ViewportTransform(50f, 0f, 0f), 1f, 8f).zoom)
        assertEquals(1f, l.clamp(ViewportTransform(0.2f, 0f, 0f), 1f, 8f).zoom)
    }

    @Test
    fun clampReturnsSameInstanceWhenAlreadyValid() {
        val l = build(ReadingMode.PAGED, 3)
        val t = l.transformForUnit(1)
        assertTrue(t === l.clamp(t, 1f, 8f))
    }

    @Test
    fun zoomAroundFocusKeepsThePointUnderTheFingerInsideAPage() {
        val l = build(ReadingMode.PAGED, 3)
        val start = l.transformForUnit(1)
        val fx = 300f
        val fy = 700f
        val docX = start.toDocX(fx)
        val docY = start.toDocY(fy)
        val z = zoomLimits.zoomAround(start, 3f, fx, fy, l)
        assertNear(docX, z.toDocX(fx), 0.5f)
        assertNear(docY, z.toDocY(fy), 0.5f)
    }

    @Test
    fun horizontalEdgeDetection() {
        val l = build(ReadingMode.PAGED, 3)
        val fitted = l.transformForUnit(1)
        assertTrue(l.isAtHorizontalEdge(fitted, -1))
        assertTrue(l.isAtHorizontalEdge(fitted, 1))
        val zoomed = l.transformForUnit(1, 2f)
        assertFalse(l.isAtHorizontalEdge(zoomed, -1))
        val atLeft = zoomed.copy(offsetX = l.pageLeft(1))
        assertTrue(l.isAtHorizontalEdge(atLeft, -1))
        assertFalse(l.isAtHorizontalEdge(atLeft, 1))
        val atRight = zoomed.copy(offsetX = l.pageLeft(1) + l.pageWidth(1) - vw / 2f)
        assertTrue(l.isAtHorizontalEdge(atRight, 1))
    }

    // --- position mapping between modes ---

    @Test
    fun positionSurvivesEveryModeSwitchAtAPageBoundary() {
        val n = 30
        val cont = build(ReadingMode.CONTINUOUS, n)
        val paged = build(ReadingMode.PAGED, n)
        val two = build(ReadingMode.TWO_PAGE, n)
        val t = cont.transformForUnit(11)
        assertEquals(11, cont.positionOf(t).page)

        val tp = ReadingPositions.switchTransform(t, cont, paged)
        assertEquals(11, paged.currentPage(tp))

        val t2 = ReadingPositions.switchTransform(tp, paged, two)
        assertEquals(two.unitOfPage(11), two.currentUnit(t2))
        // Page 11 is the second of spread (11,12)? With cover: units [0][1,2]...[11,12] -> first page is 11.
        assertEquals(11, two.currentPage(t2))

        val t3 = ReadingPositions.switchTransform(t2, two, paged)
        assertEquals(11, paged.currentPage(t3))

        val t4 = ReadingPositions.switchTransform(t3, paged, cont)
        assertEquals(11, cont.positionOf(t4).page)
    }

    @Test
    fun secondPageOfASpreadMapsToTheSpreadAndBackToItsFirstPage() {
        val paged = build(ReadingMode.PAGED, 20)
        val two = build(ReadingMode.TWO_PAGE, 20)
        val t = paged.transformForUnit(6) // page 6 is the second page of spread (5,6)
        val t2 = ReadingPositions.switchTransform(t, paged, two)
        assertEquals(5, two.currentPage(t2))
        assertEquals(3, two.currentUnit(t2))
        assertEquals(two.unitOfPage(6), two.currentUnit(t2))
    }

    @Test
    fun continuousToPagedRoundsToTheDominantPage() {
        val cont = build(ReadingMode.CONTINUOUS, 10)
        val paged = build(ReadingMode.PAGED, 10)
        assertEquals(PagePosition(4, 0f), ReadingPositions.convert(PagePosition(4, 0.3f), cont, paged))
        assertEquals(PagePosition(5, 0f), ReadingPositions.convert(PagePosition(4, 0.5f), cont, paged))
        assertEquals(PagePosition(9, 0f), ReadingPositions.convert(PagePosition(9, 0.9f), cont, paged), "no page past the end")
        assertEquals(PagePosition(4, 0.3f), ReadingPositions.convert(PagePosition(4, 0.3f), cont, cont))
        assertEquals(PagePosition(4, 0.3f), ReadingPositions.convert(PagePosition(4, 0.3f), paged, cont))
        assertEquals(PagePosition(9, 0f), ReadingPositions.convert(PagePosition(50, 0f), paged, paged), "page is clamped")
    }

    @Test
    fun switchingToADocumentOfZeroPagesIsSafe() {
        val a = build(ReadingMode.PAGED, 3)
        val b = build(ReadingMode.CONTINUOUS, 0)
        assertEquals(PagePosition(0, 0f), ReadingPositions.convert(PagePosition(2, 0f), a, b))
    }

    @Test
    fun pagedPositionCarriesVerticalScrollWhenZoomedIn() {
        val l = build(ReadingMode.PAGED, 4)
        val t = l.transformFor(PagePosition(2, 0.4f), 3f)
        val p = l.positionOf(t)
        assertEquals(2, p.page)
        assertTrue(p.pageOffset in 0.3f..0.5f, "offset ${p.pageOffset}")
        assertEquals(2, l.currentUnit(t))
    }

    // --- large documents ---

    @Test
    fun fiveThousandPagesBuildAndQueryEverywhere() {
        val n = 5000
        val w = FloatArray(n) { if (it % 7 == 0) 842f else 595f }
        val h = FloatArray(n) { if (it % 7 == 0) 595f else 842f }
        for (mode in ReadingMode.values()) {
            val l = ReadingLayout.build(mode, w, h, vw, vh, 16f, 0f, true)
            assertEquals(n, l.pageCount)
            val last = l.unitCount - 1
            val t = l.transformForUnit(last)
            assertEquals(last, l.currentUnit(t))
            assertEquals(n - 1, l.lastPageOfUnit(last))
            val mid = l.transformForUnit(l.unitCount / 2)
            val visible = DocRect(mid.offsetX, mid.offsetY, mid.offsetX + vw, mid.offsetY + vh)
            val pages = l.pagesIn(visible)
            assertFalse(pages.isEmpty())
            assertTrue(pages.count() <= 3, "pages ${pages}")
            val p = pages.first
            assertEquals(p, l.pageAtPoint(l.pageLeft(p) + 1f, l.pageTop(p) + 1f))
        }
    }

    @Test
    fun everyPageBelongsToExactlyOneUnitInOrder() {
        for (cover in listOf(true, false)) {
            for (n in 0..9) {
                val l = build(ReadingMode.TWO_PAGE, n, cover = cover)
                var expectedFirst = 0
                for (u in 0 until l.unitCount) {
                    assertEquals(expectedFirst, l.firstPageOfUnit(u), "n=$n cover=$cover u=$u")
                    for (p in l.firstPageOfUnit(u)..l.lastPageOfUnit(u)) assertEquals(u, l.unitOfPage(p))
                    expectedFirst = l.lastPageOfUnit(u) + 1
                }
                assertEquals(n, expectedFirst)
            }
        }
    }
}
