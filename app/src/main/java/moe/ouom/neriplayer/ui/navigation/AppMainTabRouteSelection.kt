package moe.ouom.neriplayer.ui.navigation

import moe.ouom.neriplayer.navigation.Destinations

internal fun <T> selectMainTabRouteContent(
    route: String,
    home: T,
    explore: T,
    library: T,
    settings: T,
    debug: T
): T? = when (route) {
    Destinations.Home.route -> home
    Destinations.Explore.route -> explore
    Destinations.Library.route -> library
    Destinations.Settings.route -> settings
    Destinations.Debug.route -> debug
    else -> null
}
