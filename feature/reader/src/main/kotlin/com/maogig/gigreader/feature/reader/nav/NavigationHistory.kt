package com.maogig.gigreader.feature.reader.nav

import com.maogig.gigreader.core.common.render.PagePosition
import kotlin.math.abs

/**
 * Back/forward stacks of reading positions, like a browser's. Only "jumps" (table of contents,
 * thumbnails, go-to-page, links, later search) are recorded, never ordinary scrolling or the
 * previous/next page buttons. Pure and bounded by [capacity]; used from the main thread only.
 */
class NavigationHistory(private val capacity: Int = DEFAULT_CAPACITY) {
    private val backStack = ArrayDeque<PagePosition>()
    private val forwardStack = ArrayDeque<PagePosition>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /** The position [back] would return to, without moving. */
    val backTarget: PagePosition? get() = backStack.lastOrNull()

    /** The position [forward] would return to, without moving. */
    val forwardTarget: PagePosition? get() = forwardStack.lastOrNull()

    /**
     * Records a jump from [from] (where the reader was) to [to]. Returns `false`, recording
     * nothing, when both are the same spot. A new jump discards the forward stack.
     */
    fun recordJump(from: PagePosition, to: PagePosition): Boolean {
        if (isSameSpot(from, to)) return false
        forwardStack.clear()
        // Two jumps in a row from the same place must not stack duplicates.
        if (backStack.lastOrNull()?.let { isSameSpot(it, from) } != true) push(backStack, from)
        return true
    }

    /**
     * Moves back: returns the position to show and remembers [current] for [forward]. Entries equal
     * to [current] are skipped (going "back" to where the reader already is would do nothing).
     */
    fun back(current: PagePosition): PagePosition? = move(backStack, forwardStack, current)

    /** The counterpart of [back]. */
    fun forward(current: PagePosition): PagePosition? = move(forwardStack, backStack, current)

    fun clear() {
        backStack.clear()
        forwardStack.clear()
    }

    private fun move(from: ArrayDeque<PagePosition>, to: ArrayDeque<PagePosition>, current: PagePosition): PagePosition? {
        while (from.isNotEmpty()) {
            val target = from.removeLast()
            if (isSameSpot(target, current)) continue
            push(to, current)
            return target
        }
        return null
    }

    private fun push(stack: ArrayDeque<PagePosition>, position: PagePosition) {
        if (stack.size == capacity) stack.removeFirst()
        stack.addLast(position)
    }

    companion object {
        const val DEFAULT_CAPACITY = 32

        /** Positions closer than this fraction of a page (on the same page) count as the same spot. */
        const val SAME_SPOT_TOLERANCE = 0.1f

        fun isSameSpot(a: PagePosition, b: PagePosition): Boolean =
            a.page == b.page && abs(a.pageOffset - b.pageOffset) < SAME_SPOT_TOLERANCE
    }
}
