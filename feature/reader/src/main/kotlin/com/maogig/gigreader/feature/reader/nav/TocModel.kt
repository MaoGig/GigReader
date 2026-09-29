package com.maogig.gigreader.feature.reader.nav

import androidx.compose.runtime.Immutable
import com.maogig.gigreader.core.pdf.engine.OutlineItem

/** One row of the flattened table of contents. [id] is the node's index in document order. */
@Immutable
class TocNode(
    val id: Int,
    val title: String,
    /** Zero-based destination page, or -1 when the entry has none (then it is only a heading). */
    val page: Int,
    val yFraction: Float?,
    val depth: Int,
    val parentId: Int,
    val hasChildren: Boolean,
) {
    val isJumpTarget: Boolean get() = page >= 0
}

/**
 * The outline flattened in document order (parents before children) so the list can be rendered
 * lazily and the current section found without walking a tree. Immutable, built once per document
 * off the main thread.
 */
@Immutable
class TocModel private constructor(val nodes: List<TocNode>) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    /** Nodes to show when exactly the sections in [expanded] are open (a node needs all ancestors open). */
    fun visible(expanded: Set<Int>): List<TocNode> {
        if (nodes.none { it.hasChildren }) return nodes
        val out = ArrayList<TocNode>(nodes.size)
        // Nodes come parents-first, so one pass tells whether each node is reachable.
        val shown = BooleanArray(nodes.size)
        for (node in nodes) {
            val reachable = node.parentId < 0 || (shown[node.parentId] && node.parentId in expanded)
            shown[node.id] = reachable
            if (reachable) out += node
        }
        return out
    }

    /**
     * The section containing [page]: the entry with the greatest destination page not after it (the
     * deepest / latest one when several share that page), or -1 before the first entry.
     */
    fun currentNodeId(page: Int): Int {
        var best = -1
        var bestPage = -1
        for (node in nodes) {
            if (node.page in 0..page && node.page >= bestPage) {
                best = node.id
                bestPage = node.page
            }
        }
        return best
    }

    /** Ids of every ancestor of [id], nearest first. */
    fun ancestorsOf(id: Int): List<Int> {
        val out = ArrayList<Int>(4)
        var parent = nodes.getOrNull(id)?.parentId ?: -1
        while (parent >= 0) {
            out += parent
            parent = nodes[parent].parentId
        }
        return out
    }

    /** [expanded] plus the ancestors of [id], so that node is visible. */
    fun expandedToReveal(id: Int, expanded: Set<Int>): Set<Int> {
        val ancestors = ancestorsOf(id)
        return if (expanded.containsAll(ancestors)) expanded else expanded + ancestors
    }

    companion object {
        val Empty = TocModel(emptyList())

        /** Entries deeper than this are dropped (the engine already limits depth; this is a backstop). */
        const val MAX_DEPTH = 24

        fun from(items: List<OutlineItem>): TocModel {
            if (items.isEmpty()) return Empty
            val out = ArrayList<TocNode>()
            // Iterative pre-order walk: a hostile outline cannot overflow the stack.
            class Frame(val items: List<OutlineItem>, val depth: Int, val parent: Int, var next: Int = 0)
            val stack = ArrayDeque<Frame>()
            stack.addLast(Frame(items, 0, -1))
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                if (frame.next >= frame.items.size) {
                    stack.removeLast()
                    continue
                }
                val item = frame.items[frame.next++]
                val id = out.size
                val children = item.children.takeIf { frame.depth + 1 < MAX_DEPTH }.orEmpty()
                out += TocNode(id, item.title, item.page, item.yFraction, frame.depth, frame.parent, hasChildren = children.isNotEmpty())
                if (children.isNotEmpty()) stack.addLast(Frame(children, frame.depth + 1, id))
            }
            return TocModel(out)
        }
    }
}
