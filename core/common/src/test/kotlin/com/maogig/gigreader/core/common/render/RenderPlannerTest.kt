package com.maogig.gigreader.core.common.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderPlannerTest {
    private val vw = 1000f
    private val vh = 2000f
    private val n = 50
    private val layout = DocumentLayout.build(FloatArray(n) { 595f }, FloatArray(n) { 842f }, vw, 0f, 20f)
    private val planner = RenderPlanner(tileSize = 512, prefetchPages = 1)

    @Test
    fun zoomBucketsAreMonotonicAndCoverTheZoom() {
        var previous = 0
        for (z in listOf(1.01f, 1.2f, 1.26f, 1.3f, 1.6f, 2f, 3f, 4f, 7.9f, 8f)) {
            val b = ZoomBuckets.bucketFor(z)
            assertTrue(b >= previous, "bucket must not decrease")
            assertTrue(ZoomBuckets.scale(b) >= z - 1e-3f, "bucket scale ${ZoomBuckets.scale(b)} must cover zoom $z")
            assertTrue(ZoomBuckets.scale(b) <= z * 1.26f + 1e-3f, "bucket scale must not oversample too much")
            previous = b
        }
        assertEquals(3, ZoomBuckets.bucketFor(2f))
        assertEquals(0, ZoomBuckets.bucketFor(1f))
        assertEquals(1f, ZoomBuckets.scale(0))
    }

    @Test
    fun atFitWidthOnlyVisiblePagesAndNeighboursAreRendered() {
        val t = ViewportTransform(1f, 0f, layout.pageTop(10))
        val plan = planner.plan(layout, t, vw, vh)
        // Page height = 1000 * 842/595 ≈ 1415 px, so a 2000 px viewport shows pages 10 and 11.
        assertEquals(listOf(10, 11), plan.pages.map { it.page }, "closest to the center first")
        assertEquals(listOf(12, 9), plan.prefetch.map { it.page }, "prefetch neighbours")
        assertTrue(plan.tiles.isEmpty(), "no tiles at zoom 1")
        assertEquals(1000, plan.pages.first().widthPx)
        assertEquals(listOf(10, 11, 12, 9), plan.keys.map { it.page })
    }

    @Test
    fun firstPageHasNoPreviousPrefetch() {
        val plan = planner.plan(layout, ViewportTransform(1f, 0f, 0f), vw, vh)
        assertEquals(listOf(0, 1), plan.pages.map { it.page })
        assertEquals(listOf(2), plan.prefetch.map { it.page })
    }

    @Test
    fun zoomedInPlansOnlyVisibleTiles() {
        val zoom = 3f
        val t = ViewportTransform(zoom, 200f, layout.pageTop(5) + 300f)
        val plan = planner.plan(layout, t, vw, vh)
        assertTrue(plan.tiles.isNotEmpty())
        assertTrue(plan.keys.indexOf(plan.tiles.first()) < plan.keys.indexOf(plan.prefetch.first()), "tiles before prefetch")
        val bucketScale = ZoomBuckets.scale(ZoomBuckets.bucketFor(zoom))
        // Visible area in rendered pixels is about (vw, vh) * bucketScale / zoom; allow one tile of slack per axis.
        val maxCols = ((vw * bucketScale / zoom) / 512).toInt() + 2
        val maxRows = ((vh * bucketScale / zoom) / 512).toInt() + 2
        assertTrue(plan.tiles.size <= maxCols * maxRows * 2, "too many tiles: ${plan.tiles.size}")
        plan.tiles.forEach { tile ->
            assertTrue(tile.left < tile.pageWidthPx && tile.top < tile.pageHeightPx, "tile outside page: $tile")
            assertTrue(tile.right > tile.left && tile.bottom > tile.top)
        }
        assertEquals(plan.tiles.size, plan.tiles.toSet().size, "tiles must be unique")
    }

    @Test
    fun wideViewportsCapTheBaseAndUseTilesAtFitWidth() {
        val tablet = DocumentLayout.build(FloatArray(n) { 595f }, FloatArray(n) { 842f }, 2560f, 0f, 20f)
        val plan = planner.plan(tablet, ViewportTransform(1f, 0f, tablet.pageTop(3)), 2560f, 1600f)
        assertEquals(1440, plan.pages.first().widthPx)
        assertTrue(plan.tiles.isNotEmpty(), "tiles keep text crisp above the base resolution")
        assertEquals(0, plan.tiles.first().bucket)
        assertEquals(2560, plan.tiles.first().pageWidthPx)
        val pinching = planner.plan(tablet, ViewportTransform(1f, 0f, tablet.pageTop(3)), 2560f, 1600f, includeTiles = false)
        assertTrue(pinching.tiles.isEmpty())
    }

    @Test
    fun emptyDocumentProducesEmptyPlan() {
        val empty = DocumentLayout.build(FloatArray(0), FloatArray(0), vw, 0f, 0f)
        assertEquals(RenderPlan.Empty, planner.plan(empty, ViewportTransform(), vw, vh))
    }
}
