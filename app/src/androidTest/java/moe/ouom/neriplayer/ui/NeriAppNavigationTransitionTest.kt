package moe.ouom.neriplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriAppNavigationTransitionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun recentScenesNeverOverlapDuringForwardAndBackTransitions() {
        assertTransparentDetailHandoff(Destinations.Recent.route)
    }

    @Test
    fun playbackStatsScenesNeverOverlapDuringForwardAndBackTransitions() {
        assertTransparentDetailHandoff(Destinations.PlaybackStats.route)
    }

    @Test
    fun neteaseAlbumScenesNeverOverlapDuringForwardAndBackTransitions() {
        assertTransparentDetailHandoff(
            detailRoute = Destinations.NeteaseAlbumDetail.route,
            navigationRoute = "netease_album_detail/test"
        )
    }

    private fun assertTransparentDetailHandoff(
        detailRoute: String,
        navigationRoute: String = detailRoute
    ) {
        lateinit var navController: NavHostController
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            navController = rememberNavController()
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(240.dp, 320.dp)
                        .background(Color.White)
                        .testTag(RootTag)
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Destinations.Library.route,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(
                            route = Destinations.Library.route,
                            enterTransition = { mainTabEnterTransition() },
                            exitTransition = { mainTabExitTransition() },
                            popEnterTransition = { mainTabEnterTransition() }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag(LibrarySceneTag)
                            )
                        }
                        composable(
                            route = detailRoute,
                            enterTransition = { transparentDetailEnterTransition() },
                            exitTransition = { transparentDetailExitTransition() },
                            popExitTransition = { transparentDetailPopExitTransition() }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag(DetailSceneTag)
                            )
                        }
                    }
                }
            }
        }

        composeRule.runOnIdle {
            navController.navigate(navigationRoute)
        }

        assertNoSceneOverlapAcrossFrames(
            upperSceneTag = LibrarySceneTag,
            lowerSceneTag = DetailSceneTag,
            durationMs = MAIN_TAB_DETAIL_OPEN_DURATION_MS
        )
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle {
            navController.popBackStack()
        }
        assertNoSceneOverlapAcrossFrames(
            upperSceneTag = LibrarySceneTag,
            lowerSceneTag = DetailSceneTag,
            durationMs = MAIN_TAB_DETAIL_CLOSE_DURATION_MS
        )
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    private fun assertNoSceneOverlapAcrossFrames(
        upperSceneTag: String,
        lowerSceneTag: String,
        durationMs: Int
    ) {
        var framesWithBothScenes = 0
        repeat((durationMs / FRAME_MS) + 2) { frame ->
            composeRule.mainClock.advanceTimeBy(FRAME_MS.toLong())
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
                    "Transparent scenes overlap at frame $frame: " +
                        "upper=$upperBounds lower=$lowerBounds",
                    upperBounds.bottom <= lowerBounds.top + POSITION_TOLERANCE_PX
                )
            }
        }
        assertTrue(
            "No frame contained both scenes for comparison",
            framesWithBothScenes > 0
        )
    }

    private companion object {
        const val RootTag = "navigation_transition_root"
        const val LibrarySceneTag = "library_scene"
        const val DetailSceneTag = "detail_scene"
        const val FRAME_MS = 16
        const val POSITION_TOLERANCE_PX = 1f
    }
}
