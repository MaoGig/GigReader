package com.maogig.gigreader.core.common.cache

/**
 * Thread-safe LRU cache bounded by the total *weight* of its entries (e.g. bitmap bytes), with an
 * eviction callback so evicted values can be recycled into a pool.
 *
 * Every cache in the app is an instance of this class with an explicit budget, so no cache can grow
 * without bound. [onEvicted] is invoked outside the lock.
 */
class WeightedLruCache<K : Any, V : Any>(
    maxWeight: Long,
    private val weigher: (K, V) -> Long,
    private val onEvicted: (K, V) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val map = LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true)

    @Volatile
    var maxWeight: Long = maxWeight
        private set

    @Volatile
    var weight: Long = 0
        private set

    @Volatile
    var hitCount: Long = 0
        private set

    @Volatile
    var missCount: Long = 0
        private set

    val size: Int get() = synchronized(lock) { map.size }

    operator fun get(key: K): V? = synchronized(lock) {
        val v = map[key]
        if (v == null) missCount++ else hitCount++
        v
    }

    /** Looks up without touching hit statistics (draw-time probing). Still refreshes recency. */
    fun peek(key: K): V? = synchronized(lock) { map[key] }

    operator fun contains(key: K): Boolean = synchronized(lock) { map.containsKey(key) }

    /**
     * Inserts [value]. Entries heavier than the whole budget are rejected (and handed straight to
     * [onEvicted]) instead of flushing the cache. Returns `true` if the value was stored.
     */
    fun put(key: K, value: V): Boolean {
        val w = weigher(key, value)
        require(w >= 0) { "negative weight" }
        val evicted = ArrayList<Pair<K, V>>(2)
        val stored: Boolean
        synchronized(lock) {
            if (w > maxWeight) {
                stored = false
            } else {
                val previous = map.put(key, value)
                weight += w
                if (previous != null) {
                    weight -= weigher(key, previous)
                    if (previous !== value) evicted.add(key to previous)
                }
                trimLocked(maxWeight, evicted)
                stored = true
            }
        }
        if (!stored) onEvicted(key, value)
        evicted.forEach { (k, v) -> onEvicted(k, v) }
        return stored
    }

    fun remove(key: K): V? {
        val v = synchronized(lock) {
            map.remove(key)?.also { weight -= weigher(key, it) }
        }
        if (v != null) onEvicted(key, v)
        return v
    }

    /** Removes every entry matching [predicate] (e.g. all tiles of an obsolete zoom bucket). */
    fun removeIf(predicate: (K) -> Boolean) {
        val evicted = ArrayList<Pair<K, V>>()
        synchronized(lock) {
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (predicate(e.key)) {
                    it.remove()
                    weight -= weigher(e.key, e.value)
                    evicted.add(e.key to e.value)
                }
            }
        }
        evicted.forEach { (k, v) -> onEvicted(k, v) }
    }

    /** Shrinks the cache to at most [targetWeight] (used on memory pressure). */
    fun trimTo(targetWeight: Long) {
        val evicted = ArrayList<Pair<K, V>>()
        synchronized(lock) { trimLocked(targetWeight.coerceAtLeast(0), evicted) }
        evicted.forEach { (k, v) -> onEvicted(k, v) }
    }

    fun resize(newMaxWeight: Long) {
        require(newMaxWeight >= 0)
        maxWeight = newMaxWeight
        trimTo(newMaxWeight)
    }

    fun clear() = trimTo(0)

    fun keys(): List<K> = synchronized(lock) { map.keys.toList() }

    private fun trimLocked(target: Long, out: MutableList<Pair<K, V>>) {
        if (weight <= target) return
        val it = map.entries.iterator()
        while (weight > target && it.hasNext()) {
            val e = it.next()
            it.remove()
            weight -= weigher(e.key, e.value)
            out.add(e.key to e.value)
        }
    }
}
