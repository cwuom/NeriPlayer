package moe.ouom.neriplayer.ui.component.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class NeriNavigationDestinationSelectionTest {

    @Test
    fun `no current destination selects no tab`() {
        assertFalse(isNeriNavigationDestinationSelected(null, Destinations.Home))
    }

    @Test
    fun `a top level route selects only its own tab`() {
        val explore = destination(route = "explore")

        assertTrue(isNeriNavigationDestinationSelected(explore, Destinations.Explore))
        assertFalse(isNeriNavigationDestinationSelected(explore, Destinations.Home))
    }

    @Test
    fun `a nested destination selects the tab of an enclosing graph`() {
        val root = graph(route = null, parent = null)
        val library = graph(route = "library", parent = root)
        val playlistDetail = destination(route = "playlist_detail/{id}", parent = library)

        assertTrue(isNeriNavigationDestinationSelected(playlistDetail, Destinations.Library))
        assertFalse(isNeriNavigationDestinationSelected(playlistDetail, Destinations.Settings))
    }

    private fun destination(route: String?, parent: NavGraph? = null): NavDestination =
        mock(NavDestination::class.java).also { destination ->
            `when`(destination.route).thenReturn(route)
            `when`(destination.parent).thenReturn(parent)
        }

    private fun graph(route: String?, parent: NavGraph?): NavGraph =
        mock(NavGraph::class.java).also { graph ->
            `when`(graph.route).thenReturn(route)
            `when`(graph.parent).thenReturn(parent)
        }
}
