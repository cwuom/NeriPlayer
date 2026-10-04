package moe.ouom.neriplayer.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassSceneOpacity
import moe.ouom.neriplayer.ui.navigation.DRAWER_DETAIL_OPEN_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.MAIN_TAB_DETAIL_CLOSE_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.MAIN_TAB_DETAIL_OPEN_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.MainTabBackgroundMotion
import moe.ouom.neriplayer.ui.navigation.MainTabLayerHost
import moe.ouom.neriplayer.ui.navigation.MainTabLayerScenePhase
import moe.ouom.neriplayer.ui.navigation.MainTabLayerTransitionState
import moe.ouom.neriplayer.ui.navigation.animateMainTabDetailCloseRootRevealFraction
import moe.ouom.neriplayer.ui.navigation.clipMainTabDetailCloseRoot
import moe.ouom.neriplayer.ui.navigation.debugNavigationEnterTransition
import moe.ouom.neriplayer.ui.navigation.debugNavigationExitTransition
import moe.ouom.neriplayer.ui.navigation.mainTabDetailContentOffsetEasing
import moe.ouom.neriplayer.ui.navigation.mainTabEnterTransition
import moe.ouom.neriplayer.ui.navigation.mainTabExitTransition
import moe.ouom.neriplayer.ui.navigation.rememberMainTabDetailVisibilityState
import moe.ouom.neriplayer.ui.navigation.rememberMainTabLayerTransitionState
import moe.ouom.neriplayer.ui.navigation.rememberMainTabSceneRestoredEntry
import moe.ouom.neriplayer.ui.navigation.resolveMainTabBackgroundMotion
import moe.ouom.neriplayer.ui.navigation.resolveMainTabBackgroundMotionDurationMillis
import moe.ouom.neriplayer.ui.navigation.resolveMainTabBackgroundTransform
import moe.ouom.neriplayer.ui.navigation.shouldSuppressRestoredMainTabHostEntry
import moe.ouom.neriplayer.ui.navigation.transparentDetailEnterTransition
import moe.ouom.neriplayer.ui.navigation.transparentDetailExitTransition
import moe.ouom.neriplayer.ui.navigation.transparentDetailPopEnterTransition
import moe.ouom.neriplayer.ui.navigation.transparentDetailPopExitTransition
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.ceil

private val Boolean.testNavigationDepth: Int
    get() = if (this) 1 else 0

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
    fun phoneWideWindowKeepsDirectionalSlideWithoutScaleOrFade() {
        lateinit var selectedRoute: MutableState<String>
        lateinit var transitionState: MainTabLayerTransitionState
        val sceneOpacities = mutableMapOf<String, () -> Float>()
        composeRule.mainClock.autoAdvance = false
        setMainTabContent(smallestScreenWidthDp = 599, screenWidthDp = 840) {
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            transitionState = rememberMainTabLayerTransitionState(Destinations.Home.route)
            Box(
                Modifier.size(240.dp, 320.dp).background(Color.Black).testTag(MainTabRootTag)
            ) {
                val parentGlassOpacity = remember { { 0.8f } }
                CompositionLocalProvider(LocalAdvancedGlassSceneOpacity provides parentGlassOpacity) {
                    MainTabLayerHost(
                        selectedRoute = selectedRoute.value,
                        transitionState = transitionState,
                        modifier = Modifier.fillMaxSize()
                    ) { route ->
                        val sceneOpacity = LocalAdvancedGlassSceneOpacity.current
                        SideEffect { sceneOpacities[route] = sceneOpacity }
                        MainTabTestScene(route)
                    }
                }
            }
        }

        // 首个场景完成准备后再计切页预算，避免把宿主的启动帧算进动画时间
        repeat(4) { advanceRapidSwitchFrame() }
        composeRule.runOnIdle {
            val initial = transitionState.visibleScenes.singleOrNull()
            assertTrue("Phone Tab host did not prepare its initial scene: $initial",
                initial != null && initial.route == Destinations.Home.route &&
                    initial.phase == MainTabLayerScenePhase.Settled)
        }

        listOf(Destinations.Explore.route, Destinations.Home.route).forEach { target ->
            val requestTime = composeRule.mainClock.currentTime
            composeRule.runOnIdle { selectedRoute.value = target }
            var observedMovingScenes = false
            repeat((ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS / FRAME_MS) + 6) { frame ->
                advanceRapidSwitchFrame()
                val root = checkNotNull(singleNodeBoundsOrNull(MainTabRootTag))
                val home = singleNodeBoundsOrNull(FirstMainTabSceneTag)
                val explore = singleNodeBoundsOrNull(SecondMainTabSceneTag)
                if (home != null && explore != null) {
                    assertTrue("Phone Tab scenes overlapped at frame $frame: $home $explore",
                        home.right <= explore.left + POSITION_TOLERANCE_PX)
                    listOf(home, explore).forEach { bounds ->
                        assertTrue("Phone Tab unexpectedly scaled vertically at frame $frame: $bounds",
                            abs(bounds.top - root.top) <= POSITION_TOLERANCE_PX &&
                                abs(bounds.bottom - root.bottom) <= POSITION_TOLERANCE_PX)
                    }
                    assertTrue("Phone Tab slide exposed a gap at frame $frame: $home $explore",
                        abs(home.width + explore.width - root.width) <= POSITION_TOLERANCE_PX * 2)
                    observedMovingScenes = observedMovingScenes ||
                        (home.width > POSITION_TOLERANCE_PX && explore.width > POSITION_TOLERANCE_PX)
                }
                assertPhoneMainTabFrameOpaque(frame)
                composeRule.runOnIdle {
                    sceneOpacities.values.forEach { opacity ->
                        assertEquals("Phone Tab did not inherit its parent glass opacity", 0.8f, opacity(), 0f)
                    }
                }
            }
            assertTrue("Phone Tab never showed paired moving scenes for $target", observedMovingScenes)
            val settledTag = if (target == Destinations.Home.route) FirstMainTabSceneTag else SecondMainTabSceneTag
            val otherTag = if (target == Destinations.Home.route) SecondMainTabSceneTag else FirstMainTabSceneTag
            val root = checkNotNull(singleNodeBoundsOrNull(MainTabRootTag))
            assertEquals(root, singleNodeBoundsOrNull(settledTag))
            composeRule.runOnIdle {
                val scenes = transitionState.visibleScenes
                val diagnostics = scenes.joinToString { scene ->
                    "${scene.route}:${scene.phase}:token=${scene.transitionToken}:" +
                        "offset=${transitionState.offsetFractionFor(scene)}"
                }
                assertTrue("Phone Tab controller did not settle on $target after " +
                    "${composeRule.mainClock.currentTime - requestTime}ms: $diagnostics",
                    scenes.singleOrNull()?.let { scene ->
                        scene.route == target && scene.phase == MainTabLayerScenePhase.Settled
                    } == true)
            }
            assertTrue("Phone Tab retained its outgoing scene after settling on $target", nodeCount(otherTag) == 0)
        }
    }

    @Test
    fun phoneSlidingScenesKeepPreparationAndOutgoingActionsIsolated() {
        lateinit var transitionState: MainTabLayerTransitionState
        val clicks = mutableMapOf<String, Int>()
        composeRule.mainClock.autoAdvance = false
        setMainTabContent(smallestScreenWidthDp = 360, screenWidthDp = 840) {
            transitionState = rememberMainTabLayerTransitionState(Destinations.Home.route)
            MainTabLayerHost(
                selectedRoute = Destinations.Home.route,
                transitionState = transitionState,
                modifier = Modifier.size(240.dp, 320.dp).testTag(RapidSwitchRootTag)
            ) { route ->
                Box(Modifier.fillMaxSize().testTag("phone-tab-touch-$route").clickable {
                    clicks[route] = clicks.getOrDefault(route, 0) + 1
                })
            }
        }
        repeat(4) { advanceRapidSwitchFrame() }
        composeRule.runOnIdle { transitionState.request(Destinations.Explore.route) }
        advanceRapidSwitchFrame()
        composeRule.onNodeWithTag("phone-tab-touch-${Destinations.Home.route}").assertDoesNotExist()
        composeRule.onNodeWithTag("phone-tab-touch-${Destinations.Explore.route}").assertDoesNotExist()
        composeRule.onNodeWithTag(RapidSwitchRootTag).performTouchInput { click() }
        composeRule.runOnIdle { assertTrue("Phone preparation frame accepted a touch", clicks.isEmpty()) }

        repeat(8) { advanceRapidSwitchFrame() }
        val outgoingTag = "phone-tab-touch-${Destinations.Home.route}"
        val incomingTag = "phone-tab-touch-${Destinations.Explore.route}"
        composeRule.onNodeWithTag(outgoingTag).assertDoesNotExist()
        composeRule.onNodeWithTag(outgoingTag, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(incomingTag).assertHasClickAction()
        val root = checkNotNull(singleNodeBoundsOrNull(RapidSwitchRootTag))
        val outgoing = checkNotNull(singleNodeBoundsOrNull(outgoingTag))
        val incoming = checkNotNull(singleNodeBoundsOrNull(incomingTag))
        composeRule.onNodeWithTag(RapidSwitchRootTag).performTouchInput {
            click(Offset(outgoing.center.x - root.left, outgoing.center.y - root.top))
        }
        composeRule.runOnIdle { assertTrue("Phone outgoing Tab accepted a touch", clicks.isEmpty()) }
        composeRule.onNodeWithTag(RapidSwitchRootTag).performTouchInput {
            click(Offset(incoming.center.x - root.left, incoming.center.y - root.top))
        }
        composeRule.runOnIdle {
            assertTrue("Phone prepared incoming Tab did not accept a touch",
                clicks[Destinations.Explore.route] == 1 && clicks[Destinations.Home.route] == null)
        }
    }

    @Test
    fun mainTabSwitchScalesAndFadesInPlaceAndFinishesWithinBudget() {
        lateinit var selectedRoute: MutableState<String>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
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
        var observedScale = false
        var observedIncomingScale = false
        var observedFade = false
        repeat((ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS / FRAME_MS) + 4) { frame ->
            composeRule.mainClock.advanceTimeBy(FRAME_MS.toLong())
            composeRule.waitForIdle()
            assertMainTabFrameCovered(frame, MainTabRootTag)
            val firstBounds = singleNodeBoundsOrNull(FirstMainTabSceneTag)
            val secondBounds = singleNodeBoundsOrNull(SecondMainTabSceneTag)
            if (firstBounds != null && secondBounds != null) {
                observedPairedScenes = true
                assertMainTabScenesStayCentered(frame, listOf(firstBounds, secondBounds), MainTabRootTag)
                val rootBounds = checkNotNull(singleNodeBoundsOrNull(MainTabRootTag))
                observedScale = observedScale ||
                    firstBounds.width < rootBounds.width - POSITION_TOLERANCE_PX
                observedIncomingScale = observedIncomingScale ||
                    secondBounds.width < rootBounds.width - POSITION_TOLERANCE_PX
                val pixels = composeRule.onNodeWithTag(MainTabRootTag).captureToImage().toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2]
                observedFade = observedFade || (center.red > 0.15f && center.blue > 0.15f)
            }
        }

        assertTrue("Main Tab switch no longer uses isolated paired scenes", observedPairedScenes)
        assertTrue("Main Tab switch never scaled the outgoing scene", observedScale)
        assertTrue("Main Tab switch never scaled the incoming scene", observedIncomingScale)
        assertTrue("Main Tab switch never blended scene opacity", observedFade)
        val finalRootBounds = checkNotNull(singleNodeBoundsOrNull(MainTabRootTag))
        val finalSceneBounds = checkNotNull(singleNodeBoundsOrNull(SecondMainTabSceneTag))
        assertTrue("Settled Main Tab did not restore its full width",
            abs(finalSceneBounds.width - finalRootBounds.width) <= POSITION_TOLERANCE_PX)
        assertTrue("Settled Main Tab did not restore its full height",
            abs(finalSceneBounds.height - finalRootBounds.height) <= POSITION_TOLERANCE_PX)
        assertMainTabScenesStayCentered(-1, listOf(finalSceneBounds), MainTabRootTag)
        val finalPixels = composeRule.onNodeWithTag(MainTabRootTag)
            .captureToImage()
            .toPixelMap()
        val finalCenter = finalPixels[finalPixels.width / 2, finalPixels.height / 2]
        assertTrue("Settled Main Tab retained outgoing opacity",
            finalCenter.blue >= 0.98f && finalCenter.red <= 0.02f && finalCenter.green <= 0.02f)
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
        setMainTabContent {
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
            assertTrue(
                "Rapid Tab retargeting removed every content scene at frame $sampledFrame",
                bounds.isNotEmpty()
            )
            assertMainTabScenesStayCentered(sampledFrame, bounds, RapidSwitchRootTag)
            sampledFrame++
        }

        composeRule.runOnIdle { selectedRoute.value = Destinations.Explore.route }
        repeat(4) { advanceAndTrackFrame() }
        composeRule.runOnIdle { selectedRoute.value = Destinations.Library.route }
        repeat(3) { advanceAndTrackFrame() }
        composeRule.runOnIdle { selectedRoute.value = Destinations.Explore.route }
        repeat(2) { advanceAndTrackFrame() }
        composeRule.runOnIdle { selectedRoute.value = Destinations.Home.route }
        repeat((ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS * 2 / FRAME_MS) + 12) {
            advanceAndTrackFrame()
        }

        assertTrue(
            "Rapid retargeting did not settle on the latest tab",
            composeRule.onAllNodesWithTag(RapidHomeTag, useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithTag(RapidExploreTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag(RapidLibraryTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun rapidMainTabRetargetingKeepsTheFullMotionDuration() {
        lateinit var selectedRoute: MutableState<String>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            MainTabLayerHost(
                selectedRoute = selectedRoute.value,
                modifier = Modifier.size(240.dp, 320.dp)
            ) { route ->
                RapidMainTabTestScene(route)
            }
        }

        composeRule.runOnIdle { selectedRoute.value = Destinations.Explore.route }
        repeat(18) { advanceRapidSwitchFrame() }
        composeRule.runOnIdle { selectedRoute.value = Destinations.Library.route }
        composeRule.mainClock.advanceTimeBy(
            (ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS / 2).toLong()
        )
        composeRule.waitForIdle()

        assertTrue(
            "Queued Tab request interrupted the current animation",
            nodeCount(RapidHomeTag) == 1 &&
                nodeCount(RapidExploreTag) == 1 &&
                nodeCount(RapidLibraryTag) == 0
        )

        composeRule.mainClock.advanceTimeBy(
            (ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS * 2 + FRAME_MS * 4).toLong()
        )
        composeRule.waitForIdle()
        assertTrue(
            "Retargeted Tab animation did not settle on the latest route",
            nodeCount(RapidLibraryTag) == 1 &&
                nodeCount(RapidHomeTag) == 0 &&
                nodeCount(RapidExploreTag) == 0
        )
    }

    @Test
    fun mainTabTransitionWaitsForFirstNonzeroContainerWidth() {
        lateinit var selectedRoute: MutableState<String>
        lateinit var hostWidth: MutableState<androidx.compose.ui.unit.Dp>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            hostWidth = remember { mutableStateOf(0.dp) }
            MainTabLayerHost(
                selectedRoute = selectedRoute.value,
                modifier = Modifier.size(hostWidth.value, 320.dp)
            ) { route ->
                RapidMainTabTestScene(route)
            }
        }

        composeRule.runOnIdle { selectedRoute.value = Destinations.Explore.route }
        composeRule.waitForIdle()
        assertTrue(
            "Tab transition advanced before its container had a width",
            nodeCount(RapidHomeTag) == 1 && nodeCount(RapidExploreTag) == 0
        )

        composeRule.runOnIdle { hostWidth.value = 240.dp }
        waitForNodeCount(RapidHomeTag, expected = 1)
        waitForNodeCount(RapidExploreTag, expected = 1)
    }

    @Test
    fun firstMainTabTransitionPreparesTheIncomingSceneBeforeScalingAndFading() {
        lateinit var transitionState: MainTabLayerTransitionState
        lateinit var incomingSceneWasComposed: MutableState<Boolean>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            transitionState = rememberMainTabLayerTransitionState(Destinations.Home.route)
            incomingSceneWasComposed = remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .size(240.dp, 320.dp)
                    .background(Color.Black)
                    .testTag(RapidSwitchRootTag)
            ) {
                MainTabLayerHost(
                    selectedRoute = Destinations.Home.route,
                    transitionState = transitionState,
                    modifier = Modifier.fillMaxSize()
                ) { route ->
                    if (route == Destinations.Explore.route) {
                        SideEffect { incomingSceneWasComposed.value = true }
                    }
                    RapidMainTabTestScene(route)
                }
            }
        }

        repeat(4) { advanceRapidSwitchFrame() }
        composeRule.runOnIdle {
            transitionState.request(Destinations.Explore.route)
        }
        repeat(2) { advanceRapidSwitchFrame() }

        val rootBounds = singleNodeBoundsOrNull(RapidSwitchRootTag)
        val homeBounds = singleNodeBoundsOrNull(RapidHomeTag)
        assertTrue(
            "Incoming Tab started shrinking before its preparation completed: " +
                "home=$homeBounds",
            rootBounds != null &&
                homeBounds != null &&
                incomingSceneWasComposed.value &&
                abs(homeBounds.width - rootBounds.width) <= POSITION_TOLERANCE_PX
        )

        var movingHomeBounds = singleNodeBoundsOrNull(RapidHomeTag)
        for (frame in 0 until 12) {
            if (rootBounds != null && movingHomeBounds != null &&
                movingHomeBounds.right < rootBounds.right - POSITION_TOLERANCE_PX
            ) break
            advanceRapidSwitchFrame()
            movingHomeBounds = singleNodeBoundsOrNull(RapidHomeTag)
        }
        assertTrue(
            "Incoming Tab did not start scaling after its preparation: " +
                "home=$movingHomeBounds",
            rootBounds != null &&
                movingHomeBounds != null &&
                movingHomeBounds.right < rootBounds.right - POSITION_TOLERANCE_PX
        )
    }

    @Test
    fun queuedMainTabRequestKeepsTheCurrentMotionRunning() {
        lateinit var transitionState: MainTabLayerTransitionState
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            transitionState = rememberMainTabLayerTransitionState(Destinations.Home.route)
            Box(
                modifier = Modifier
                    .size(240.dp, 320.dp)
                    .background(Color.Black)
                    .testTag(RapidSwitchRootTag)
            ) {
                MainTabLayerHost(
                    selectedRoute = Destinations.Home.route,
                    transitionState = transitionState,
                    modifier = Modifier.fillMaxSize()
                ) { route ->
                    RapidMainTabTestScene(route)
                }
            }
        }

        composeRule.runOnIdle { transitionState.request(Destinations.Explore.route) }
        var homeRightWhenMotionStarted: Float? = null
        repeat(16) {
            if (homeRightWhenMotionStarted == null) {
                advanceRapidSwitchFrame()
                val rootBounds = singleNodeBoundsOrNull(RapidSwitchRootTag)
                val homeBounds = singleNodeBoundsOrNull(RapidHomeTag)
                if (
                    rootBounds != null &&
                    homeBounds != null &&
                    homeBounds.right < rootBounds.right - POSITION_TOLERANCE_PX
                ) {
                    homeRightWhenMotionStarted = homeBounds.right
                }
            }
        }
        assertTrue(
            "Initial Tab motion did not start",
            homeRightWhenMotionStarted != null
        )

        composeRule.runOnIdle { transitionState.request(Destinations.Library.route) }
        repeat(4) { advanceRapidSwitchFrame() }
        val continuedHomeBounds = singleNodeBoundsOrNull(RapidHomeTag)
        assertTrue(
            "Queued Tab request paused the current motion: home=$continuedHomeBounds",
            continuedHomeBounds != null &&
                continuedHomeBounds.right <
                checkNotNull(homeRightWhenMotionStarted) - POSITION_TOLERANCE_PX
        )
        assertTrue(
            "Queued Tab request replaced the current transition before it settled",
            nodeCount(RapidLibraryTag) == 0
        )

        repeat((ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS * 2 / FRAME_MS) + 12) {
            advanceRapidSwitchFrame()
        }
        assertTrue(
            "Queued Tab request did not settle on the latest target",
            nodeCount(RapidLibraryTag) == 1 &&
                nodeCount(RapidHomeTag) == 0 &&
                nodeCount(RapidExploreTag) == 0
        )
    }

    @Test
    fun immediateMainTabRequestsDoNotWaitForRouteRecomposition() {
        lateinit var transitionState: MainTabLayerTransitionState
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            transitionState = rememberMainTabLayerTransitionState(Destinations.Home.route)
            Box(
                modifier = Modifier
                    .size(240.dp, 320.dp)
                    .background(Color.Black)
                    .testTag(RapidSwitchRootTag)
            ) {
                MainTabLayerHost(
                    selectedRoute = Destinations.Home.route,
                    transitionState = transitionState,
                    modifier = Modifier.fillMaxSize()
                ) { route ->
                    RapidMainTabTestScene(route)
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { transitionState.request(Destinations.Explore.route) }
        var initialMotionObserved = false
        repeat(12) {
            if (!initialMotionObserved) {
                advanceRapidSwitchFrame()
                val rootBounds = singleNodeBoundsOrNull(RapidSwitchRootTag)
                val homeBounds = singleNodeBoundsOrNull(RapidHomeTag)
                initialMotionObserved = rootBounds != null &&
                    homeBounds != null &&
                    homeBounds.right < rootBounds.right - POSITION_TOLERANCE_PX
            }
        }
        assertTrue("Immediate Tab request did not begin its initial motion", initialMotionObserved)
        composeRule.runOnIdle { transitionState.request(Destinations.Home.route) }

        var observedPairedScenes = false
        repeat((ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS * 2 / FRAME_MS) + 12) { frame ->
            advanceRapidSwitchFrame()
            assertMainTabFrameCovered(frame, RapidSwitchRootTag)
            val bounds = listOf(RapidHomeTag, RapidExploreTag)
                .mapNotNull(::singleNodeBoundsOrNull)
            assertTrue(
                "Immediate Tab request exposed no content scene at frame $frame",
                bounds.isNotEmpty()
            )
            if (bounds.size == 2) {
                observedPairedScenes = true
                assertMainTabScenesStayCentered(frame, bounds, RapidSwitchRootTag)
            }
        }

        assertTrue("Immediate Tab request did not keep paired scenes during reversal", observedPairedScenes)
        assertTrue(
            "Immediate Tab request did not settle on the latest tab",
            composeRule.onAllNodesWithTag(RapidHomeTag, useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithTag(RapidExploreTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun mainTabAnimationDoesNotRecomposeStableSceneContentEveryFrame() {
        lateinit var transitionState: MainTabLayerTransitionState
        lateinit var sceneCompositionCounts: MutableMap<String, Int>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            transitionState = rememberMainTabLayerTransitionState(Destinations.Home.route)
            sceneCompositionCounts = remember { mutableMapOf() }
            MainTabLayerHost(
                selectedRoute = Destinations.Home.route,
                transitionState = transitionState,
                modifier = Modifier.size(240.dp, 320.dp)
            ) { route ->
                SideEffect {
                    sceneCompositionCounts[route] =
                        sceneCompositionCounts.getOrDefault(route, 0) + 1
                }
                RapidMainTabTestScene(route)
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { transitionState.request(Destinations.Explore.route) }
        waitForNodeCount(RapidHomeTag, expected = 1)
        waitForNodeCount(RapidExploreTag, expected = 1)
        repeat(4) { advanceRapidSwitchFrame() }
        val countsAfterTransitionStarts = sceneCompositionCounts.toMap()

        repeat(4) {
            composeRule.mainClock.advanceTimeBy(FRAME_MS.toLong())
            composeRule.waitForIdle()
        }

        assertTrue(
            "Stable Tab content recomposed while only the graphics layer changed: " +
                "before=$countsAfterTransitionStarts after=$sceneCompositionCounts",
            sceneCompositionCounts == countsAfterTransitionStarts
        )
    }

    @Test
    fun overlappingMainTabScenesOnlyAllowThePreparedIncomingSceneToHandleTouches() {
        lateinit var transitionState: MainTabLayerTransitionState
        val clicks = mutableMapOf<String, Int>()
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            transitionState = rememberMainTabLayerTransitionState(Destinations.Home.route)
            MainTabLayerHost(
                selectedRoute = Destinations.Home.route,
                transitionState = transitionState,
                modifier = Modifier.size(240.dp, 320.dp).testTag(RapidSwitchRootTag)
            ) { route ->
                Box(Modifier.fillMaxSize().testTag("main-tab-touch-$route").clickable {
                    clicks[route] = clicks.getOrDefault(route, 0) + 1
                })
            }
        }
        repeat(4) { advanceRapidSwitchFrame() }
        composeRule.runOnIdle { transitionState.request(Destinations.Explore.route) }
        advanceRapidSwitchFrame()
        composeRule.onNodeWithTag("main-tab-touch-${Destinations.Home.route}").assertDoesNotExist()
        composeRule.onNodeWithTag("main-tab-touch-${Destinations.Explore.route}").assertDoesNotExist()
        composeRule.onNodeWithTag("main-tab-touch-${Destinations.Home.route}", useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithTag(RapidSwitchRootTag).performTouchInput { click() }
        composeRule.runOnIdle { assertTrue("Preparation frame accepted a touch", clicks.isEmpty()) }

        repeat(8) { advanceRapidSwitchFrame() }
        composeRule.onNodeWithTag("main-tab-touch-${Destinations.Home.route}").assertDoesNotExist()
        composeRule.onNodeWithTag("main-tab-touch-${Destinations.Home.route}", useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithTag("main-tab-touch-${Destinations.Explore.route}").assertHasClickAction()
        composeRule.onNodeWithTag(RapidSwitchRootTag).performTouchInput { click() }
        composeRule.runOnIdle {
            assertTrue("Outgoing Tab accepted a touch", clicks[Destinations.Home.route] == null)
            assertTrue("Prepared incoming Tab did not accept a touch", clicks[Destinations.Explore.route] == 1)
        }
    }

    @Test
    fun restoredMainTabSceneStateEndsWhenItsTabTransitionSettles() {
        lateinit var selectedRoute: MutableState<String>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            MainTabLayerHost(
                selectedRoute = selectedRoute.value,
                modifier = Modifier
                    .size(240.dp, 320.dp)
                    .testTag(RestoredSceneRootTag)
            ) { route ->
                val restoredEntry = rememberMainTabSceneRestoredEntry()
                val tag = if (
                    route == Destinations.Home.route &&
                    restoredEntry
                ) {
                    RestoredHomeSceneTag
                } else {
                    FreshHomeSceneTag
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(FirstTabColor)
                        .testTag(tag)
                )
            }
        }

        composeRule.runOnIdle {
            selectedRoute.value = Destinations.Settings.route
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle {
            selectedRoute.value = Destinations.Home.route
        }
        waitForNodeCount(RestoredHomeSceneTag, expected = 1)

        composeRule.mainClock.advanceTimeBy(
            (ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS + FRAME_MS * 4).toLong()
        )
        composeRule.waitForIdle()
        assertTrue(
            "restored state remained active after the Tab transition settled",
            composeRule.onAllNodesWithTag(RestoredHomeSceneTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )
    }

    @Test
    fun restoredDetailVisibilityDoesNotLeakIntoLaterDetailOpens() {
        lateinit var selectedRoute: MutableState<String>
        lateinit var detailKey: MutableState<String>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            detailKey = remember { mutableStateOf("restored_playlist") }
            MainTabLayerHost(selectedRoute = selectedRoute.value) { route ->
                if (route == Destinations.Home.route) {
                    val visibilityState = rememberMainTabDetailVisibilityState(detailKey.value)
                    val startedVisible = remember(detailKey.value) {
                        visibilityState.currentState
                    }
                    val tag = if (startedVisible) {
                        RestoredEntryTag
                    } else {
                        FreshEntryTag
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(tag)
                    )
                }
            }
        }

        composeRule.runOnIdle {
            selectedRoute.value = Destinations.Settings.route
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle {
            selectedRoute.value = Destinations.Home.route
        }
        waitForNodeCount(RestoredEntryTag, expected = 1)

        composeRule.mainClock.advanceTimeBy(
            (ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS + FRAME_MS * 4).toLong()
        )
        composeRule.waitForIdle()
        assertTrue(
            "restored detail restarted its entry animation after the Tab transition settled",
            composeRule.onAllNodesWithTag(RestoredEntryTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size == 1
        )

        composeRule.runOnIdle {
            detailKey.value = "new_playlist"
        }
        waitForNodeCount(FreshEntryTag, expected = 1)
    }

    @Test
    fun restoredDetailVisibilitySurvivesDelayedDetailComposition() {
        lateinit var selectedRoute: MutableState<String>
        lateinit var detailComposed: MutableState<Boolean>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            detailComposed = remember { mutableStateOf(true) }
            MainTabLayerHost(selectedRoute = selectedRoute.value) { route ->
                if (route == Destinations.Home.route && detailComposed.value) {
                    val visibilityState = rememberMainTabDetailVisibilityState(
                        "restored_playlist"
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(
                                if (visibilityState.currentState) {
                                    DelayedRestoredDetailTag
                                } else {
                                    DelayedFreshDetailTag
                                }
                            )
                    )
                }
            }
        }

        composeRule.runOnIdle {
            selectedRoute.value = Destinations.Settings.route
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            detailComposed.value = false
        }

        composeRule.runOnIdle {
            selectedRoute.value = Destinations.Home.route
        }
        composeRule.mainClock.advanceTimeBy(
            (ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS + FRAME_MS * 4).toLong()
        )
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            detailComposed.value = true
        }
        waitForNodeCount(DelayedRestoredDetailTag, expected = 1)
        assertTrue(
            "delayed restored detail replayed its entry animation",
            composeRule.onAllNodesWithTag(DelayedFreshDetailTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )
    }

    @Test
    fun closingRestoredHostDetailAfterTabSwitchKeepsCloseAnimation() {
        lateinit var selectedRoute: MutableState<String>
        lateinit var detailVisible: MutableState<Boolean>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            selectedRoute = remember { mutableStateOf(Destinations.Home.route) }
            MainTabLayerHost(selectedRoute = selectedRoute.value) { route ->
                if (route == Destinations.Home.route) {
                    detailVisible = rememberSaveable { mutableStateOf(false) }
                    val suppressRestoredEntry = rememberMainTabSceneRestoredEntry()
                    val detailTransition = updateTransition(
                        targetState = detailVisible.value,
                        label = "restored_host_detail_transition"
                    )
                    val rootRevealFraction =
                        detailTransition.animateMainTabDetailCloseRootRevealFraction(
                            navigationDepth = { visible -> visible.testNavigationDepth },
                            label = "restored_host_detail_close"
                        )
                    detailTransition.AnimatedContent(
                        transitionSpec = {
                            if (
                                shouldSuppressRestoredMainTabHostEntry(
                                    restoredEntry = suppressRestoredEntry,
                                    initialDepth = initialState.testNavigationDepth,
                                    targetDepth = targetState.testNavigationDepth
                                )
                            ) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                            }.using(SizeTransform(clip = true))
                        },
                        modifier = Modifier.fillMaxSize()
                    ) { detail ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag(
                                    if (detail) {
                                        RestoredHostDetailTag
                                    } else {
                                        RestoredHostRootSceneTag
                                    }
                                )
                        ) {
                            if (!detail) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clipMainTabDetailCloseRoot(rootRevealFraction)
                                        .background(FirstTabColor)
                                        .testTag(RestoredHostRootContentTag)
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(RestoredHostOtherTabTag)
                    )
                }
            }
        }

        composeRule.runOnIdle {
            detailVisible.value = true
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        assertTrue(
            "test detail did not open before switching tabs",
            nodeCount(RestoredHostDetailTag) == 1
        )

        composeRule.runOnIdle {
            selectedRoute.value = Destinations.Settings.route
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle {
            selectedRoute.value = Destinations.Home.route
        }
        waitForNodeCount(RestoredHostDetailTag, expected = 1)

        composeRule.runOnIdle {
            detailVisible.value = false
        }
        advanceRapidSwitchFrame()
        assertTrue(
            "restored host close animation was cut at its first frame",
            nodeCount(RestoredHostRootSceneTag) == 1 &&
                nodeCount(RestoredHostDetailTag) == 1
        )
        assertTrue(
            "restored root content disappeared during the close",
            nodeCount(RestoredHostRootContentTag) == 1
        )
        val firstCloseFramePixels = composeRule
            .onNodeWithTag(RestoredHostRootContentTag)
            .captureToImage()
            .toPixelMap()
        assertTrue(
            "restored root content was fully exposed behind the closing detail",
            countDominantPixelsInBottomHalf(
                pixels = firstCloseFramePixels,
                dominantRed = true
            ) == 0
        )

        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        assertTrue(
            "restored host detail remained after close animation",
            nodeCount(RestoredHostDetailTag) == 0 &&
                nodeCount(RestoredHostRootContentTag) == 1
        )
    }

    @Test
    fun externalRouteChangeDuringMainTabTransitionStaysCoveredAndSettlesOnDetail() {
        lateinit var navController: NavHostController
        lateinit var selectedRoute: MutableState<String>
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
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
            assertMainTabScenesStayCentered(frame, bounds, ExternalRootTag)
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
        setMainTabContent {
            navController = rememberNavController()
            val backEntry by navController.currentBackStackEntryAsState()
            var layerHeightPx by remember { mutableIntStateOf(0) }
            val motion = resolveMainTabBackgroundMotion(
                route = backEntry?.destination?.route,
                coherentFeedbackEnabled = true
            )
            val targetProgress = if (motion == MainTabBackgroundMotion.NONE) 0f else 1f
            val backgroundProgress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(
                    durationMillis = resolveMainTabBackgroundMotionDurationMillis(
                        targetProgress = targetProgress,
                        coherentFeedbackEnabled = true,
                        debugSceneVisible = false
                    ),
                    easing = mainTabDetailContentOffsetEasing()
                ),
                label = "test_main_tab_detail_handoff"
            )
            val backgroundTransform = resolveMainTabBackgroundTransform(
                motion = motion,
                progress = backgroundProgress
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
                                y = (
                                        backgroundTransform.translationYFraction * layerHeightPx
                                        ).roundToInt()
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
    fun drawerDetailKeepsTheSinkingMainTabLayerVisibleWhileOpening() {
        assertExternalBackgroundLayerBehavior(
            mainRoute = Destinations.Library.route,
            childRoute = Destinations.PlaybackStats.route,
            coherentFeedbackEnabled = false,
            debugRoute = false,
            durationMillis = DRAWER_DETAIL_OPEN_DURATION_MS,
            backgroundShouldRemain = true
        )
    }

    @Test
    fun drawerDebugChildKeepsTheSinkingDebugHomeLayerVisibleWhileOpening() {
        assertExternalBackgroundLayerBehavior(
            mainRoute = Destinations.Debug.route,
            childRoute = Destinations.DebugListenTogether.route,
            coherentFeedbackEnabled = false,
            debugRoute = true,
            durationMillis = DRAWER_DETAIL_OPEN_DURATION_MS,
            backgroundShouldRemain = true
        )
    }

    @Test
    fun nestedDetailAndAlbumScenesNeverOverlapDuringForwardAndBackTransitions() {
        lateinit var navController: NavHostController
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
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
        setMainTabContent {
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

    private fun assertExternalBackgroundLayerBehavior(
        mainRoute: String,
        childRoute: String,
        coherentFeedbackEnabled: Boolean,
        debugRoute: Boolean,
        durationMillis: Int,
        backgroundShouldRemain: Boolean
    ) {
        lateinit var navController: NavHostController
        composeRule.mainClock.autoAdvance = false
        setMainTabContent {
            navController = rememberNavController()
            val backEntry by navController.currentBackStackEntryAsState()
            var layerHeightPx by remember { mutableIntStateOf(0) }
            val motion = resolveMainTabBackgroundMotion(
                route = backEntry?.destination?.route,
                coherentFeedbackEnabled = coherentFeedbackEnabled
            )
            val targetProgress = if (motion == MainTabBackgroundMotion.NONE) 0f else 1f
            val progress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(
                    durationMillis = resolveMainTabBackgroundMotionDurationMillis(
                        targetProgress = targetProgress,
                        coherentFeedbackEnabled = coherentFeedbackEnabled,
                        debugSceneVisible = debugRoute && targetProgress > 0f
                    ),
                    easing = mainTabDetailContentOffsetEasing()
                ),
                label = "external_background_layer_handoff"
            )
            val transform = resolveMainTabBackgroundTransform(motion, progress)
            Box(
                modifier = Modifier
                    .size(240.dp, 320.dp)
                    .background(Color.Black)
                    .testTag(ExternalBackgroundRootTag)
            ) {
                MainTabLayerHost(
                    selectedRoute = mainRoute,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size -> layerHeightPx = size.height }
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (
                                        transform.translationYFraction * layerHeightPx
                                        ).roundToInt()
                            )
                        }
                        .graphicsLayer {
                            scaleX = transform.scale
                            scaleY = transform.scale
                            alpha = transform.alpha
                            transformOrigin = TransformOrigin.Center
                        }
                        .zIndex(0f)
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(FirstTabColor)
                            .testTag(ExternalBackgroundSceneTag)
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = mainRoute,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                ) {
                    composable(
                        route = mainRoute,
                        enterTransition = {
                            mainTabEnterTransition(coherentFeedbackEnabled)
                        },
                        exitTransition = {
                            mainTabExitTransition(coherentFeedbackEnabled)
                        }
                    ) {}
                    if (debugRoute) {
                        composable(
                            route = childRoute,
                            enterTransition = {
                                debugNavigationEnterTransition(coherentFeedbackEnabled)
                            },
                            exitTransition = {
                                debugNavigationExitTransition(coherentFeedbackEnabled)
                            }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag(ExternalForegroundSceneTag)
                            )
                        }
                    } else {
                        composable(
                            route = childRoute,
                            enterTransition = {
                                transparentDetailEnterTransition(coherentFeedbackEnabled)
                            },
                            exitTransition = {
                                transparentDetailExitTransition(coherentFeedbackEnabled)
                            }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag(ExternalForegroundSceneTag)
                            )
                        }
                    }
                }
            }
        }

        composeRule.runOnIdle { navController.navigate(childRoute) }
        composeRule.mainClock.advanceTimeBy((durationMillis / 2).toLong())
        composeRule.waitForIdle()
        val midpoint = composeRule
            .onNodeWithTag(ExternalBackgroundRootTag)
            .captureToImage()
            .toPixelMap()
        assertTrue(
            "Background layer disappeared before the foreground reached it",
            countDominantPixels(midpoint, dominantRed = true) > 0
        )

        composeRule.mainClock.advanceTimeBy((durationMillis + FRAME_MS * 2).toLong())
        composeRule.waitForIdle()
        val settled = composeRule
            .onNodeWithTag(ExternalBackgroundRootTag)
            .captureToImage()
            .toPixelMap()
        val settledBackgroundPixels = countDominantPixels(settled, dominantRed = true)
        assertTrue(
            if (backgroundShouldRemain) {
                "Drawer hid the sinking background scene before it was covered"
            } else {
                "Debug home scene remained visible through the settled foreground"
            },
            if (backgroundShouldRemain) {
                settledBackgroundPixels > settled.width * settled.height / 2
            } else {
                settledBackgroundPixels == 0
            }
        )
        assertTrue(
            "Foreground scene disappeared after navigation",
            nodeCount(ExternalForegroundSceneTag) == 1
        )
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
                .onAllNodesWithTag(upperSceneTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
            val lowerNodes = composeRule
                .onAllNodesWithTag(lowerSceneTag, useUnmergedTree = true)
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

    private fun setMainTabContent(
        smallestScreenWidthDp: Int = 600,
        screenWidthDp: Int = 240,
        content: @Composable () -> Unit
    ) {
        composeRule.setContent {
            val currentConfiguration = LocalConfiguration.current
            val configuration = remember(currentConfiguration, smallestScreenWidthDp, screenWidthDp) {
                Configuration(currentConfiguration).apply {
                    this.smallestScreenWidthDp = smallestScreenWidthDp
                    this.screenWidthDp = screenWidthDp
                }
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, content = content)
        }
    }

    private fun assertPhoneMainTabFrameOpaque(frame: Int) {
        val pixels = composeRule.onNodeWithTag(MainTabRootTag).captureToImage().toPixelMap()
        var opaquePixels = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if ((color.red >= 0.98f && color.blue <= 0.02f) ||
                    (color.blue >= 0.98f && color.red <= 0.02f)
                ) opaquePixels++
            }
        }
        assertTrue("Phone Tab slide faded, blended or uncovered content at frame $frame: " +
            "$opaquePixels/${pixels.width * pixels.height}",
            opaquePixels >= pixels.width * pixels.height * 98 / 100)
    }

    private fun assertMainTabFrameCovered(frame: Int, rootTag: String) {
        val pixels = composeRule.onNodeWithTag(rootTag).captureToImage().toPixelMap()
        val insetX = ceil(pixels.width * MAIN_TAB_SCALE_INSET_FRACTION).toInt()
        val insetY = ceil(pixels.height * MAIN_TAB_SCALE_INSET_FRACTION).toInt()
        val contentArea = (pixels.width - insetX * 2) * (pixels.height - insetY * 2)
        var coveredPixels = 0
        for (y in insetY until pixels.height - insetY) {
            for (x in insetX until pixels.width - insetX) {
                val color = pixels[x, y]
                if (color.red > 0.12f || color.green > 0.12f || color.blue > 0.12f) coveredPixels++
            }
        }
        assertTrue(
            "Main Tab frame exposed blank content inside the scale margin at frame $frame: " +
                "$coveredPixels/$contentArea",
            coveredPixels >= contentArea * MIN_FRAME_COVERAGE_PERCENT / 100
        )
    }

    private fun singleNodeBoundsOrNull(tag: String): Rect? {
        val nodes = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Multiple scene nodes found for $tag: ${nodes.size}", nodes.size <= 1)
        return nodes.singleOrNull()?.boundsInRoot?.takeIf { bounds ->
            bounds.width > 0f && bounds.height > 0f
        }
    }

    private fun assertMainTabScenesStayCentered(frame: Int, bounds: List<Rect>, rootTag: String) {
        val rootBounds = checkNotNull(singleNodeBoundsOrNull(rootTag))
        bounds.forEach { sceneBounds ->
            assertTrue(
                "Main Tab scene translated at frame $frame: root=$rootBounds scene=$sceneBounds",
                abs(sceneBounds.center.x - rootBounds.center.x) <= POSITION_TOLERANCE_PX &&
                    abs(sceneBounds.center.y - rootBounds.center.y) <= POSITION_TOLERANCE_PX
            )
            assertTrue(
                "Main Tab scene distorted its aspect ratio at frame $frame: $sceneBounds",
                abs(sceneBounds.width / rootBounds.width - sceneBounds.height / rootBounds.height) < 0.01f
            )
            assertTrue(
                "Main Tab scene exceeded its scale range at frame $frame: $sceneBounds",
                sceneBounds.width >= rootBounds.width * 0.94f - POSITION_TOLERANCE_PX &&
                    sceneBounds.width <= rootBounds.width + POSITION_TOLERANCE_PX
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
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun waitForNodeCount(
        tag: String,
        expected: Int,
        maxFrames: Int = 4
    ) {
        repeat(maxFrames + 1) {
            composeRule.waitForIdle()
            if (nodeCount(tag) == expected) return
            composeRule.mainClock.advanceTimeBy(FRAME_MS.toLong())
        }
        assertTrue(
            "Expected $expected node(s) with tag $tag, found ${nodeCount(tag)}",
            nodeCount(tag) == expected
        )
    }

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

    private fun countDominantPixelsInBottomHalf(
        pixels: androidx.compose.ui.graphics.PixelMap,
        dominantRed: Boolean
    ): Int {
        var count = 0
        for (y in pixels.height / 2 until pixels.height) {
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

    private companion object {
        const val RootTag = "navigation_transition_root"
        const val MainTabRootTag = "main_tab_transition_root"
        const val RapidSwitchRootTag = "rapid_switch_root"
        const val FirstMainTabSceneTag = "first_main_tab_scene"
        const val SecondMainTabSceneTag = "second_main_tab_scene"
        const val RapidHomeTag = "rapid_home_scene"
        const val RapidExploreTag = "rapid_explore_scene"
        const val RapidLibraryTag = "rapid_library_scene"
        const val RestoredSceneRootTag = "restored_scene_root"
        const val FreshHomeSceneTag = "fresh_home_scene"
        const val RestoredHomeSceneTag = "restored_home_scene"
        const val RestoredEntryTag = "restored_entry_scene"
        const val FreshEntryTag = "fresh_entry_scene"
        const val DelayedRestoredDetailTag = "delayed_restored_detail_scene"
        const val DelayedFreshDetailTag = "delayed_fresh_detail_scene"
        const val RestoredHostRootSceneTag = "restored_host_root_scene"
        const val RestoredHostRootContentTag = "restored_host_root_content"
        const val RestoredHostDetailTag = "restored_host_detail"
        const val RestoredHostOtherTabTag = "restored_host_other_tab"
        const val ExternalRootTag = "external_transition_root"
        const val ExternalHomeTag = "external_home_scene"
        const val ExternalExploreTag = "external_explore_scene"
        const val ExternalDetailTag = "external_detail_scene"
        const val LayeredRootTag = "layered_transition_root"
        const val LayeredLibrarySceneTag = "layered_library_scene"
        const val LayeredDetailSceneTag = "layered_detail_scene"
        const val ExternalBackgroundRootTag = "external_background_root"
        const val ExternalBackgroundSceneTag = "external_background_scene"
        const val ExternalForegroundSceneTag = "external_foreground_scene"
        const val LibrarySceneTag = "library_scene"
        const val DetailSceneTag = "detail_scene"
        const val PreviousDetailSceneTag = "previous_detail_scene"
        const val NestedAlbumSceneTag = "nested_album_scene"
        const val FRAME_MS = 16
        const val POSITION_TOLERANCE_PX = 1f
        const val MIN_FRAME_COVERAGE_PERCENT = 98
        const val MAIN_TAB_SCALE_INSET_FRACTION = 0.03f
        val FirstTabColor = Color(0xFFFF0000)
        val SecondTabColor = Color(0xFF0000FF)
        val ThirdTabColor = Color(0xFF00FF00)
    }
}
