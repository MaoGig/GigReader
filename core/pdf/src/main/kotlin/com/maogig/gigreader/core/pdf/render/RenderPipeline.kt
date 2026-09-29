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
 *
 * While the reader is not visible the owner calls [pause] (all bitmaps released, nothing rendered)
 * and [resume] when it comes back.
 */
class RenderPipeline(
    private val document: PdfDocument,
    scope: CoroutineScope,
    val budget: RenderBudget,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val pool = BitmapPool(budget.poolBytes)

    /**
     * The most recently cached base key of each page, so a page whose exact key is missing (new
     * width after a rotation, resize or page-gap change; refined page size) can be drawn from a
     * base of another size meanwhile. May briefly name an evicted key; lookups then miss.
     */
    private val basesByPage = ConcurrentHashMap<Int, PageKey>()
    private val cache = WeightedLruCache<RenderKey, Bitmap>(
        maxWeight = budget.cacheBytes,
        weigher = { _, bitmap -> bitmap.allocationByteCount.toLong() },
        onEvicted = { key, bitmap ->
            if (key is PageKey) basesByPage.remove(key.page, key)
            recycle(bitmap)
        },
    )
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val failedPages: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var plan: RenderPlan = RenderPlan.Empty

    @Volatile
    private var wanted: Set<RenderKey> = emptySet()

    private val _busy = MutableStateFlow(false)

    /**
     * `true` while the worker is rendering (or deciding what to render). Lets lower-priority users of
     * the same engine (thumbnails) wait for idle instead of polling; conflated, so a burst of quick
     * drains costs collectors nothing.
     */
    val isBusy: StateFlow<Boolean> = _busy.asStateFlow()

    private var busy: Boolean
        get() = _busy.value
        set(value) {
            _busy.value = value
        }

    @Volatile
    private var closed = false

    /** Set by [pause]: nothing is rendered or cached until [resume]. */
    @Volatile
    private var paused = false

    /** Bumped by [resume]: the worker then forgets which keys it already attempted for the plan. */
    @Volatile
    private var attemptsGeneration = 0

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

    /**
     * Base bitmap for [key], or else a cached base of the same page at another size (to be scaled
     * by the caller) until the exact one is rendered. Called from draw code; allocates nothing
     * when [key] is cached.
     */
    fun baseBitmap(key: PageKey): Bitmap? {
        cache.peek(key)?.let { return it }
        val other = basesByPage[key.page] ?: return null
        if (other == key) return null
        val bitmap = cache.peek(other)
        if (bitmap == null) basesByPage.remove(key.page, other)
        return bitmap
    }

    fun isFailed(page: Int): Boolean = page in failedPages

    /** Drops everything that is not needed for the current plan (memory pressure). */
    fun trimToPlan() {
        val keep = wanted
        cache.removeIf { it !in keep }
        pool.clear()
    }

    /**
     * The document is not visible (reader stopped or covered): releases every cached and pooled
     * bitmap and renders nothing until [resume]. A render already running on the engine finishes
     * but its result is dropped. Idempotent.
     */
    fun pause() {
        if (closed) return
        paused = true
        releaseAll()
    }

    /** The document is visible again: renders what the current plan needs. No-op unless paused. */
    fun resume() {
        if (closed || !paused) return
        paused = false
        // Keys attempted before the pause were released with the cache; attempt them again.
        attemptsGeneration++
        signal.trySend(Unit)
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
        closed = true
        RenderDiagnostics.unregister(this)
        worker.cancel()
        signal.close()
        releaseAll()
    }

    private fun releaseAll() {
        cache.clear()
        pool.clear()
        basesByPage.clear()
    }

    /** Returns [bitmap] to the pool, unless everything is being released (paused or closed). */
    private fun recycle(bitmap: Bitmap) {
        if (!paused && !closed) pool.release(bitmap)
    }

    private suspend fun drain() {
        busy = true
        // Each key is attempted at most once per plan. Without this, a budget too small for the
        // whole plan would evict and re-render the same bitmaps forever (a CPU/battery loop).
        var attemptedFor: RenderPlan? = null
        var attemptedGeneration = -1
        var oomRetriedFor: RenderPlan? = null
        val attempted = HashSet<RenderKey>()
        try {
            while (!paused) {
                val current = plan
                val generation = attemptsGeneration
                if (current !== attemptedFor || generation != attemptedGeneration) {
                    attempted.clear()
                    attemptedFor = current
                    attemptedGeneration = generation
                }
                val next = current.keys.firstOrNull { it !in attempted && it !in cache && it.page !in failedPages }
                    ?: return
                if (!renderPage(next.page, current, attempted) && oomRetriedFor !== current) {
                    // The OOM trim may have evicted visible bitmaps already marked as attempted, which
                    // would leave them blank until the next scroll: retry the plan once (only once, so
                    // a real memory shortage cannot turn into a render loop).
                    oomRetriedFor = current
                    attempted.clear()
                }
            }
        } finally {
            busy = false
        }
    }

    /** Renders the missing keys of [page]. Returns `false` if it ran out of memory (cache trimmed). */
    private suspend fun renderPage(page: Int, current: RenderPlan, attempted: MutableSet<RenderKey>): Boolean {
        val candidates = current.keys.filter { it.page == page && it !in attempted && it !in cache }
        attempted.addAll(candidates)
        // A bitmap the platform refuses to draw would crash the UI thread on the next frame
        // ("Canvas: trying to draw too large bitmap"); such keys (extremely tall pages at base
        // resolution) are never rendered. The page stays blank at base zoom; its tiles still work.
        val keys = candidates.filter { it.bitmapBytes() <= MAX_DRAWABLE_BITMAP_BYTES }
        if (keys.isEmpty()) return true
        val jobs = ArrayList<RenderJob>(keys.size)
        val started = SystemClock.elapsedRealtime()
        val rendered = try {
            // Allocation is inside the try: createBitmap can throw OutOfMemoryError, which must not
            // escape the worker (it would crash the app through the owner's scope).
            for (key in keys) {
                jobs += when (key) {
                    is PageKey -> RenderJob(
                        pool.obtain(key.widthPx, key.heightPx), key.widthPx, key.heightPx,
                        isStale = { paused || key !in wanted },
                    )
                    is TileKey -> RenderJob(
                        pool.obtain(key.tileSize, key.tileSize), key.pageWidthPx, key.pageHeightPx, key.left, key.top,
                        isStale = { paused || key !in wanted },
                    )
                }
            }
            PerfTrace.async(PerfTrace.RENDER_PAGE) { document.render(page, jobs) }
        } catch (e: CancellationException) {
            // Cancellation almost always means close(): keep the emptied pool empty.
            jobs.forEach { recycle(it.target) }
            throw e
        } catch (e: OutOfMemoryError) {
            jobs.forEach { recycle(it.target) }
            cache.trimTo(cache.maxWeight / 2)
            pool.clear()
            return false
        } catch (e: Exception) {
            // Broken page (or engine error): remember it so we do not retry in a loop; the viewport
            // draws an error placeholder for it.
            jobs.forEach { recycle(it.target) }
            failedPages.add(page)
            _version.value++
            return true
        }
        lastRenderMillis = SystemClock.elapsedRealtime() - started
        // Closed or paused while this (uninterruptible) render ran: do not refill the cache/pool
        // that close()/pause() just emptied; the bitmaps are simply dropped.
        if (closed || paused) return true
        var any = false
        for (i in jobs.indices) {
            if (rendered[i]) {
                // Starts the GPU texture upload on RenderThread now, not on the first draw.
                jobs[i].target.prepareToDraw()
                val key = keys[i]
                if (cache.put(key, jobs[i].target) && key is PageKey) basesByPage[key.page] = key
                completed++
                any = true
            } else {
                recycle(jobs[i].target)
                skipped++
            }
        }
        // pause()/close() may have emptied the cache while the loop above refilled it.
        if (paused || closed) releaseAll() else if (any) _version.value++
        return true
    }

    private fun RenderKey.bitmapBytes(): Long = when (this) {
        is PageKey -> widthPx.toLong() * heightPx * 4L
        is TileKey -> tileSize.toLong() * tileSize * 4L
    }

    private companion object {
        /** RecordingCanvas' limit for drawing a (non-hardware) bitmap: 100 MB on older releases. */
        const val MAX_DRAWABLE_BITMAP_BYTES = 100L * 1024 * 1024
    }
}
