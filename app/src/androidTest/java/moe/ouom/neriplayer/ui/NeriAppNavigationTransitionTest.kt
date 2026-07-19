package moe.ouom.neriplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

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

    @Test
    fun mainTabSwitchUsesPairedScenesWithoutOverlapAndFinishesWithinBudget() {
        lateinit var selectedRoute: MutableState<String>
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            Box(
                modifier = Modifier
                    .size(240.dp, 320.dp)
                    .background(Color.Black)
                    .testTag(MainTabRootTag)
            ) {
                MainTabLayerHost(
                    selectedRoute = selectedRoute.value,
                    modifier = Modifier.fillMaxSize()
                ) { route ->
                    MainTabTestScene(route)
                }
            }
        }

        composeRule.runOnIdle { selectedRoute.value = Destinations.Explore.route }
        var observedPairedScenes = false
        repeat((ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS / FRAME_MS) + 4) { frame ->
            composeRule.mainClock.advanceTimeBy(FRAME_MS.toLong())
            composeRule.waitForIdle()
            assertMainTabFrameCovered(frame, MainTabRootTag)
            val firstBounds = singleNodeBoundsOrNull(FirstMainTabSceneTag)
            val secondBounds = singleNodeBoundsOrNull(SecondMainTabSceneTag)
            if (firstBounds != null && secondBounds != null) {
                observedPairedScenes = true
                assertHorizontalScenesDoNotOverlap(frame, listOf(firstBounds, secondBounds))
            }
        }

        assertTrue("Main Tab switch no longer uses isolated paired scenes", observedPairedScenes)
        val finalPixels = composeRule.onNodeWithTag(MainTabRootTag)
            .captureToImage()
            .toPixelMap()
        assertTrue(
            "Main Tab transition did not finish within its time budget",
            countDominantPixels(finalPixels, dominantRed = false) >
                finalPixels.width * finalPixels.height / 2
        )
    }

    @Test
    fun rapidMainTabRetargetingContinuesFromCurrentMotionAndSettlesOnLatestTab() {
        lateinit var selectedRoute: MutableState<String>
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            Box(
                modifier = Modifier
                    .size(240.dp, 320.dp)
                    .background(Color.Black)
                    .testTag(RapidSwitchRootTag)
            ) {
                MainTabLayerHost(
                    selectedRoute = selectedRoute.value,
                    modifier = Modifier.fillMaxSize()
                ) { route ->
                    RapidMainTabTestScene(route)
                }
            }
        }

        var sampledFrame = 0
        fun advanceAndTrackFrame() {
            advanceRapidSwitchFrame()
            assertMainTabFrameCovered(sampledFrame, RapidSwitchRootTag)
            val bounds = listOf(RapidHomeTag, RapidExploreTag, RapidLibraryTag)
                .mapNotNull(::singleNodeBoundsOrNull)
            assertHorizontalScenesDoNotOverlap(sampledFrame, bounds)
            sampledFrame++
        }

        composeRule.runOnIdle { selectedRoute.value = Destinations.Explore.route }
        repeat(4) { advanceAndTrackFrame() }
        composeRule.runOnIdle { selectedRoute.value = Destinations.Library.route }
        repeat(3) { advanceAndTrackFrame() }
        composeRule.runOnIdle { selectedRoute.value = Destinations.Explore.route }
        repeat(2) { advanceAndTrackFrame() }
        composeRule.runOnIdle { selectedRoute.value = Destinations.Home.route }
        repeat((ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS / FRAME_MS) + 8) {
            advanceAndTrackFrame()
        }

        assertTrue(
            "Rapid retargeting did not settle on the latest tab",
            composeRule.onAllNodesWithTag(RapidHomeTag).fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithTag(RapidExploreTag).fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag(RapidLibraryTag).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun externalRouteChangeDuringMainTabTransitionStaysCoveredAndSettlesOnDetail() {
        lateinit var navController: NavHostController
        lateinit var selectedRoute: MutableState<String>
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            navController = rememberNavController()
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            Box(
                modifier = Modifier
                    .size(240.dp, 320.dp)
                    .background(Color.Black)
                    .testTag(ExternalRootTag)
            ) {
                MainTabLayerHost(
                    selectedRoute = selectedRoute.value,
                    modifier = Modifier.fillMaxSize()
                ) { route ->
                    ExternalMainTabTestScene(route)
                }
                NavHost(
                    navController = navController,
                    startDestination = Destinations.Home.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(
                        route = Destinations.Home.route,
                        enterTransition = { mainTabEnterTransition() },
                        exitTransition = { mainTabExitTransition() }
                    ) {}
                    composable(
                        route = Destinations.Explore.route,
                        enterTransition = { mainTabEnterTransition() },
                        exitTransition = { mainTabExitTransition() }
                    ) {}
                    composable(
                        route = Destinations.Recent.route,
                        enterTransition = { transparentDetailEnterTransition() },
                        exitTransition = { transparentDetailExitTransition() }
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(ThirdTabColor)
                                .testTag(ExternalDetailTag)
                        )
                    }
                }
            }
        }

        composeRule.runOnIdle {
            selectedRoute.value = Destinations.Explore.route
            navigateMainTab(navController, Destinations.Explore.route)
        }
        repeat(3) { frame ->
            advanceRapidSwitchFrame()
            assertMainTabFrameCovered(frame, ExternalRootTag)
            val bounds = listOf(ExternalHomeTag, ExternalExploreTag)
                .mapNotNull(::singleNodeBoundsOrNull)
            assertHorizontalScenesDoNotOverlap(frame, bounds)
        }
        composeRule.runOnIdle { navController.navigate(Destinations.Recent.route) }
        repeat((MAIN_TAB_DETAIL_OPEN_DURATION_MS / FRAME_MS) + 4) { frame ->
            advanceRapidSwitchFrame()
            assertMainTabFrameCovered(frame, ExternalRootTag)
        }

        assertCurrentRoute(navController, Destinations.Recent.route)
        assertTrue("External detail disappeared", nodeCount(ExternalDetailTag) == 1)
    }

    @Test
    fun transparentDetailMovesExternalMainTabLayerOutOfViewport() {
        lateinit var navController: NavHostController
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            navController = rememberNavController()
            val backEntry by navController.currentBackStackEntryAsState()
            var layerHeightPx by remember { mutableIntStateOf(0) }
            val offsetTarget = resolveMainTabDetailContentOffsetTarget(
                backEntry?.destination?.route
            )
            val offsetFraction by animateFloatAsState(
                targetValue = offsetTarget,
                animationSpec = tween(
                    durationMillis = resolveMainTabDetailContentOffsetDurationMillis(offsetTarget),
                    easing = mainTabDetailContentOffsetEasing()
                ),
                label = "test_main_tab_detail_handoff"
            )
            Box(
                modifier = Modifier
                    .size(240.dp, 320.dp)
                    .background(Color.Black)
                    .testTag(LayeredRootTag)
            ) {
                MainTabLayerHost(
                    selectedRoute = Destinations.Library.route,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size -> layerHeightPx = size.height }
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (offsetFraction * layerHeightPx).roundToInt()
                            )
                        }
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(FirstTabColor)
                            .testTag(LayeredLibrarySceneTag)
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = Destinations.Library.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(
                        route = Destinations.Library.route,
                        enterTransition = { mainTabEnterTransition() },
                        exitTransition = { mainTabExitTransition() }
                    ) {}
                    composable(
                        route = Destinations.PlaybackStats.route,
                        enterTransition = { transparentDetailEnterTransition() },
                        exitTransition = { transparentDetailExitTransition() }
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(SecondTabColor)
                                .testTag(LayeredDetailSceneTag)
                        )
                    }
                }
            }
        }

        composeRule.runOnIdle {
            navController.navigate(Destinations.PlaybackStats.route)
        }
        var observedSeparatedMotion = false
        repeat((MAIN_TAB_DETAIL_OPEN_DURATION_MS / FRAME_MS) + 4) { frame ->
            composeRule.mainClock.advanceTimeBy(FRAME_MS.toLong())
            composeRule.waitForIdle()
            assertMainTabFrameCovered(frame, LayeredRootTag)
            val libraryBounds = singleNodeBoundsOrNull(LayeredLibrarySceneTag)
            val detailBounds = singleNodeBoundsOrNull(LayeredDetailSceneTag)
            if (libraryBounds != null && detailBounds != null) {
                observedSeparatedMotion = true
                assertTrue(
                    "Main Tab layer overlapped transparent detail at frame $frame: " +
                        "main=$libraryBounds detail=$detailBounds",
                    libraryBounds.bottom <= detailBounds.top + POSITION_TOLERANCE_PX
                )
            }
        }

        assertTrue("No separated handoff frame was sampled", observedSeparatedMotion)
        val settledLibraryBounds = singleNodeBoundsOrNull(LayeredLibrarySceneTag)
        assertTrue(
            "Main Tab layer remained visible behind detail: $settledLibraryBounds",
            settledLibraryBounds == null ||
                settledLibraryBounds.bottom <= POSITION_TOLERANCE_PX
        )
    }

    @Test
    fun nestedDetailAndAlbumScenesNeverOverlapDuringForwardAndBackTransitions() {
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
                        startDestination = Destinations.Recent.route,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(
                            route = Destinations.Recent.route,
                            enterTransition = { transparentDetailEnterTransition() },
                            exitTransition = { transparentDetailExitTransition() },
                            popEnterTransition = { transparentDetailPopEnterTransition() },
                            popExitTransition = { transparentDetailPopExitTransition() }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag(PreviousDetailSceneTag)
                            )
                        }
                        composable(
                            route = Destinations.NeteaseAlbumDetail.route,
                            enterTransition = { transparentDetailEnterTransition() },
                            exitTransition = { transparentDetailExitTransition() },
                            popEnterTransition = { transparentDetailPopEnterTransition() },
                            popExitTransition = { transparentDetailPopExitTransition() }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag(NestedAlbumSceneTag)
                            )
                        }
                    }
                }
            }
        }

        composeRule.runOnIdle {
            navController.navigate("netease_album_detail/test")
        }
        assertNoSceneOverlapAcrossFrames(
            upperSceneTag = PreviousDetailSceneTag,
            lowerSceneTag = NestedAlbumSceneTag,
            durationMs = MAIN_TAB_DETAIL_OPEN_DURATION_MS
        )
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle {
            navController.popBackStack()
        }
        assertNoSceneOverlapAcrossFrames(
            upperSceneTag = PreviousDetailSceneTag,
            lowerSceneTag = NestedAlbumSceneTag,
            durationMs = MAIN_TAB_DETAIL_CLOSE_DURATION_MS
        )
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
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

    private fun assertMainTabFrameCovered(frame: Int, rootTag: String) {
        val pixels = composeRule.onNodeWithTag(rootTag).captureToImage().toPixelMap()
        val coveredPixels = countNonBlankPixels(pixels)
        assertTrue(
            "Main Tab frame exposed the blank background at frame $frame: " +
                "$coveredPixels/${pixels.width * pixels.height}",
            coveredPixels >= pixels.width * pixels.height * MIN_FRAME_COVERAGE_PERCENT / 100
        )
    }

    private fun singleNodeBoundsOrNull(tag: String): Rect? {
        val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        assertTrue("Multiple scene nodes found for $tag: ${nodes.size}", nodes.size <= 1)
        return nodes.singleOrNull()?.boundsInRoot?.takeIf { bounds ->
            bounds.width > 0f && bounds.height > 0f
        }
    }

    private fun assertHorizontalScenesDoNotOverlap(frame: Int, bounds: List<Rect>) {
        val orderedBounds = bounds.sortedBy(Rect::left)
        orderedBounds.zipWithNext().forEach { (left, right) ->
            assertTrue(
                "Isolated Main Tab layers overlap at frame $frame: left=$left right=$right",
                left.right <= right.left + POSITION_TOLERANCE_PX
            )
        }
    }

    @Composable
    private fun MainTabTestScene(route: String) {
        val (tag, color) = when (route) {
            Destinations.Home.route -> FirstMainTabSceneTag to FirstTabColor
            Destinations.Explore.route -> SecondMainTabSceneTag to SecondTabColor
            Destinations.Library.route -> RapidLibraryTag to ThirdTabColor
            else -> error("Unexpected test route $route")
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(color)
                .testTag(tag)
        )
    }

    @Composable
    private fun ExternalMainTabTestScene(route: String) {
        val (tag, color) = when (route) {
            Destinations.Home.route -> ExternalHomeTag to FirstTabColor
            Destinations.Explore.route -> ExternalExploreTag to SecondTabColor
            else -> error("Unexpected external test route $route")
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(color)
                .testTag(tag)
        )
    }

    @Composable
    private fun RapidMainTabTestScene(route: String) {
        val (tag, color) = when (route) {
            Destinations.Home.route -> RapidHomeTag to FirstTabColor
            Destinations.Explore.route -> RapidExploreTag to SecondTabColor
            Destinations.Library.route -> RapidLibraryTag to ThirdTabColor
            else -> error("Unexpected rapid test route $route")
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(color)
                .testTag(tag)
        )
    }

    private fun assertCurrentRoute(navController: NavHostController, expectedRoute: String) {
        val actualRoute = navController.currentBackStackEntry?.destination?.route
        assertTrue(
            "Expected current route $expectedRoute but was $actualRoute",
            actualRoute == expectedRoute
        )
    }

    private fun nodeCount(tag: String): Int =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun navigateMainTab(navController: NavHostController, route: String): String? {
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        val currentEntry = navController.currentBackStackEntry
        assertTrue(
            "Navigation did not synchronously expose target entry $route",
            currentEntry?.destination?.route == route
        )
        return currentEntry?.id
    }

    private fun advanceRapidSwitchFrame() {
        composeRule.mainClock.advanceTimeBy(FRAME_MS.toLong())
        composeRule.waitForIdle()
    }

    private fun countDominantPixels(
        pixels: androidx.compose.ui.graphics.PixelMap,
        dominantRed: Boolean
    ): Int {
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                val isDominant = if (dominantRed) {
                    color.red > 0.15f && color.red > color.blue * 2f
                } else {
                    color.blue > 0.15f && color.blue > color.red * 2f
                }
                if (isDominant) count++
            }
        }
        return count
    }

    private fun countNonBlankPixels(pixels: androidx.compose.ui.graphics.PixelMap): Int {
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.red > 0.12f || color.green > 0.12f || color.blue > 0.12f) {
                    count++
                }
            }
        }
        return count
    }

    private companion object {
        const val RootTag = "navigation_transition_root"
        const val MainTabRootTag = "main_tab_transition_root"
        const val RapidSwitchRootTag = "rapid_switch_root"
        const val FirstMainTabSceneTag = "first_main_tab_scene"
        const val SecondMainTabSceneTag = "second_main_tab_scene"
        const val RapidHomeTag = "rapid_home_scene"
        const val RapidExploreTag = "rapid_explore_scene"
        const val RapidLibraryTag = "rapid_library_scene"
        const val ExternalRootTag = "external_transition_root"
        const val ExternalHomeTag = "external_home_scene"
        const val ExternalExploreTag = "external_explore_scene"
        const val ExternalDetailTag = "external_detail_scene"
        const val LayeredRootTag = "layered_transition_root"
        const val LayeredLibrarySceneTag = "layered_library_scene"
        const val LayeredDetailSceneTag = "layered_detail_scene"
        const val LibrarySceneTag = "library_scene"
        const val DetailSceneTag = "detail_scene"
        const val PreviousDetailSceneTag = "previous_detail_scene"
        const val NestedAlbumSceneTag = "nested_album_scene"
        const val FRAME_MS = 16
        const val POSITION_TOLERANCE_PX = 1f
        const val MIN_FRAME_COVERAGE_PERCENT = 98
        val FirstTabColor = Color(0xFFFF0000)
        val SecondTabColor = Color(0xFF0000FF)
        val ThirdTabColor = Color(0xFF00FF00)
    }
}
