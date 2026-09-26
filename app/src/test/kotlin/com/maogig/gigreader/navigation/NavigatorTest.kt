package com.maogig.gigreader.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigatorTest {
    private val removed = ArrayList<BackStackEntry>()

    private fun navigator(vararg path: Destination): Navigator {
        val navigator = Navigator(listOf(Navigator.newEntry(Destination.Library())))
        navigator.onEntryRemoved = { removed += it }
        path.forEach { navigator.navigate(it) }
        return navigator
    }

    private fun Navigator.destinations() = backStack.map { it.destination }

    @Test
    fun navigateOrPopTo_returnsToAnExistingEntryAndDropsTheOnesAbove() {
        val nav = navigator(Destination.Library("a"), Destination.Library("b"), Destination.Library("c"))
        val a = nav.backStack[1]

        nav.navigateOrPopTo(Destination.Library("a"))

        assertEquals(listOf(Destination.Library(), Destination.Library("a")), nav.destinations())
        assertEquals(a.key, nav.current.key) // the same entry: its saved state and ViewModels survive
        assertEquals(listOf(Destination.Library("c"), Destination.Library("b")), removed.map { it.destination })
    }

    @Test
    fun navigateOrPopTo_pushesWhenNotInTheStack() {
        val nav = navigator(Destination.Library("a"))

        nav.navigateOrPopTo(Destination.Library("b"))

        assertEquals(listOf(Destination.Library(), Destination.Library("a"), Destination.Library("b")), nav.destinations())
        assertTrue(removed.isEmpty())
    }

    @Test
    fun navigateOrPopTo_theCurrentDestinationChangesNothing() {
        val nav = navigator(Destination.Library("a"))
        val top = nav.current

        nav.navigateOrPopTo(Destination.Library("a"))

        assertEquals(top, nav.current)
        assertEquals(2, nav.backStack.size)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun navigateOrPopTo_theRootPopsEverything() {
        val nav = navigator(Destination.Library("a"), Destination.Library("b"))

        nav.navigateOrPopTo(Destination.Library())

        assertEquals(listOf(Destination.Library()), nav.destinations())
        assertEquals(2, removed.size)
    }

    @Test
    fun popToRoot_keepsOnlyTheRoot() {
        val nav = navigator(Destination.Library("a"), Destination.Library("b"), Destination.Settings)
        val root = nav.backStack.first()

        nav.popToRoot()

        assertEquals(listOf(root), nav.backStack.toList())
        assertFalse(nav.canGoBack)
        assertEquals(
            listOf(Destination.Settings, Destination.Library("b"), Destination.Library("a")),
            removed.map { it.destination },
        )
        nav.popToRoot() // already there: no-op
        assertEquals(3, removed.size)
    }
}
