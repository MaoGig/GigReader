package com.maogig.gigreader.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderTreeTest {
    private val tree = FolderTree(
        listOf(
            TreeNode("research", null, "Research"),
            TreeNode("papers", "research", "Papers"),
            TreeNode("ml", "papers", "ML"),
            TreeNode("work", null, "work"),
            TreeNode("orphan", "missing-parent", "Orphan"),
        ),
    )

    @Test
    fun pathIsRootFirst() {
        assertEquals(listOf("research", "papers", "ml"), tree.pathTo("ml").map { it.id })
        assertEquals(emptyList(), tree.pathTo(null))
    }

    @Test
    fun cyclesArePrevented() {
        assertFalse(tree.canMoveInto(listOf("research"), "ml"))
        assertFalse(tree.canMoveInto(listOf("papers"), "papers"))
        assertTrue(tree.canMoveInto(listOf("ml"), "work"))
        assertTrue(tree.canMoveInto(listOf("papers"), null))
    }

    @Test
    fun orphansAreTreatedAsRoots() {
        assertEquals(listOf("Orphan", "Research", "work"), tree.childrenOf(null).map { it.name })
    }

    @Test
    fun flattenIsDepthFirst() {
        assertEquals(
            listOf("Orphan" to 0, "Research" to 0, "Papers" to 1, "ML" to 2, "work" to 0),
            tree.flatten().map { it.first.name to it.second },
        )
    }

    @Test
    fun likeEscaping() {
        assertEquals("50\\%\\_a\\\\b", escapeLike("50%_a\\b"))
    }
}
