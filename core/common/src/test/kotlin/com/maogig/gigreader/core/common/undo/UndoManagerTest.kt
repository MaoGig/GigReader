package com.maogig.gigreader.core.common.undo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoManagerTest {
    private class Counter { var value = 0 }

    private fun increment(counter: Counter, label: String) = object : UndoableAction {
        override val label = label
        override suspend fun undo() { counter.value-- }
        override suspend fun redo() { counter.value++ }
    }

    @Test
    fun undoAndRedoRestoreState() = runTest {
        val counter = Counter()
        val undo = UndoManager()
        counter.value++
        undo.record(increment(counter, "inc"))
        assertEquals("inc", undo.state.value.undoLabel)

        assertEquals("inc", undo.undo())
        assertEquals(0, counter.value)
        assertTrue(undo.state.value.canRedo)

        assertEquals("inc", undo.redo())
        assertEquals(1, counter.value)
        assertFalse(undo.state.value.canRedo)
    }

    @Test
    fun recordingClearsRedoAndCapacityIsBounded() = runTest {
        val counter = Counter()
        val undo = UndoManager(capacity = 2)
        repeat(3) { i ->
            counter.value++
            undo.record(increment(counter, "inc$i"))
        }
        assertEquals("inc2", undo.undo())
        assertEquals("inc1", undo.undo())
        assertNull(undo.undo(), "oldest action was dropped")
        assertEquals(1, counter.value)

        undo.redo()
        counter.value++
        undo.record(increment(counter, "new"))
        assertFalse(undo.state.value.canRedo)
    }
}
