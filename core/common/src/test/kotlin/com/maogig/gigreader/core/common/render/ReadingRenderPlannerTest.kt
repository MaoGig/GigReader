package com.maogig.gigreader.core.common.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingRenderPlannerTest {
    private val vw = 1000f
    private val vh = 1600f
    private val n = 40
    private val planner = RenderPlanner(tileSize = 512, prefetchPages = 1)

    private fun layout(mode: ReadingMode, cover: Boolean = true) = ReadingLayout.build(
        mode, FloatArray(n) { 600f }, FloatArray(n) { 800f }, vw, vh, 20f, 0f, cover,
    )

    @Test
    fun continuousDelegatesToTheExistingPlanner() {
        val doc = DocumentLayout.build(FloatArray(n) { 595f }, FloatArray(n) { 842f }, vw, 0f, 20f)
        val l = ContinuousReadingLayout(doc, vh)
        val t = ViewportTransform(2.5f, 100f, doc.pageTop(7) + 200f)
        assertEquals(planner.plan(doc, t, vw, vh), planner.plan(l, t))
        assertEquals(planner.plan(doc, t, vw, vh, includeTiles = false), planner.plan(l, t, includeTiles = false))
    }

    @Test
    fun pagedRendersOnlyTheCurrentPageAndPrefetchesAdjacentOnes() {
        val l = layout(ReadingMode.PAGED)
        val plan = planner.plan(l, l.transformForUnit(10))
        assertEquals(listOf(10), plan.pages.map { it.page })
        assertEquals(listOf(11, 9), plan.prefetch.map { it.page }, "next first, then previous")
        assertTrue(plan.tiles.isEmpty())
        assertEquals(1000, plan.pages.single().widthPx)
    }

    @Test
    fun pagedEdgesHaveNoOutOfRangePrefetch() {
        val l = layout(ReadingMode.PAGED)
        assertEquals(listOf(1), planner.plan(l, l.transformForUnit(0)).prefetch.map { it.page })
        assertEquals(listOf(n - 2), planner.plan(l, l.transformForUnit(n - 1)).prefetch.map { it.page })
    }

    @Test
    fun midSwipeRendersBothPagesClosestFirst() {
        val l = layout(ReadingMode.PAGED)
        val t = ViewportTransform(1f, l.unitLeft(10) + 700f, 0f) // mostly page 11
        val plan = planner.plan(l, t)
        assertEquals(listOf(11, 10), plan.pages.map { it.page })
        assertEquals(listOf(12, 9), plan.prefetch.map { it.page })
    }

    @Test
    fun twoPagePrefetchesWholeAdjacentSpreads() {
        val l = layout(ReadingMode.TWO_PAGE)
        // Units: [0] [1,2] [3,4] [5,6] [7,8] ...
        val plan = planner.plan(l, l.transformForUnit(3))
        assertEquals(setOf(5, 6), plan.pages.map { it.page }.toSet())
        assertEquals(listOf(7, 8, 3, 4), plan.prefetch.map { it.page })
        val cover = planner.plan(l, l.transformForUnit(0))
        assertEquals(listOf(0), cover.pages.map { it.page })
        assertEquals(listOf(1, 2), cover.prefetch.map { it.page })
    }

    @Test
    fun largerPrefetchCountsUnits() {
        val l = layout(ReadingMode.TWO_PAGE)
        val plan = RenderPlanner(prefetchPages = 2).plan(l, l.transformForUnit(5))
        assertEquals(listOf(11, 12, 7, 8, 13, 14, 5, 6), plan.prefetch.map { it.page })
        assertEquals(8, plan.prefetch.size)
        assertEquals(0, RenderPlanner(prefetchPages = 0).plan(l, l.transformForUnit(5)).prefetch.size)
    }

    @Test
    fun zoomedPagedPlansOnlyVisibleTilesOfThePage() {
        val l = layout(ReadingMode.PAGED)
        val t = l.transformForUnit(10, 3f)
        val plan = planner.plan(l, t)
        assertEquals(listOf(10), plan.pages.map { it.page })
        assertTrue(plan.tiles.isNotEmpty())
        assertTrue(plan.tiles.all { it.page == 10 })
        val scale = ZoomBuckets.scale(ZoomBuckets.bucketFor(3f))
        val bound = ((vw * scale / 3f) / 512).toInt() + 2
        assertTrue(plan.tiles.size <= bound * (((vh * scale / 3f) / 512).toInt() + 2), "tiles=${plan.tiles.size}")
        val t0 = plan.tiles.first()
        assertEquals(1000 * scale, t0.pageWidthPx.toFloat(), 1f)
        // First tile is the one nearest the view centre.
        val cx = t.offsetX + vw / 3f / 2f
        val cy = t.offsetY + vh / 3f / 2f
        val d = plan.tiles.map { tile ->
            val x = l.pageLeft(10) + (tile.left + tile.right) / 2f / scale - cx
            val y = l.pageTop(10) + (tile.top + tile.bottom) / 2f / scale - cy
            x * x + y * y
        }
        assertEquals(d.min(), d.first(), 1e-2f)
        assertTrue(planner.plan(l, t, includeTiles = false).tiles.isEmpty())
    }

    @Test
    fun zoomedSpreadPlansTilesOnlyForThePagesInView() {
        val l = layout(ReadingMode.TWO_PAGE)
        val b = l.unitBounds(3)
        // Zoom on the left page of spread (5,6).
        val t = l.clamp(ViewportTransform(3f, b.left, b.top + 100f), 1f, 8f)
        val plan = planner.plan(l, t)
        assertEquals(listOf(5), plan.pages.map { it.page })
        assertTrue(plan.tiles.isNotEmpty())
        assertTrue(plan.tiles.all { it.page == 5 })
    }

    @Test
    fun pagesOfDifferentSizesUseTheirOwnBaseWidth() {
        val l = ReadingLayout.build(
            ReadingMode.TWO_PAGE, floatArrayOf(600f, 600f, 600f), floatArrayOf(800f, 800f, 400f), vw, vh, 20f,
        )
        val plan = planner.plan(l, l.transformForUnit(1))
        assertEquals(setOf(1, 2), plan.pages.map { it.page }.toSet())
        assertTrue(plan.pages.all { it.widthPx == 500 })
        assertEquals(667, plan.pages.first { it.page == 1 }.heightPx)
        assertEquals(333, plan.pages.first { it.page == 2 }.heightPx)
    }

    @Test
    fun hugeBasesAreCappedAndPlanEmptyForEmptyDocument() {
        val big = ReadingLayout.build(ReadingMode.PAGED, FloatArray(2) { 600f }, FloatArray(2) { 800f }, 4000f, 6000f, 0f)
        assertEquals(1440, planner.baseKey(big, 0).widthPx)
        assertEquals(RenderPlan.Empty, planner.plan(layout(ReadingMode.PAGED).let {
            ReadingLayout.build(ReadingMode.PAGED, FloatArray(0), FloatArray(0), vw, vh, 0f)
        }, ViewportTransform()))
    }

    @Test
    fun fiveThousandPagesPlanQuickly() {
        val big = 5000
        val l = ReadingLayout.build(ReadingMode.TWO_PAGE, FloatArray(big) { 595f }, FloatArray(big) { 842f }, vw, vh, 20f)
        val plan = planner.plan(l, l.transformForUnit(l.unitCount / 2))
        assertEquals(2, plan.pages.size)
        assertEquals(4, plan.prefetch.size)
    }
}
