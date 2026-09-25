package com.maogig.gigreader.core.common

/** Minimal node used for folder-tree algorithms (no Android or database types). */
data class TreeNode(val id: String, val parentId: String?, val name: String)

/**
 * In-memory folder tree built from a flat list (one query), used for breadcrumbs, the move dialog
 * and cycle prevention. Building it is O(n) and it is never rebuilt during scrolling.
 */
class FolderTree(nodes: List<TreeNode>) {
    private val byId: Map<String, TreeNode> = nodes.associateBy { it.id }
    private val children: Map<String?, List<TreeNode>> =
        nodes.groupBy { it.parentId?.takeIf { p -> p in byId } }
            .mapValues { (_, list) -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }

    operator fun get(id: String): TreeNode? = byId[id]

    fun childrenOf(parentId: String?): List<TreeNode> = children[parentId].orEmpty()

    /** Root-first path to [folderId] (inclusive); empty for the root or unknown ids. */
    fun pathTo(folderId: String?): List<TreeNode> {
        if (folderId == null) return emptyList()
        val path = ArrayList<TreeNode>()
        var current = byId[folderId]
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            path.add(current)
            current = current.parentId?.let(byId::get)
        }
        return path.asReversed()
    }

    /** True if [candidate] is [folderId] itself or one of its descendants. */
    fun isSelfOrDescendant(candidate: String?, folderId: String): Boolean {
        var current = candidate
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            if (current == folderId) return true
            current = byId[current]?.parentId
        }
        return false
    }

    /** Whether the folders [movingFolderIds] can be moved into [target] (null = root) without a cycle. */
    fun canMoveInto(movingFolderIds: Collection<String>, target: String?): Boolean =
        movingFolderIds.none { isSelfOrDescendant(target, it) }

    /** Depth-first flattening for pickers: (node, depth) in display order. */
    fun flatten(): List<Pair<TreeNode, Int>> {
        val out = ArrayList<Pair<TreeNode, Int>>(byId.size)
        fun visit(parent: String?, depth: Int) {
            for (child in childrenOf(parent)) {
                out.add(child to depth)
                visit(child.id, depth + 1)
            }
        }
        visit(null, 0)
        return out
    }
}
