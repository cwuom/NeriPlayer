package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeFeedKindIds
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeLayout
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.ui.navigation.AppStartupDestinationDecision
import moe.ouom.neriplayer.ui.navigation.AppStartupDestinationInput
import moe.ouom.neriplayer.ui.navigation.appStartupDestinationDecision
import moe.ouom.neriplayer.ui.navigation.dispatch
import moe.ouom.neriplayer.ui.navigation.resolvedStartupGraphRoute
import moe.ouom.neriplayer.ui.navigation.startupHomeAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupDestinationDecisionTest {
    @Test
    fun hiddenInternationalHomeStartsAtExploreWithoutMountingHomeFirst() {
        val layout = InternationalHomeLayout(
            hidden = setOf(InternationalHomeSectionIds.SPOTLIGHT, InternationalHomeSectionIds.CONTINUE),
            hiddenFeedKinds = InternationalHomeFeedKindIds.DEFAULT_ORDER.toSet()
        )
        val availability = listOf(
            startupHomeAvailability(null, null, null, domesticAvailable = true),
            startupHomeAvailability(true, null, null, domesticAvailable = true),
            startupHomeAvailability(true, layout, null, domesticAvailable = true)
        )
        assertEquals(listOf(null, null, "explore"), availability.map {
            resolvedStartupGraphRoute("home", it, devModeEnabled = false)
        })
    }

    @Test
    fun onlyContinueNeedsHistoryAndExplicitTabsCanStartImmediately() {
        val onlyContinue = InternationalHomeLayout(
            hidden = setOf(InternationalHomeSectionIds.SPOTLIGHT, InternationalHomeSectionIds.FEED)
        )
        assertEquals(null, startupHomeAvailability(true, onlyContinue, null, false))
        assertEquals(false, startupHomeAvailability(true, onlyContinue, false, false))
        assertEquals(true, startupHomeAvailability(true, onlyContinue, true, false))
        assertEquals(true, startupHomeAvailability(true, InternationalHomeLayout(), null, false))
        assertEquals(false, startupHomeAvailability(false, InternationalHomeLayout(), null, false))
        assertEquals(null, resolvedStartupGraphRoute(null, true, false))
        assertEquals("home", resolvedStartupGraphRoute("home", true, false))
        for (route in listOf("explore", "library", "settings")) {
            assertEquals(route, resolvedStartupGraphRoute(route, null, false))
            assertEquals(route, resolvedStartupGraphRoute(route, false, false))
        }
    }

    @Test
    fun persistedDestinationReplacesFallbackOnlyWhileStartupIsAwaitingIt() {
        val fallback = Destinations.Home.route
        val destination = Destinations.Library.route
        val decision = appStartupDestinationDecision(
            input(
                currentRoute = fallback,
                defaultStartDestination = destination,
                effectiveStartDestination = destination,
                awaiting = true
            )
        )

        assertEquals(destination, decision.route)
        assertTrue(decision.replaceFallbackRoute)
        assertTrue(decision.clearAwaitingPersistedRoute)

        val settled = appStartupDestinationDecision(
            input(
                currentRoute = destination,
                defaultStartDestination = destination,
                effectiveStartDestination = destination,
                awaiting = false
            )
        )
        assertEquals(null, settled.route)
        assertFalse(settled.replaceFallbackRoute)
    }

    @Test
    fun hiddenHomeTabNavigatesAwayWithoutReplacingBackStackState() {
        val decision = appStartupDestinationDecision(
            input(
                currentRoute = Destinations.Home.route,
                defaultStartDestination = null,
                effectiveStartDestination = Destinations.Explore.route,
                awaiting = true,
                showHomeTab = false
            )
        )

        assertEquals(Destinations.Explore.route, decision.route)
        assertFalse(decision.replaceFallbackRoute)
        assertFalse(decision.clearAwaitingPersistedRoute)
    }

    @Test
    fun dispatchKeepsResolutionAndNavigationInTheSameDecision() {
        val events = mutableListOf<String>()
        AppStartupDestinationDecision(
            route = Destinations.Library.route,
            replaceFallbackRoute = true,
            clearAwaitingPersistedRoute = true
        ).dispatch(
            onPersistedRouteResolved = { events += "resolved" },
            navigate = { route, replace -> events += "$route:$replace" }
        )
        assertEquals(listOf("resolved", "${Destinations.Library.route}:true"), events)

        AppStartupDestinationDecision(
            route = null,
            replaceFallbackRoute = false,
            clearAwaitingPersistedRoute = false
        ).dispatch(
            onPersistedRouteResolved = { events += "unexpected resolution" },
            navigate = { _, _ -> events += "unexpected navigation" }
        )
        assertEquals(2, events.size)
    }

    private fun input(
        currentRoute: String?,
        defaultStartDestination: String?,
        effectiveStartDestination: String,
        awaiting: Boolean,
        showHomeTab: Boolean = true
    ) = AppStartupDestinationInput(
        currentRoute = currentRoute,
        showHomeTab = showHomeTab,
        effectiveStartDestination = effectiveStartDestination,
        defaultStartDestination = defaultStartDestination,
        navHostStartDestination = Destinations.Home.route,
        awaitingPersistedStartDestination = awaiting
    )
}
