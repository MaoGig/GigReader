package com.maogig.gigreader.core.common

import com.maogig.gigreader.core.common.coroutines.CoalescingSaver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingSaverTest {
    @Test
    fun onlyTheLatestValueIsWrittenOncePerWindow() = runTest {
        val saved = mutableListOf<Int>()
        val saver = CoalescingSaver<Int>(backgroundScope, delayMillis = 1000) { saved.add(it) }
        saver.submit(1)
        saver.submit(2)
        advanceTimeBy(500)
        saver.submit(3)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(listOf(3), saved)
        assertFalse(saver.hasPending)
    }

    @Test
    fun continuousChangesAreStillPersistedEveryWindow() = runTest {
        val saved = mutableListOf<Int>()
        val saver = CoalescingSaver<Int>(backgroundScope, delayMillis = 100) { saved.add(it) }
        for (i in 1..10) {
            saver.submit(i)
            advanceTimeBy(40)
            runCurrent()
        }
        assertTrue(saved.size >= 3, "throttled writes happened: $saved")
        saver.flush()
        assertEquals(10, saved.last())
    }

    @Test
    fun failedWriteIsRetriedOnNextFlush() = runTest {
        var fail = true
        val saved = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val saver = CoalescingSaver<Int>(backgroundScope, 100, onError = { errors.add(it) }) {
            if (fail) error("disk full")
            saved.add(it)
        }
        saver.submit(7)
        saver.flush()
        assertEquals(1, errors.size)
        assertTrue(saver.hasPending)
        fail = false
        saver.flush()
        assertEquals(listOf(7), saved)
    }
}
