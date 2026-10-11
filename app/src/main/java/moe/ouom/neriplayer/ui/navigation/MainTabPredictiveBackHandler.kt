package moe.ouom.neriplayer.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.CancellationException

@Composable
internal fun MainTabPredictiveBackHandler(
    navController: NavHostController,
    transitionState: MainTabLayerTransitionState,
    selectedRoute: String,
    enabled: Boolean,
    onBackCommitted: (String) -> Unit
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val targetRoute = navController.previousBackStackEntry?.destination?.route
    val currentOnBackCommitted by rememberUpdatedState(onBackCommitted)
    val currentSelectedRoute by rememberUpdatedState(selectedRoute)
    val currentEnabled by rememberUpdatedState(enabled)
    PredictiveBackHandler(
        enabled = enabled && currentRoute == selectedRoute &&
            resolveMainTabTransitionDirection(currentRoute, targetRoute) != null
    ) { events ->
        val originEntry = navController.currentBackStackEntry
        val targetEntry = navController.previousBackStackEntry
        val backTarget = targetEntry?.destination?.route
        if (backTarget == null || resolveMainTabTransitionDirection(originEntry?.destination?.route, backTarget) == null) {
            events.collect { }
            return@PredictiveBackHandler
        }
        fun ownsHistory(): Boolean = currentEnabled &&
            currentSelectedRoute == originEntry?.destination?.route &&
            navController.currentBackStackEntry === originEntry &&
            navController.previousBackStackEntry === targetEntry

        val seeked = ownsHistory() && transitionState.beginPredictiveBack(backTarget)
        try {
            events.collect { event ->
                if (seeked && ownsHistory()) {
                    transitionState.seekPredictiveBack(event.progress)
                }
            }
            if (ownsHistory() && navController.popBackStack()) {
                if (seeked) {
                    transitionState.commitPredictiveBack()
                } else {
                    transitionState.request(backTarget)
                }
                currentOnBackCommitted(backTarget)
            } else if (seeked) {
                transitionState.cancelPredictiveBack()
            }
        } catch (cancelled: CancellationException) {
            if (seeked) transitionState.cancelPredictiveBack()
            throw cancelled
        }
    }
}
