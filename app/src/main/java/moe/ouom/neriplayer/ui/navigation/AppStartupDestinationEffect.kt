package moe.ouom.neriplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import moe.ouom.neriplayer.navigation.Destinations

@Composable
internal fun AppStartupDestinationEffect(
    navController: NavHostController,
    currentRoute: String?,
    showHomeTab: Boolean,
    effectiveStartDestination: String,
    defaultStartDestination: String?,
    navHostStartDestination: String
) {
    var awaitingPersistedStartDestination by rememberSaveable {
        mutableStateOf(defaultStartDestination == null)
    }
    val input = AppStartupDestinationInput(
        currentRoute = currentRoute,
        showHomeTab = showHomeTab,
        effectiveStartDestination = effectiveStartDestination,
        defaultStartDestination = defaultStartDestination,
        navHostStartDestination = navHostStartDestination,
        awaitingPersistedStartDestination = awaitingPersistedStartDestination
    )
    AppStartupDestinationNavigationEffect(
        AppStartupDestinationNavigationOwner(navController, input) {
            awaitingPersistedStartDestination = false
        }
    )
}

internal data class AppStartupDestinationInput(
    val currentRoute: String?,
    val showHomeTab: Boolean,
    val effectiveStartDestination: String,
    val defaultStartDestination: String?,
    val navHostStartDestination: String,
    val awaitingPersistedStartDestination: Boolean
)

internal data class AppStartupDestinationDecision(
    val route: String?,
    val replaceFallbackRoute: Boolean,
    val clearAwaitingPersistedRoute: Boolean
)

internal fun appStartupDestinationDecision(input: AppStartupDestinationInput): AppStartupDestinationDecision {
    val resolvedPersistedRoute = input.effectiveStartDestination.takeIf {
        input.defaultStartDestination != null
    }
    val shouldApplyPersistedRoute = shouldApplyPersistedStartupDestination(
        awaitingPersistedRoute = input.awaitingPersistedStartDestination,
        currentRoute = input.currentRoute,
        initialFallbackRoute = input.navHostStartDestination,
        resolvedPersistedRoute = resolvedPersistedRoute
    )
    val route = when {
        shouldApplyPersistedRoute -> resolvedPersistedRoute
        !input.showHomeTab && input.currentRoute == Destinations.Home.route ->
            input.effectiveStartDestination
        else -> null
    }
    return AppStartupDestinationDecision(
        route = route,
        replaceFallbackRoute = shouldApplyPersistedRoute,
        clearAwaitingPersistedRoute =
            input.defaultStartDestination != null && input.currentRoute != null
    )
}

internal fun AppStartupDestinationDecision.dispatch(
    onPersistedRouteResolved: () -> Unit,
    navigate: (String, Boolean) -> Unit
) {
    if (clearAwaitingPersistedRoute) onPersistedRouteResolved()
    route?.let { navigate(it, replaceFallbackRoute) }
}

internal class AppStartupDestinationNavigationOwner(
    private val navController: NavHostController,
    private val input: AppStartupDestinationInput,
    private val onPersistedRouteResolved: () -> Unit
) {
    val effectIdentity: Pair<NavHostController, AppStartupDestinationInput>
        get() = navController to input

    fun navigateIfNeeded() {
        appStartupDestinationDecision(input).dispatch(onPersistedRouteResolved) { route, replaceFallback ->
            navController.navigate(route) {
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = replaceFallback
                    saveState = !replaceFallback
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
}

@Composable
private fun AppStartupDestinationNavigationEffect(owner: AppStartupDestinationNavigationOwner) {
    LaunchedEffect(owner.effectIdentity) { owner.navigateIfNeeded() }
}
