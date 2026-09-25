package com.maogig.gigreader.core.pdf.render

import android.graphics.Bitmap
import android.os.SystemClock
import com.maogig.gigreader.core.common.cache.WeightedLruCache
import com.maogig.gigreader.core.common.render.PageKey
import com.maogig.gigreader.core.common.render.RenderKey
import com.maogig.gigreader.core.common.render.RenderPlan
import com.maogig.gigreader.core.common.render.TileKey
import com.maogig.gigreader.core.pdf.PerfTrace
import com.maogig.gigreader.core.pdf.engine.PdfDocument
import com.maogig.gigreader.core.pdf.engine.RenderJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Memory limits for one open document, derived from the device and viewport (see [RenderBudgets]). */
data class RenderBudget(
    /** Upper bound for all cached page and tile bitmaps. */
    val cacheBytes: Long,
    /** Upper bound for idle bitmaps kept for reuse. */
    val poolBytes: Long,
    val maxBaseWidthPx: Int,
    val prefetchPages: Int,
    val tileSize: Int = 512,
)

/** Snapshot for the debug diagnostics screen. */
data class RenderStats(
    val cachedBitmaps: Int,
    val cacheBytes: Long,
    val cacheBudgetBytes: Long,
    val poolBytes: Long,
    val hits: Long,
    val misses: Long,
    val rendersCompleted: Long,
    val rendersSkippedStale: Long,
    val lastRenderMillis: Long,
    val failedPages: Int,
    val busy: Boolean,
)

/**
 * Turns [RenderPlan]s into cached bitmaps for one document.
 *
 * Event driven: [request] stores the latest plan and wakes the worker through a conflated channel;
 * the worker renders missing keys in plan order (page by page, loading each page once) and then
 * suspends. With nothing to render it consumes no CPU, runs no timer and holds no thread.
 * Work that falls out of the plan while queued is skipped, never rendered.
 */
class RenderPipeline(
    private val document: PdfDocument,
    scope: CoroutineScope,
    val budget: RenderBudget,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val pool = BitmapPool(budget.poolBytes)
    private val cache = WeightedLruCache<RenderKey, Bitmap>(
        maxWeight = budget.cacheBytes,
        weigher = { _, bitmap -> bitmap.allocationByteCount.toLong() },
        onEvicted = { _, bitmap -> pool.release(bitmap) },
    )
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val failedPages: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var plan: RenderPlan = RenderPlan.Empty

    @Volatile
    private var wanted: Set<RenderKey> = emptySet()

    @Volatile
    private var busy = false

    @Volatile
    private var lastRenderMillis = 0L

    @Volatile
    private var completed = 0L

    @Volatile
    private var skipped = 0L

    private val _version = MutableStateFlow(0L)

    /** Incremented whenever new bitmaps become available; the viewport redraws when it changes. */
    val version: StateFlow<Long> = _version.asStateFlow()

    private val worker: Job = scope.launch(workerDispatcher) {
        for (ignored in signal) drain()
    }

    init {
        RenderDiagnostics.register(this)
    }

    /** Replaces the current wish list. Cheap; call it whenever the viewport changes. */
    fun request(newPlan: RenderPlan) {
        if (newPlan == plan) return
        plan = newPlan
        wanted = newPlan.keys.toHashSet()
        signal.trySend(Unit)
    }

    /** Bitmap for [key] if rendered. Called from draw code; never renders or blocks. */
    fun bitmap(key: RenderKey): Bitmap? = cache.peek(key)

    fun isFailed(page: Int): Boolean = page in failedPages

    /** Drops everything that is not needed for the current plan (memory pressure). */
    fun trimToPlan() {
        val keep = wanted
        cache.removeIf { it !in keep }
        pool.clear()
    }

    /** Drops tiles of other zoom buckets, e.g. after the zoom settled at a new level. */
    fun dropTilesExcept(bucket: Int) {
        cache.removeIf { it is TileKey && it.bucket != bucket }
    }

    fun stats(): RenderStats = RenderStats(
        cachedBitmaps = cache.size,
        cacheBytes = cache.weight,
        cacheBudgetBytes = cache.maxWeight,
        poolBytes = pool.sizeBytes,
        hits = cache.hitCount,
        misses = cache.missCount,
        rendersCompleted = completed,
        rendersSkippedStale = skipped,
        lastRenderMillis = lastRenderMillis,
        failedPages = failedPages.size,
        busy = busy,
    )

    /** Stops the worker and releases all bitmaps. The document itself is closed by its owner. */
    fun close() {
        RenderDiagnostics.unregister(this)
        worker.cancel()
        signal.close()
        cache.clear()
        pool.clear()
    }

    private suspend fun drain() {
        busy = true
        // Each key is attempted at most once per plan. Without this, a budget too small for the
        // whole plan would evict and re-render the same bitmaps forever (a CPU/battery loop).
        var attemptedFor: RenderPlan? = null
        val attempted = HashSet<RenderKey>()
        try {
            while (true) {
                val current = plan
                if (current !== attemptedFor) {
                    attempted.clear()
                    attemptedFor = current
                }
                val next = current.keys.firstOrNull { it !in attempted && it !in cache && it.page !in failedPages }
                    ?: return
                renderPage(next.page, current, attempted)
            }
        } finally {
            busy = false
        }
    }

    private suspend fun renderPage(page: Int, current: RenderPlan, attempted: MutableSet<RenderKey>) {
        val keys = current.keys.filter { it.page == page && it !in attempted && it !in cache }
        attempted.addAll(keys)
        val jobs = keys.map { key ->
            val bitmap = when (key) {
                is PageKey -> pool.obtain(key.widthPx, key.heightPx)
                is TileKey -> pool.obtain(key.tileSize, key.tileSize)
            }
            when (key) {
                is PageKey -> RenderJob(bitmap, key.widthPx, key.heightPx, isStale = { key !in wanted })
                is TileKey -> RenderJob(
                    bitmap, key.pageWidthPx, key.pageHeightPx, key.left, key.top,
                    isStale = { key !in wanted },
                )
            }
        }
        val started = SystemClock.elapsedRealtime()
        val rendered = try {
            PerfTrace.async(PerfTrace.RENDER_PAGE) { document.render(page, jobs) }
        } catch (e: CancellationException) {
            jobs.forEach { pool.release(it.target) }
            throw e
        } catch (e: OutOfMemoryError) {
            jobs.forEach { pool.release(it.target) }
            cache.trimTo(cache.maxWeight / 2)
            pool.clear()
            return
        } catch (e: Exception) {
            // Broken page (or engine error): remember it so we do not retry in a loop; the viewport
            // draws an error placeholder for it.
            jobs.forEach { pool.release(it.target) }
            failedPages.add(page)
            _version.value++
            return
        }
        lastRenderMillis = SystemClock.elapsedRealtime() - started
        var any = false
        for (i in jobs.indices) {
            if (rendered[i]) {
                cache.put(keys[i], jobs[i].target)
                completed++
                any = true
            } else {
                pool.release(jobs[i].target)
                skipped++
            }
        }
        if (any) _version.value++
    }
}
