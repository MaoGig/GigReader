package com.maogig.gigreader.feature.reader.nav

import com.maogig.gigreader.core.common.cache.WeightedLruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * A low-priority, one-at-a-time producer with a small byte-bounded cache. It is how thumbnails
 * share the PDF engine with the viewport without ever delaying it:
 *
 *  - **One at a time.** Only one item is being produced at any moment, so anything the viewport
 *    queues on the engine's serial lane waits for at most one small thumbnail.
 *  - **Only while idle.** Before each item the queue suspends in [awaitIdle] (for thumbnails: until
 *    the viewport's render pipeline has nothing to render). No polling: it is a flow collection.
 *  - **Latest wish wins.** [request] replaces the wish list; items that left it while queued are
 *    never produced, and each key is attempted at most once per wish list (a cache too small for
 *    the list cannot cause an evict/re-render loop).
 *  - **Event driven.** With nothing to produce the worker is suspended on a channel: no timers.
 *
 * [pause] (reader stopped) and [close] (panel closed) release every value and stop producing.
 * Values are not recycled on eviction: a cell may still be drawing one.
 */
class IdleGatedRenderQueue<K : Any, V : Any>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    maxWeight: Long,
    weigher: (V) -> Long,
    private val awaitIdle: suspend () -> Unit,
    /** Produces the value of a key, or `null` to skip it. [isWanted] turns false when it went stale. */
    private val produce: suspend (key: K, isWanted: () -> Boolean) -> V?,
) {
    private class Plan<K>(val keys: List<K>) {
        val set: Set<K> = keys.toHashSet()
    }

    private val cache = WeightedLruCache<K, V>(maxWeight, { _, v -> weigher(v) })
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val failed: MutableSet<K> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var plan = Plan<K>(emptyList())

    @Volatile
    private var paused = false

    @Volatile
    private var closed = false

    private val _version = MutableStateFlow(0L)

    /** Incremented whenever a value is added; cells re-read [peek] when it changes. */
    val version: StateFlow<Long> = _version.asStateFlow()

    val cachedBytes: Long get() = cache.weight

    private val worker: Job = scope.launch(dispatcher) {
        for (ignored in signal) drain()
    }

    /** Replaces the wish list (most wanted first). Cheap; call it whenever the visible range changes. */
    fun request(keys: List<K>) {
        if (closed || keys == plan.keys) return
        plan = Plan(keys)
        signal.trySend(Unit)
    }

    /** The cached value, if produced. Never produces or blocks; safe to call while drawing. */
    fun peek(key: K): V? = cache.peek(key)

    /** Stops producing and releases every value until [resume]. Idempotent. */
    fun pause() {
        if (closed) return
        paused = true
        cache.clear()
    }

    /** Produces the current wish list again after [pause]. No-op unless paused. */
    fun resume() {
        if (closed || !paused) return
        paused = false
        plan = Plan(plan.keys) // a fresh plan forgets what was attempted before the pause
        signal.trySend(Unit)
    }

    /** Ends the queue for good: cancels the worker and releases every value. */
    fun close() {
        closed = true
        worker.cancel()
        signal.close()
        cache.clear()
    }

    private suspend fun drain() {
        var attemptedFor: Plan<K>? = null
        val attempted = HashSet<K>()
        while (!paused && !closed) {
            val current = plan
            if (current !== attemptedFor) {
                attempted.clear()
                attemptedFor = current
            }
            val next = current.keys.firstOrNull { it !in attempted && it !in cache && it !in failed } ?: return
            awaitIdle()
            // Things may have changed while waiting for idle: choose again.
            if (paused || closed) return
            if (plan !== current) continue
            attempted += next
            val value = try {
                produce(next) { !paused && !closed && next in plan.set }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += next // a broken page is not retried in a loop
                null
            }
            if (value != null && !paused && !closed) {
                cache.put(next, value)
                _version.value++
            }
        }
    }
}
