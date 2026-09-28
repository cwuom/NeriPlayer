package moe.ouom.neriplayer.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.effect.glass.DRAWER_BACKGROUND_SINK_FRACTION
import moe.ouom.neriplayer.ui.effect.glass.DRAWER_RECESSED_CONTENT_SCALE
import kotlin.collections.get

private val MAIN_TAB_ROUTES = listOf(
    Destinations.Home.route,
    Destinations.Explore.route,
    Destinations.Library.route,
    Destinations.Settings.route,
    Destinations.Debug.route
)
private val TRANSPARENT_MAIN_TAB_DETAIL_ROUTES = setOf(
    Destinations.PlaylistDetail.route,
    Destinations.NeteaseAlbumDetail.route,
    Destinations.NeteaseArtistDetail.route,
    Destinations.BiliPlaylistDetail.route,
    Destinations.BiliUploaderDetail.route,
    Destinations.YouTubeMusicCreatorDetail.route,
    Destinations.YouTubeMusicPlaylistDetail.route,
    Destinations.LocalPlaylistDetail.route,
    Destinations.Recent.route,
    Destinations.PlaybackStats.route,
    Destinations.DownloadManager.route,
    Destinations.DownloadProgress.route
)
private val DEBUG_NAVIGATION_DEPTH_BY_ROUTE = mapOf(
    Destinations.Debug.route to 0,
    Destinations.DebugListenTogether.route to 1,
    Destinations.DebugUsbExclusive.route to 1,
    Destinations.DebugYouTube.route to 1,
    Destinations.DebugBili.route to 1,
    Destinations.DebugNetease.route to 1,
    Destinations.DebugSearch.route to 1,
    Destinations.DebugLogsList.route to 1,
    Destinations.DebugCrashLogsList.route to 1,
    Destinations.DebugLogViewer.route to 2
)
private val DEBUG_MAIN_TAB_CHILD_ROUTES = DEBUG_NAVIGATION_DEPTH_BY_ROUTE
    .filterValues { depth -> depth > 0 }
    .keys

internal fun transparentNavigationDepth(route: String?): Int {
    val debugDepth = DEBUG_NAVIGATION_DEPTH_BY_ROUTE[route]
    if (debugDepth != null) return debugDepth
    return when {
        route == Destinations.NeteaseAlbumDetail.route ||
            route == Destinations.YouTubeMusicPlaylistDetail.route ||
            route == Destinations.DownloadProgress.route -> 2
        route in TRANSPARENT_MAIN_TAB_DETAIL_ROUTES -> 1
        else -> 0
    }
}

internal fun shouldUseInstantBiliUploaderPlaylistTransition(
    initialRoute: String?,
    targetRoute: String?
): Boolean {
    return (initialRoute == Destinations.BiliUploaderDetail.route &&
        targetRoute == Destinations.BiliPlaylistDetail.route) ||
        (initialRoute == Destinations.BiliPlaylistDetail.route &&
            targetRoute == Destinations.BiliUploaderDetail.route)
}

internal const val MAIN_TAB_DETAIL_OPEN_DURATION_MS = 220
internal const val MAIN_TAB_DETAIL_CLOSE_DURATION_MS = 240
internal const val DRAWER_DETAIL_OPEN_DURATION_MS = 300
internal const val DRAWER_DETAIL_CLOSE_DURATION_MS = 280

private const val DRAWER_ROOT_RETAIN_ALPHA = 0.999f
internal const val DEBUG_NAVIGATION_OPEN_DURATION_MS = 220
internal const val DEBUG_NAVIGATION_CLOSE_DURATION_MS = 240
internal enum class MainTabDetailHandoff {
    OPEN_DETAIL,
    RETURN_TO_TAB
}

internal enum class MainTabBackgroundMotion {
    NONE,
    COHERENT_EXIT,
    DRAWER_SINK
}

internal data class MainTabBackgroundTransform(
    val translationYFraction: Float,
    val scale: Float,
    val alpha: Float
)

internal fun resolveMainTabTransitionDirection(
    initialRoute: String?,
    targetRoute: String?
): Int? {
    val initialIndex = MAIN_TAB_ROUTES.indexOf(initialRoute)
    val targetIndex = MAIN_TAB_ROUTES.indexOf(targetRoute)
    if (initialIndex < 0 || targetIndex < 0 || initialIndex == targetIndex) return null
    return if (targetIndex > initialIndex) 1 else -1
}

internal fun shouldDispatchMainTabNavigation(
    currentRoute: String?,
    pendingRoute: String?,
    targetRoute: String
): Boolean = pendingRoute != targetRoute &&
    (currentRoute != targetRoute || pendingRoute != null)

internal fun shouldAcceptObservedMainTabRoute(
    observedRoute: String?,
    pendingRoute: String?
): Boolean = observedRoute != null &&
    observedRoute in MAIN_TAB_ROUTES &&
    (pendingRoute == null || pendingRoute == observedRoute)

internal fun shouldUseAdvancedGlassNavigationHandoff(
    visibleRoutes: Collection<String?>
): Boolean {
    val routes = visibleRoutes.filterNotNull().toSet()
    return routes.size > 1 && routes.any { it !in MAIN_TAB_ROUTES }
}

internal fun resolveMainTabDetailHandoff(
    initialRoute: String?,
    targetRoute: String?
): MainTabDetailHandoff? {
    if (initialRoute == null || targetRoute == null) return null
    val initialIsMainTab = initialRoute in MAIN_TAB_ROUTES
    val targetIsMainTab = targetRoute in MAIN_TAB_ROUTES
    return when {
        initialIsMainTab && targetRoute in TRANSPARENT_MAIN_TAB_DETAIL_ROUTES ->
            MainTabDetailHandoff.OPEN_DETAIL
        initialRoute in TRANSPARENT_MAIN_TAB_DETAIL_ROUTES && targetIsMainTab ->
            MainTabDetailHandoff.RETURN_TO_TAB
        else -> null
    }
}

internal fun resolveDebugNavigationTransitionDirection(
    initialRoute: String?,
    targetRoute: String?
): Int? {
    val initialDepth = DEBUG_NAVIGATION_DEPTH_BY_ROUTE[initialRoute] ?: return null
    val targetDepth = DEBUG_NAVIGATION_DEPTH_BY_ROUTE[targetRoute] ?: return null
    if (initialDepth == targetDepth) return null
    return if (targetDepth > initialDepth) 1 else -1
}

internal fun resolveMainTabBackgroundMotion(
    route: String?,
    coherentFeedbackEnabled: Boolean
): MainTabBackgroundMotion {
    val hasBackgroundHandoff =
        route in DEBUG_MAIN_TAB_CHILD_ROUTES || route in TRANSPARENT_MAIN_TAB_DETAIL_ROUTES
    return when {
        !hasBackgroundHandoff -> MainTabBackgroundMotion.NONE
        coherentFeedbackEnabled -> MainTabBackgroundMotion.COHERENT_EXIT
        else -> MainTabBackgroundMotion.DRAWER_SINK
    }
}

internal fun resolveMainTabBackgroundTransform(
    motion: MainTabBackgroundMotion,
    progress: Float
): MainTabBackgroundTransform {
    val normalizedProgress = progress.coerceIn(0f, 1f)
    return when (motion) {
        MainTabBackgroundMotion.NONE -> MainTabBackgroundTransform(
            translationYFraction = 0f,
            scale = 1f,
            alpha = 1f
        )
        MainTabBackgroundMotion.COHERENT_EXIT -> MainTabBackgroundTransform(
            translationYFraction = -normalizedProgress,
            scale = 1f,
            alpha = 1f
        )
        MainTabBackgroundMotion.DRAWER_SINK -> MainTabBackgroundTransform(
            translationYFraction = DRAWER_BACKGROUND_SINK_FRACTION * normalizedProgress,
            scale = 1f - (1f - DRAWER_RECESSED_CONTENT_SCALE) * normalizedProgress,
            alpha = 1f
        )
    }
}

internal fun resolveMainTabBackgroundMotionDurationMillis(
    targetProgress: Float,
    coherentFeedbackEnabled: Boolean,
    debugSceneVisible: Boolean
): Int {
    val opening = targetProgress > 0f
    return when {
        coherentFeedbackEnabled && debugSceneVisible ->
            if (opening) DEBUG_NAVIGATION_OPEN_DURATION_MS else DEBUG_NAVIGATION_CLOSE_DURATION_MS
        coherentFeedbackEnabled ->
            if (opening) MAIN_TAB_DETAIL_OPEN_DURATION_MS else MAIN_TAB_DETAIL_CLOSE_DURATION_MS
        else ->
            if (opening) DRAWER_DETAIL_OPEN_DURATION_MS else DRAWER_DETAIL_CLOSE_DURATION_MS
    }
}

internal fun mainTabDetailContentOffsetEasing(): Easing = FastOutSlowInEasing

internal data class MainTabNavigationMotionTarget(
    val backgroundMotion: MainTabBackgroundMotion,
    val targetProgress: Float,
    val debugSceneVisible: Boolean
)

internal data class MainTabNavigationMotionState(
    val backgroundMotion: MainTabBackgroundMotion,
    val backgroundTransform: MainTabBackgroundTransform,
    val tabLayerTransform: MainTabBackgroundTransform
)

internal fun resolveMainTabNavigationMotionTarget(
    currentRoute: String?,
    visibleRoutes: Set<String?>,
    coherentFeedbackEnabled: Boolean
): MainTabNavigationMotionTarget {
    val currentMotion = resolveMainTabBackgroundMotion(
        route = currentRoute,
        coherentFeedbackEnabled = coherentFeedbackEnabled
    )
    val backgroundMotion = if (currentMotion != MainTabBackgroundMotion.NONE) {
        currentMotion
    } else {
        visibleRoutes.firstNotNullOfOrNull { route ->
            resolveMainTabBackgroundMotion(route, coherentFeedbackEnabled)
                .takeUnless { it == MainTabBackgroundMotion.NONE }
        } ?: MainTabBackgroundMotion.NONE
    }
    return MainTabNavigationMotionTarget(
        backgroundMotion = backgroundMotion,
        targetProgress = if (currentMotion == MainTabBackgroundMotion.NONE) 0f else 1f,
        debugSceneVisible = visibleRoutes.any { it in DEBUG_MAIN_TAB_CHILD_ROUTES }
    )
}

internal fun resolveMainTabNavigationMotionState(
    backgroundMotion: MainTabBackgroundMotion,
    progress: Float
): MainTabNavigationMotionState {
    val backgroundTransform = resolveMainTabBackgroundTransform(backgroundMotion, progress)
    val tabLayerTransform = if (backgroundMotion == MainTabBackgroundMotion.COHERENT_EXIT) {
        backgroundTransform
    } else {
        MainTabBackgroundTransform(translationYFraction = 0f, scale = 1f, alpha = 1f)
    }
    return MainTabNavigationMotionState(
        backgroundMotion = backgroundMotion,
        backgroundTransform = backgroundTransform,
        tabLayerTransform = tabLayerTransform
    )
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.mainTabEnterTransition(
    coherentFeedbackEnabled: Boolean = true
): EnterTransition {
    val initialRoute = initialState.destination.route
    val targetRoute = targetState.destination.route
    val direction = resolveMainTabTransitionDirection(
        initialRoute = initialRoute,
        targetRoute = targetRoute
    )
    if (direction != null) {
        return EnterTransition.None
    }
    val debugDirection = resolveDebugNavigationTransitionDirection(
        initialRoute = initialRoute,
        targetRoute = targetRoute
    )
    if (debugDirection != null) {
        return if (coherentFeedbackEnabled) {
            debugNavigationEnterTransition(debugDirection)
        } else {
            fadeIn(
                initialAlpha = DRAWER_ROOT_RETAIN_ALPHA,
                animationSpec = tween(
                    durationMillis = if (debugDirection > 0) {
                        DRAWER_DETAIL_OPEN_DURATION_MS
                    } else {
                        DRAWER_DETAIL_CLOSE_DURATION_MS
                    },
                    easing = mainTabDetailContentOffsetEasing()
                )
            )
        }
    }
    return if (
        resolveMainTabDetailHandoff(initialRoute, targetRoute) ==
        MainTabDetailHandoff.RETURN_TO_TAB && coherentFeedbackEnabled
    ) {
        slideInVertically(
            animationSpec = tween(
                durationMillis = MAIN_TAB_DETAIL_CLOSE_DURATION_MS,
                easing = mainTabDetailContentOffsetEasing()
            )
        ) { fullHeight -> -fullHeight }
    } else {
        EnterTransition.None
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.mainTabExitTransition(
    coherentFeedbackEnabled: Boolean = true
): ExitTransition {
    val initialRoute = initialState.destination.route
    val targetRoute = targetState.destination.route
    val direction = resolveMainTabTransitionDirection(
        initialRoute = initialRoute,
        targetRoute = targetRoute
    )
    if (direction != null) {
        return ExitTransition.None
    }
    val debugDirection = resolveDebugNavigationTransitionDirection(
        initialRoute = initialRoute,
        targetRoute = targetRoute
    )
    if (debugDirection != null) {
        return if (coherentFeedbackEnabled) {
            debugNavigationExitTransition(debugDirection)
        } else {
            ExitTransition.KeepUntilTransitionsFinished
        }
    }
    return if (
        resolveMainTabDetailHandoff(initialRoute, targetRoute) ==
        MainTabDetailHandoff.OPEN_DETAIL && coherentFeedbackEnabled
    ) {
        slideOutVertically(
            animationSpec = tween(
                durationMillis = MAIN_TAB_DETAIL_OPEN_DURATION_MS,
                easing = mainTabDetailContentOffsetEasing()
            )
        ) { fullHeight -> -fullHeight }
    } else {
        ExitTransition.None
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.transparentDetailEnterTransition(
    coherentFeedbackEnabled: Boolean = true
): EnterTransition {
    if (
        shouldUseInstantBiliUploaderPlaylistTransition(
            initialRoute = initialState.destination.route,
            targetRoute = targetState.destination.route
        )
    ) {
        return EnterTransition.None
    }
    val durationMillis = if (coherentFeedbackEnabled) {
        MAIN_TAB_DETAIL_OPEN_DURATION_MS
    } else {
        DRAWER_DETAIL_OPEN_DURATION_MS
    }
    return if (coherentFeedbackEnabled) {
        slideInVertically(
            animationSpec = tween(
                durationMillis = durationMillis,
                easing = mainTabDetailContentOffsetEasing()
            )
        ) { fullHeight -> fullHeight }
    } else {
        fadeIn(
            initialAlpha = DRAWER_ROOT_RETAIN_ALPHA,
            animationSpec = tween(
                durationMillis = durationMillis,
                easing = mainTabDetailContentOffsetEasing()
            )
        )
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.transparentDetailExitTransition(
    coherentFeedbackEnabled: Boolean = true
): ExitTransition {
    if (
        shouldUseInstantBiliUploaderPlaylistTransition(
            initialRoute = initialState.destination.route,
            targetRoute = targetState.destination.route
        )
    ) {
        return ExitTransition.None
    }
    val handoff = resolveMainTabDetailHandoff(
        initialRoute = initialState.destination.route,
        targetRoute = targetState.destination.route
    )
    return if (!coherentFeedbackEnabled) {
        ExitTransition.KeepUntilTransitionsFinished
    } else if (handoff == MainTabDetailHandoff.RETURN_TO_TAB) {
        slideOutVertically(
            animationSpec = tween(
                durationMillis = MAIN_TAB_DETAIL_CLOSE_DURATION_MS,
                easing = mainTabDetailContentOffsetEasing()
            )
        ) { fullHeight -> fullHeight }
    } else {
        slideOutVertically(
            animationSpec = tween(
                durationMillis = MAIN_TAB_DETAIL_OPEN_DURATION_MS,
                easing = mainTabDetailContentOffsetEasing()
            )
        ) { fullHeight -> -fullHeight }
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.transparentDetailPopEnterTransition(
    coherentFeedbackEnabled: Boolean = true
): EnterTransition {
    if (
        shouldUseInstantBiliUploaderPlaylistTransition(
            initialRoute = initialState.destination.route,
            targetRoute = targetState.destination.route
        )
    ) {
        return EnterTransition.None
    }
    return if (coherentFeedbackEnabled) {
        slideInVertically(
            animationSpec = tween(
                durationMillis = MAIN_TAB_DETAIL_CLOSE_DURATION_MS,
                easing = mainTabDetailContentOffsetEasing()
            )
        ) { fullHeight -> -fullHeight }
    } else {
        fadeIn(
            initialAlpha = DRAWER_ROOT_RETAIN_ALPHA,
            animationSpec = tween(
                durationMillis = DRAWER_DETAIL_CLOSE_DURATION_MS,
                easing = mainTabDetailContentOffsetEasing()
            )
        )
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.transparentDetailPopExitTransition(
    coherentFeedbackEnabled: Boolean = true
): ExitTransition {
    if (
        shouldUseInstantBiliUploaderPlaylistTransition(
            initialRoute = initialState.destination.route,
            targetRoute = targetState.destination.route
        )
    ) {
        return ExitTransition.None
    }
    return if (coherentFeedbackEnabled) {
        slideOutVertically(
            animationSpec = tween(
                durationMillis = MAIN_TAB_DETAIL_CLOSE_DURATION_MS,
                easing = mainTabDetailContentOffsetEasing()
            )
        ) { fullHeight -> fullHeight }
    } else {
        ExitTransition.KeepUntilTransitionsFinished
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.debugNavigationEnterTransition(
    coherentFeedbackEnabled: Boolean = true
): EnterTransition {
    val direction = resolveDebugNavigationTransitionDirection(
        initialRoute = initialState.destination.route,
        targetRoute = targetState.destination.route
    ) ?: return EnterTransition.None
    return if (coherentFeedbackEnabled) {
        debugNavigationEnterTransition(direction)
    } else {
        fadeIn(
            initialAlpha = DRAWER_ROOT_RETAIN_ALPHA,
            animationSpec = tween(
                durationMillis = if (direction > 0) {
                    DRAWER_DETAIL_OPEN_DURATION_MS
                } else {
                    DRAWER_DETAIL_CLOSE_DURATION_MS
                },
                easing = mainTabDetailContentOffsetEasing()
            )
        )
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.debugNavigationExitTransition(
    coherentFeedbackEnabled: Boolean = true
): ExitTransition {
    val direction = resolveDebugNavigationTransitionDirection(
        initialRoute = initialState.destination.route,
        targetRoute = targetState.destination.route
    ) ?: return ExitTransition.None
    return if (coherentFeedbackEnabled) {
        debugNavigationExitTransition(direction)
    } else {
        ExitTransition.KeepUntilTransitionsFinished
    }
}

private fun debugNavigationEnterTransition(direction: Int): EnterTransition {
    return slideInVertically(
        animationSpec = tween(debugNavigationDurationMs(direction))
    ) { fullHeight -> direction * fullHeight }
}

private fun debugNavigationExitTransition(direction: Int): ExitTransition {
    return slideOutVertically(
        animationSpec = tween(debugNavigationDurationMs(direction))
    ) { fullHeight -> -direction * fullHeight }
}

private fun debugNavigationDurationMs(direction: Int): Int {
    return if (direction > 0) {
        DEBUG_NAVIGATION_OPEN_DURATION_MS
    } else {
        DEBUG_NAVIGATION_CLOSE_DURATION_MS
    }
}

internal fun resolveMainStartDestination(
    preferredRoute: String?,
    showHomeTab: Boolean,
    devModeEnabled: Boolean
): String {
    val fallback = if (showHomeTab) Destinations.Home.route else Destinations.Explore.route
    return when (preferredRoute) {
        Destinations.Explore.route,
        Destinations.Library.route,
        Destinations.Settings.route -> preferredRoute
        Destinations.Home.route -> if (showHomeTab) Destinations.Home.route else fallback
        Destinations.Debug.route -> if (devModeEnabled) Destinations.Debug.route else fallback
        else -> fallback
    }
}

internal fun shouldApplyPersistedStartupDestination(
    awaitingPersistedRoute: Boolean,
    currentRoute: String?,
    initialFallbackRoute: String,
    resolvedPersistedRoute: String?
): Boolean {
    return awaitingPersistedRoute &&
        currentRoute == initialFallbackRoute &&
        resolvedPersistedRoute != null &&
        resolvedPersistedRoute != currentRoute
}
