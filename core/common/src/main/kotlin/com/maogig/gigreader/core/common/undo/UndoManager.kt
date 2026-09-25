package com.maogig.gigreader.core.common.undo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An edit that has already been applied and knows how to revert/re-apply itself.
 * Implementations must be idempotent with respect to the data they touch (e.g. "restore from trash"
 * on an item that is not in the trash is a no-op), because the user can race undo with other edits.
 */
interface UndoableAction {
    /** Short user-facing description, e.g. "Moved 3 items". */
    val label: String

    suspend fun undo()

    suspend fun redo()
}

data class UndoState(
    val undoLabel: String? = null,
    val redoLabel: String? = null,
) {
    val canUndo: Boolean get() = undoLabel != null
    val canRedo: Boolean get() = redoLabel != null
}

/**
 * Bounded undo/redo history (command pattern). Recording a new action clears the redo stack.
 * All operations are serialized, so a quick double tap on "Undo" cannot undo the same action twice.
 */
class UndoManager(private val capacity: Int = 50) {
    private val mutex = Mutex()
    private val undoStack = ArrayDeque<UndoableAction>()
    private val redoStack = ArrayDeque<UndoableAction>()
    private val _state = MutableStateFlow(UndoState())
    val state: StateFlow<UndoState> = _state.asStateFlow()

    init {
        require(capacity > 0)
    }

    /** Records an action that the caller has *already* performed. */
    suspend fun record(action: UndoableAction) = mutex.withLock {
        undoStack.addLast(action)
        while (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
        publish()
    }

    /** Returns the label of the undone action, or `null` if there was nothing to undo. */
    suspend fun undo(): String? = mutex.withLock {
        val action = undoStack.removeLastOrNull() ?: return@withLock null
        try {
            action.undo()
            redoStack.addLast(action)
        } finally {
            publish()
        }
        action.label
    }

    suspend fun redo(): String? = mutex.withLock {
        val action = redoStack.removeLastOrNull() ?: return@withLock null
        try {
            action.redo()
            undoStack.addLast(action)
        } finally {
            publish()
        }
        action.label
    }

    suspend fun clear() = mutex.withLock {
        undoStack.clear()
        redoStack.clear()
        publish()
    }

    private fun publish() {
        _state.value = UndoState(undoStack.lastOrNull()?.label, redoStack.lastOrNull()?.label)
    }
}
