package moe.ouom.neriplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriAppNavigationIsolationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun everyDebugHierarchyTransitionKeepsScenesDisjoint() {
        lateinit var navController: NavHostController
        val firstLevelRoutes = listOf(
            Destinations.DebugListenTogether.route,
            Destinations.DebugUsbExclusive.route,
            Destinations.DebugYouTube.route,
            Destinations.DebugBili.route,
            Destinations.DebugNetease.route,
            Destinations.DebugSearch.route,
            Destinations.DebugLogsList.route,
            Destinations.DebugCrashLogsList.route
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            navController = rememberNavController()
            MaterialTheme {
                TestRoot {
                    NavHost(
                        navController = navController,
                        startDestination = Destinations.Debug.route,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(
                            route = Destinations.Debug.route,
                            enterTransition = { mainTabEnterTransition() },
                            exitTransition = { mainTabExitTransition() },
                            popEnterTransition = { mainTabEnterTransition() },
                            popExitTransition = { mainTabExitTransition() }
                        ) {
                            TestScene(DebugRootTag)
                        }
                        firstLevelRoutes.forEach { route ->
                            composable(
                                route = route,
                                enterTransition = { debugNavigationEnterTransition() },
                                exitTransition = { debugNavigationExitTransition() },
                                popEnterTransition = { debugNavigationEnterTransition() },
                                popExitTransition = { debugNavigationExitTransition() }
                            ) {
                                TestScene(route)
                            }
                        }
                        composable(
                            route = Destinations.DebugLogViewer.route,
                            arguments = listOf(
                                navArgument("filePath") { type = NavType.StringType }
                            ),
                            enterTransition = { debugNavigationEnterTransition() },
                            exitTransition = { debugNavigationExitTransition() },
                            popEnterTransition = { debugNavigationEnterTransition() },
                            popExitTransition = { debugNavigationExitTransition() }
                        ) {
                            TestScene(DebugLogViewerTag)
                        }
                    }
                }
            }
        }

        firstLevelRoutes.forEach { route ->
            composeRule.runOnIdle { navController.navigate(route) }
            assertNoVerticalOverlap(
                upperSceneTag = DebugRootTag,
                lowerSceneTag = route,
                durationMs = DEBUG_NAVIGATION_OPEN_DURATION_MS
            )
            settleTransition()

            if (route == Destinations.DebugLogsList.route ||
                route == Destinations.DebugCrashLogsList.route
            ) {
                composeRule.runOnIdle {
                    navController.navigate("debug_log_viewer/test")
                }
                assertNoVerticalOverlap(
                    upperSceneTag = route,
                    lowerSceneTag = DebugLogViewerTag,
                    durationMs = DEBUG_NAVIGATION_OPEN_DURATION_MS
                )
                settleTransition()
                composeRule.runOnIdle { navController.popBackStack() }
                assertNoVerticalOverlap(
                    upperSceneTag = route,
                    lowerSceneTag = DebugLogViewerTag,
                    durationMs = DEBUG_NAVIGATION_CLOSE_DURATION_MS
                )
                settleTransition()
            }

            composeRule.runOnIdle { navController.popBackStack() }
            assertNoVerticalOverlap(
                upperSceneTag = DebugRootTag,
                lowerSceneTag = route,
                durationMs = DEBUG_NAVIGATION_CLOSE_DURATION_MS
            )
            settleTransition()
        }
    }

    private fun assertNoVerticalOverlap(
        upperSceneTag: String,
        lowerSceneTag: String,
        durationMs: Int
    ) {
        var framesWithBothScenes = 0
        repeat((durationMs / FrameMs) + 2) { frame ->
            composeRule.mainClock.advanceTimeBy(FrameMs.toLong())
            composeRule.waitForIdle()
            val upperNodes = composeRule
                .onAllNodesWithTag(upperSceneTag)
                .fetchSemanticsNodes()
            val lowerNodes = composeRule
                .onAllNodesWithTag(lowerSceneTag)
                .fetchSemanticsNodes()
            assertTrue(
                "Multiple upper scene nodes at frame $frame: ${upperNodes.size}",
                upperNodes.size <= 1
            )
            assertTrue(
                "Multiple lower scene nodes at frame $frame: ${lowerNodes.size}",
                lowerNodes.size <= 1
            )
            val upperBounds = upperNodes.singleOrNull()?.boundsInRoot
            val lowerBounds = lowerNodes.singleOrNull()?.boundsInRoot
            if (upperBounds != null && lowerBounds != null) {
                framesWithBothScenes++
                assertTrue(
                    "Scenes overlap at frame $frame: upper=$upperBounds lower=$lowerBounds",
                    upperBounds.bottom <= lowerBounds.top + PositionTolerancePx
                )
            }
        }
        assertTrue(
            "No frame contained both scenes for comparison",
            framesWithBothScenes > 0
        )
    }

    private fun settleTransition() {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private companion object {
        const val DebugRootTag = "debug_root"
        const val DebugLogViewerTag = "debug_log_viewer"
        const val FrameMs = 16
        const val PositionTolerancePx = 1f
    }
}

@Composable
private fun TestRoot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(240.dp, 320.dp)
            .background(Color.White)
            .testTag("navigation_isolation_root")
    ) {
        content()
    }
}

@Composable
private fun TestScene(tag: String) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(tag)
    )
}
