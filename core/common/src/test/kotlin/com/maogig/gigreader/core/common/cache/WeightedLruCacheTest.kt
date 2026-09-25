package com.maogig.gigreader.core.common.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightedLruCacheTest {
    private val evicted = mutableListOf<String>()
    private val cache = WeightedLruCache<String, Int>(
        maxWeight = 10,
        weigher = { _, v -> v.toLong() },
        onEvicted = { k, _ -> evicted.add(k) },
    )

    @Test
    fun evictsLeastRecentlyUsedWhenOverBudget() {
        cache.put("a", 4)
        cache.put("b", 4)
        cache["a"] // a becomes most recent
        cache.put("c", 4) // 12 > 10 → evict b
        assertEquals(listOf("b"), evicted)
        assertTrue("a" in cache)
        assertTrue("c" in cache)
        assertEquals(8, cache.weight)
    }

    @Test
    fun rejectsEntriesHeavierThanTheBudgetWithoutFlushing() {
        cache.put("a", 3)
        assertFalse(cache.put("huge", 11))
        assertEquals(listOf("huge"), evicted)
        assertTrue("a" in cache)
    }

    @Test
    fun replacingAKeyReleasesThePreviousValue() {
        cache.put("a", 3)
        cache.put("a", 5)
        assertEquals(listOf("a"), evicted)
        assertEquals(5, cache.weight)
    }

    @Test
    fun trimAndRemoveIf() {
        cache.put("p1-t1", 2)
        cache.put("p1-t2", 2)
        cache.put("p2-t1", 2)
        cache.removeIf { it.startsWith("p1") }
        assertEquals(setOf("p1-t1", "p1-t2"), evicted.toSet())
        assertEquals(2, cache.weight)
        cache.trimTo(0)
        assertEquals(0, cache.size)
        assertNull(cache["p2-t1"])
    }

    @Test
    fun statisticsCountHitsAndMisses() {
        cache.put("a", 1)
        cache["a"]
        cache["missing"]
        assertEquals(1, cache.hitCount)
        assertEquals(1, cache.missCount)
    }
}
