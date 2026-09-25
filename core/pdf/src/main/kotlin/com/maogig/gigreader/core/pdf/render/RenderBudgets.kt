package com.maogig.gigreader.core.pdf.render

import android.app.ActivityManager
import android.content.Context
import kotlin.math.max
import kotlin.math.min

/**
 * Derives per-document memory budgets from the device class and the viewport size.
 *
 * The budget must hold at least what one screen needs (visible base pages + visible tiles) with
 * headroom for one prefetched page, otherwise visible content would be evicted by its own tiles.
 * Low-RAM devices get a smaller base resolution and no prefetch.
 */
object RenderBudgets {
    private const val MB = 1024L * 1024L

    fun forViewport(context: Context, viewportWidthPx: Int, viewportHeightPx: Int): RenderBudget {
        val am = context.getSystemService(ActivityManager::class.java)
        val lowRam = am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192
        val screenBytes = viewportWidthPx.toLong() * viewportHeightPx.toLong() * 4L
        val maxBaseWidth = if (lowRam) 1080 else 1440
        // One base page is at most maxBaseWidth wide; assume ~1.5 aspect ratio (A4/letter portrait).
        val basePageBytes = maxBaseWidth.toLong() * (maxBaseWidth * 3L / 2L) * 4L
        // A screen of visible tiles plus one ring of partially visible tiles.
        val tileBytes = screenBytes * 2
        val floor = basePageBytes * 3 + tileBytes
        val ceiling = if (lowRam) 64 * MB else 160 * MB
        val cache = min(ceiling, max(floor, 32 * MB))
        return RenderBudget(
            cacheBytes = cache,
            poolBytes = min(cache / 4, if (lowRam) 8 * MB else 24 * MB),
            maxBaseWidthPx = maxBaseWidth,
            prefetchPages = if (lowRam) 0 else 1,
        )
    }
}
