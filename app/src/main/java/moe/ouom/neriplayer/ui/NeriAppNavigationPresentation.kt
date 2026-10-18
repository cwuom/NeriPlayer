package moe.ouom.neriplayer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import moe.ouom.neriplayer.navigation.Destinations

internal data class AppNavigationVisibility(
    val destination: NavDestination?,
    val currentRoute: String?,
    val routes: Set<String?>,
    val owners: Set<Any>
)

@Composable
internal fun rememberAppNavigationVisibility(
    navController: NavHostController,
    lifecycleOwner: LifecycleOwner
): AppNavigationVisibility {
    val backEntry by navController.currentBackStackEntryAsState()
    val visibleEntries by navController.visibleEntries.collectAsStateWithLifecycle()
    return remember(visibleEntries, backEntry, lifecycleOwner) {
        resolveAppNavigationVisibility(visibleEntries, backEntry, lifecycleOwner)
    }
}

internal fun resolveAppNavigationVisibility(
    visibleEntries: List<NavBackStackEntry>,
    backEntry: NavBackStackEntry?,
    lifecycleOwner: Any
): AppNavigationVisibility {
    val destination = backEntry?.destination
    val currentRoute = destination?.route
    // 退出中的导航页仍需保留玻璃归属，空过渡才回退到当前页
    val owners = buildSet {
        if (visibleEntries.isNotEmpty()) addAll(visibleEntries) else backEntry?.let(::add)
        add(lifecycleOwner)
    }
    val routes = visibleEntries.mapTo(linkedSetOf()) { it.destination.route }
    routes.add(currentRoute)
    return AppNavigationVisibility(destination, currentRoute, routes, owners)
}

internal fun appMainTabItems(showHomeTab: Boolean, devModeEnabled: Boolean): List<Pair<Destinations, ImageVector>> =
    buildList {
        if (showHomeTab) add(Destinations.Home to Icons.Outlined.Home)
        add(Destinations.Explore to Icons.Outlined.Search)
        add(Destinations.Library to Icons.Outlined.LibraryMusic)
        add(Destinations.Settings to Icons.Outlined.Settings)
        if (devModeEnabled) add(Destinations.Debug to Icons.Outlined.BugReport)
    }
