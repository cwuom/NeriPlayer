package moe.ouom.neriplayer.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavigatorProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegionRegistry
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationRailLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val width = mutableStateOf(1280.dp)
    private val height = mutableStateOf(800.dp)
    private val currentDestination = mutableStateOf<NavDestination?>(null)
    private val playbackRequested = mutableStateOf(false)
    private val playbackMounted = mutableStateOf(false)
    private val glassRegistry = AdvancedGlassRegionRegistry()
    private var selectedRoute: String? = null

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun nestedDestinationSelectionAndConfiguredItemsKeepTheirCallbacks() {
        val libraryGraph = NavGraph(NavGraphNavigator(NavigatorProvider())).apply {
            route = Destinations.Library.route
        }
        val nestedDestination = NavDestination("fixture").apply { route = "library/detail" }
        libraryGraph.addDestination(nestedDestination)
        currentDestination.value = nestedDestination
        render()

        composeRule.onNodeWithTag("appNavigationItem_library").assertIsSelected()
        composeRule.onNodeWithTag("appNavigationLabel_library", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("appNavigationLabel_explore", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("appNavigationLabel_settings", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("appNavigationLabel_debug", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("appNavigationItem_explore").assertIsNotSelected()
        composeRule.onNodeWithTag("appNavigationItem_home").assertDoesNotExist()
        composeRule.onNodeWithTag("appNavigationItem_debug").assertIsDisplayed()
        composeRule.onNodeWithTag("appNavigationItem_explore").performClick().assertIsSelected()
        composeRule.onNodeWithTag("appNavigationItem_library").assertIsNotSelected()
        composeRule.onNodeWithTag("appNavigationLabel_library", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("appNavigationLabel_explore", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(Destinations.Explore.route, selectedRoute) }
    }

    @Test
    fun destinationGroupIsCenteredAndRemainsScrollableInShortWindows() {
        currentDestination.value = NavDestination("fixture").apply { route = Destinations.Library.route }
        render()
        val first = bounds("appNavigationItem_explore")
        val last = bounds("appNavigationItem_debug")
        val viewport = bounds("appNavigationItemsViewport")
        assertEquals("侧栏选项组应垂直居中", viewport.center.y, (first.top + last.bottom) / 2f, 1f)

        composeRule.runOnIdle { height.value = 240.dp }
        composeRule.onNodeWithTag("appNavigationItem_debug").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("appNavigationItem_debug").assertIsSelected()
        composeRule.runOnIdle { assertEquals(Destinations.Debug.route, selectedRoute) }
    }

    @Test
    fun switchingBetweenRailAndBottomModesKeepsTheMainContentState() {
        render()
        val rail = bounds("appNavigationRail")
        val content = bounds("appNavigationContent")
        assertTrue(content.left >= rail.right - 1f)
        assertEquals(bounds("viewport").right, content.right, 1f)
        composeRule.onNodeWithTag("fixtureCounter").performClick()
        composeRule.onNodeWithText("counter 1").assertIsDisplayed()

        composeRule.runOnIdle { width.value = 600.dp }
        composeRule.onNodeWithTag("appNavigationRail").assertDoesNotExist()
        composeRule.onNodeWithText("counter 1").assertIsDisplayed()
        assertEquals(bounds("viewport").left, bounds("appNavigationContent").left, 1f)

        composeRule.runOnIdle { width.value = 1280.dp }
        composeRule.onNodeWithTag("appNavigationRail").assertIsDisplayed()
        composeRule.onNodeWithText("counter 1").assertIsDisplayed()
        composeRule.onNodeWithTag("fixtureCounter").performClick()
        composeRule.onNodeWithText("counter 2").assertIsDisplayed()
    }

    @Test
    fun openingAndClosingPlaybackNaturallyCoversRailWithoutChangingItsGeometryOrBackdrop() {
        render(visibilityFixture = true)
        composeRule.onNodeWithTag("fixtureRailCounter").performClick()
        composeRule.onNodeWithTag("fixtureCounter").performClick()
        val rail = bounds("fixtureRail")
        val railButton = bounds("fixtureRailCounter")
        val content = bounds("appNavigationContent")
        assertRailColor(rail, Color.Red)
        assertRegisteredRailCount(1)
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle { playbackRequested.value = true }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertBlockedRail(content)
        assertRailColor(rail, Color.Red)
        assertRegisteredRailCount(1)
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
        assertBlockedRail(content)
        val viewport = bounds("viewport")
        composeRule.onNodeWithTag("viewport").performTouchInput {
            click(railButton.center - viewport.topLeft)
        }

        composeRule.mainClock.advanceTimeBy(320)
        composeRule.waitForIdle()
        assertRailColor(rail, Color.Blue)
        assertRegisteredRailCount(1)
        composeRule.runOnIdle { playbackRequested.value = false }
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("fixturePlaybackOverlay").assertExists()
        assertBlockedRail(content)
        assertRegisteredRailCount(1)

        composeRule.mainClock.advanceTimeBy(320)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("fixturePlaybackOverlay").assertDoesNotExist()
        assertEquals(content, bounds("appNavigationContent"))
        composeRule.onNodeWithText("rail counter 1").assertIsDisplayed()
        composeRule.onNodeWithText("counter 1").assertIsDisplayed()
        assertRailColor(rail, Color.Red)
        assertRegisteredRailCount(1)
        composeRule.onNodeWithTag("fixtureRailCounter").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("rail counter 2").assertIsDisplayed()
    }

    @Test
    fun interruptedPlaybackTransitionsKeepRailInteractionBlockedUntilOverlayDisposal() {
        render(visibilityFixture = true)
        composeRule.onNodeWithTag("fixtureRailCounter").performClick()
        val rail = bounds("fixtureRail")
        val content = bounds("appNavigationContent")
        composeRule.mainClock.autoAdvance = false

        listOf(true, false, true, false, true).forEach { requested ->
            composeRule.runOnIdle { playbackRequested.value = requested }
            composeRule.mainClock.advanceTimeBy(64)
            composeRule.waitForIdle()
            assertBlockedRail(content)
            assertEquals(rail, bounds("fixtureRail"))
            assertRegisteredRailCount(1)
        }
        composeRule.runOnIdle { playbackRequested.value = false }
        composeRule.mainClock.advanceTimeBy(320)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("rail counter 1").assertIsDisplayed()
        assertEquals(content, bounds("appNavigationContent"))
        assertRegisteredRailCount(1)
    }

    @Test
    fun restoredOpenPlaybackDoesNotMountAVisibleRailOnTheFirstLayout() {
        playbackRequested.value = true
        render(visibilityFixture = true)
        val content = bounds("appNavigationContent")
        val viewport = bounds("viewport")
        assertTrue(content.left > viewport.left)
        composeRule.onNodeWithTag("fixtureRailCounter").assertDoesNotExist()
        assertRegisteredRailCount(1)
        assertRailColor(bounds("fixtureRail"), Color.Blue)

        composeRule.runOnIdle { playbackRequested.value = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("rail counter 0").assertIsDisplayed()
        assertEquals(content, bounds("appNavigationContent"))
        assertRegisteredRailCount(1)
    }

    private fun render(visibilityFixture: Boolean = false) {
        val items = listOf(
            Destinations.Explore to Icons.Default.Search,
            Destinations.Library to Icons.Default.LibraryMusic,
            Destinations.Settings to Icons.Default.Settings,
            Destinations.Debug to Icons.Default.BugReport
        )
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val currentWidth = width.value
                    val density = LocalDensity.current
                    val currentHeight = height.value
                    val scale = minOf(maxWidth.value / currentWidth.value, maxHeight.value / currentHeight.value)
                    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, fontScale = 1f)) {
                        BoxWithConstraints(
                            Modifier.requiredSize(currentWidth, currentHeight).background(Color.Green).testTag("viewport")
                        ) {
                            val useRail = shouldUseAppNavigationRail(800, maxWidth > maxHeight, maxWidth)
                            val hideNavigation = playbackRequested.value || playbackMounted.value
                            AppAdaptiveNavigationContent(
                                useNavigationRail = useRail,
                                showNowPlaying = hideNavigation,
                                offlineMode = false,
                                railContent = {
                                    if (visibilityFixture) {
                                        VisibilityRailFixture()
                                    } else AppNavigationRail(
                                        presentation = AppBottomBarPresentation(
                                            items = items,
                                            currentDestination = currentDestination.value,
                                            showNowPlaying = hideNavigation,
                                            offlineMode = false,
                                            alwaysUseNewTabStyle = false,
                                            backgroundImageUri = null
                                        ),
                                        onMainTabSelected = { route ->
                                            selectedRoute = route
                                            currentDestination.value = NavDestination("fixture").apply { this.route = route }
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                var count by remember { mutableIntStateOf(0) }
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Button(onClick = { count++ }, modifier = Modifier.testTag("fixtureCounter")) {
                                        Text("counter $count")
                                    }
                                }
                            }
                            if (visibilityFixture) {
                                AnimatedVisibility(
                                    visible = playbackRequested.value,
                                    enter = slideInVertically(tween(300)) { it } + fadeIn(tween(150)),
                                    exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(150))
                                ) {
                                    DisposableEffect(Unit) {
                                        playbackMounted.value = true
                                        onDispose { playbackMounted.value = false }
                                    }
                                    Box(Modifier.fillMaxSize().background(Color.Blue).testTag("fixturePlaybackOverlay"))
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun VisibilityRailFixture() {
        val backdrops = remember {
            AdvancedGlassBackdrops(
                background = AdvancedGlassBackdrop().apply { positionInWindow = Offset.Zero },
                content = AdvancedGlassBackdrop().apply { positionInWindow = Offset.Zero },
                regionRegistry = glassRegistry
            )
        }
        CompositionLocalProvider(
            LocalAdvancedGlassBackdrops provides backdrops,
            LocalAdvancedGlassController provides AdvancedGlassController(
                sdkInt = 36,
                advancedBlurEnabled = true,
                enhancedAdvancedBlurEnabled = false,
                backendReady = true
            )
        ) {
            AdvancedGlassSurface(
                role = AdvancedGlassRole.NavigationRail,
                modifier = Modifier.fillMaxSize().testTag("fixtureRail")
            ) {
                var railCount by remember { mutableIntStateOf(0) }
                Box(Modifier.fillMaxSize().background(Color.Red), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = { railCount++ },
                        modifier = Modifier.testTag("fixtureRailCounter")
                    ) { Text("rail counter $railCount") }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun assertBlockedRail(content: Rect) {
        assertEquals(content, bounds("appNavigationContent"))
        composeRule.onNodeWithTag("fixtureRailCounter").assertDoesNotExist()
    }

    private fun assertRegisteredRailCount(expected: Int) {
        composeRule.runOnIdle {
            assertEquals(expected, glassRegistry.regions.count { it.role == AdvancedGlassRole.NavigationRail })
        }
    }

    private fun assertRailColor(rail: Rect, expected: Color) {
        val viewport = bounds("viewport")
        val pixels = composeRule.onNodeWithTag("viewport").captureToImage().toPixelMap()
        val sample = pixels[
            (rail.center.x - viewport.left).toInt(),
            (rail.top + rail.height / 4f - viewport.top).toInt()
        ]
        assertEquals(expected.red, sample.red, 0.02f)
        assertEquals(expected.green, sample.green, 0.02f)
        assertEquals(expected.blue, sample.blue, 0.02f)
    }
}
