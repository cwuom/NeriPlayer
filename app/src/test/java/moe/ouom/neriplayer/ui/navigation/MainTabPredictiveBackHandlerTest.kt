package moe.ouom.neriplayer.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainTabPredictiveBackHandlerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var transitionState: MainTabLayerTransitionState
    private var selectedRoute by mutableStateOf(HOME)
    private var nestedDepth by mutableIntStateOf(0)
    private val listStates = mutableMapOf<String, LazyListState>()
    private val committedRoutes = mutableListOf<String>()
    private val nestedBackRoutes = mutableListOf<String?>()
    private var composedHandlerRoutes: Pair<String, String?> = HOME to HOME
    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun setHost() {
        composeRule.setContent {
            navController = rememberNavController()
            val currentEntry by navController.currentBackStackEntryAsState()
            val composedSelectedRoute = selectedRoute
            val composedCurrentRoute = currentEntry?.destination?.route
            SideEffect { composedHandlerRoutes = composedSelectedRoute to composedCurrentRoute }
            transitionState = rememberMainTabLayerTransitionState(HOME)
            Box(Modifier.size(240.dp, 320.dp)) {
                NavHost(navController, HOME) {
                    listOf(HOME, EXPLORE, SETTINGS).forEach { route ->
                        composable(
                            route,
                            enterTransition = { mainTabEnterTransition() },
                            exitTransition = { mainTabExitTransition() },
                            popEnterTransition = { mainTabEnterTransition() },
                            popExitTransition = { mainTabExitTransition() }
                        ) {}
                    }
                    composable(DETAIL) { Text("detail") }
                }
                MainTabPredictiveBackHandler(
                    navController = navController,
                    transitionState = transitionState,
                    selectedRoute = composedSelectedRoute,
                    enabled = true,
                    onBackCommitted = { route ->
                        committedRoutes += route
                        selectedRoute = route
                    }
                )
                MainTabLayerHost(
                    selectedRoute,
                    transitionState,
                    Modifier.fillMaxSize(),
                    backHandlingEnabled = currentEntry?.destination?.route == selectedRoute
                ) { route ->
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (route == HOME) 7 else 5)
                    listStates[route] = listState
                    BackHandler(enabled = route == selectedRoute && nestedDepth > 0) {
                        nestedBackRoutes += navController.currentDestination?.route
                        nestedDepth--
                    }
                    LazyColumn(state = listState) {
                        items(40, key = { it }) { index ->
                            Text("$route $index", modifier = Modifier.size(240.dp, 40.dp))
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { navigateTo(SETTINGS) }
        composeRule.waitForIdle()
    }

    private fun navigateTo(route: String) {
        selectedRoute = route
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    private fun event(progress: Float) =
        BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    private fun startGesture(progress: Float) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(event(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(event(progress)) }
        composeRule.waitForIdle()
    }

    @Test
    fun `gesture previews the actual previous tab while selection and history stay unchanged`() {
        setHost()

        startGesture(0.4f)

        composeRule.runOnIdle {
            assertEquals(SETTINGS, selectedRoute)
            assertEquals(SETTINGS, navController.currentDestination?.route)
            val scenes = transitionState.visibleScenes
            assertEquals(listOf(SETTINGS, HOME), scenes.map(MainTabLayerScene::route))
            assertEquals(0.4f, transitionState.offsetFractionFor(scenes.first()), 0.001f)
            assertEquals(-0.6f, transitionState.offsetFractionFor(scenes.last()), 0.001f)
            assertEquals(7, listStates.getValue(HOME).firstVisibleItemIndex)
            assertEquals(5, listStates.getValue(SETTINGS).firstVisibleItemIndex)
            assertTrue(committedRoutes.isEmpty())
        }
    }

    @Test
    fun `cancelled gesture keeps the original tab and both tabs scroll state`() {
        setHost()
        startGesture(0.6f)
        composeRule.runOnIdle {
            assertEquals(2, transitionState.visibleScenes.size)
            dispatcher.dispatchOnBackCancelled()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(SETTINGS, selectedRoute)
            assertEquals(SETTINGS, navController.currentDestination?.route)
            assertEquals(SETTINGS, transitionState.visibleScenes.single().route)
            assertEquals(5, listStates.getValue(SETTINGS).firstVisibleItemIndex)
            assertTrue(committedRoutes.isEmpty())
            navigateTo(HOME)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(7, listStates.getValue(HOME).firstVisibleItemIndex) }
    }

    @Test
    fun `released gesture commits the same previewed history entry and restores its scroll state`() {
        setHost()
        startGesture(0.35f)
        composeRule.runOnIdle {
            assertEquals(2, transitionState.visibleScenes.size)
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(listOf(HOME), committedRoutes)
            assertEquals(HOME, selectedRoute)
            assertEquals(HOME, navController.currentDestination?.route)
            assertEquals(HOME, transitionState.visibleScenes.single().route)
            assertEquals(7, listStates.getValue(HOME).firstVisibleItemIndex)
        }
    }

    @Test
    fun `plain back press returns through the existing tab history`() {
        setHost()

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(listOf(HOME), committedRoutes)
            assertEquals(HOME, selectedRoute)
            assertEquals(HOME, navController.currentDestination?.route)
            assertEquals(HOME, transitionState.visibleScenes.single().route)
        }
    }

    @Test
    fun `nested tab content handles back before the cross tab handler`() {
        setHost()
        composeRule.runOnIdle { nestedDepth = 1 }
        composeRule.waitForIdle()

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(0, nestedDepth)
            assertEquals(listOf(SETTINGS), nestedBackRoutes)
            assertEquals(SETTINGS, selectedRoute)
            assertEquals(SETTINGS, navController.currentDestination?.route)
            assertTrue(committedRoutes.isEmpty())
        }
    }

    @Test
    fun `an external detail owns back while the retained tab keeps its nested state`() {
        setHost()
        composeRule.runOnIdle {
            nestedDepth = 1
            navController.navigate(DETAIL)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(1, nestedDepth)
            assertEquals(DETAIL, navController.currentDestination?.route)
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue("Retained tab handled back on $nestedBackRoutes", nestedBackRoutes.isEmpty())
            assertEquals(1, nestedDepth)
            assertEquals(SETTINGS, navController.currentDestination?.route)
            assertEquals(SETTINGS, selectedRoute)
            assertTrue(committedRoutes.isEmpty())
        }
    }

    @Test
    fun `cancelling history preview during a rapid tab switch keeps selection and scene aligned`() {
        setHost()
        composeRule.runOnIdle { navigateTo(HOME) }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { navigateTo(EXPLORE) }
        composeRule.mainClock.advanceTimeBy(64L)
        composeRule.waitForIdle()
        composeRule.runOnIdle { navigateTo(SETTINGS) }
        composeRule.waitForIdle()
        repeat(2) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals(SETTINGS to SETTINGS, composedHandlerRoutes)
            assertEquals(listOf(HOME, EXPLORE), transitionState.visibleScenes.map(MainTabLayerScene::route))
            assertEquals(SETTINGS, selectedRoute)
            assertEquals(HOME, navController.previousBackStackEntry?.destination?.route)
        }

        startGesture(0.4f)
        composeRule.runOnIdle {
            assertEquals(listOf(EXPLORE, HOME), transitionState.visibleScenes.map(MainTabLayerScene::route))
            dispatcher.dispatchOnBackCancelled()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(SETTINGS, selectedRoute)
            assertEquals(SETTINGS, navController.currentDestination?.route)
            assertEquals(SETTINGS, transitionState.visibleScenes.single().route)
            assertTrue(committedRoutes.isEmpty())
        }
    }

    @Test
    fun `history changed during a gesture cannot pop or overwrite the newly selected tab`() {
        setHost()
        startGesture(0.4f)
        composeRule.runOnIdle { navigateTo(EXPLORE) }
        composeRule.waitForIdle()

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(EXPLORE, selectedRoute)
            assertEquals(EXPLORE, navController.currentDestination?.route)
            assertEquals(EXPLORE, transitionState.visibleScenes.single().route)
            assertTrue(committedRoutes.isEmpty())
        }
    }

    private companion object {
        const val HOME = "home"
        const val EXPLORE = "explore"
        const val SETTINGS = "settings"
        const val DETAIL = "detail"
    }
}
