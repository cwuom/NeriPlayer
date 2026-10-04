package moe.ouom.neriplayer.ui.screen.tab

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegionRegistry
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassNavigationOwner
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassSceneOpacity
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.resolveCurrentAdvancedGlassRegions
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.SettingsNavigationState
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.SettingsPageHost
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.rememberSettingsNavigationState
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.settingsPageTransitionSceneTag
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsResponsiveDetailScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SETTINGS_SPLIT_DETAIL_PANE_TAG
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SETTINGS_SPLIT_NAVIGATION_PANE_TAG
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsSearchEntry
import moe.ouom.neriplayer.ui.screen.tab.settings.page.backTargetPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class SettingsPageTransitionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var selectedPage: MutableState<SettingsPage?>
    private val opacityGetters = mutableMapOf<SettingsPage?, () -> Float>()
    private val clickedPages = mutableListOf<SettingsPage?>()
    private lateinit var listStates: Map<SettingsPage, LazyListState>
    private lateinit var topBarStates: Map<SettingsPage, TopAppBarState>

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun phonePortraitSettingsPagesKeepDirectionalSpringWithoutScaleOrFade() {
        renderSinglePane(420f, 700f, smallestWidth = 360)
        assertPhoneSlideFrames(SettingsPage.General, SettingsPage.Accounts, forward = true)
        assertPhoneSlideFrames(SettingsPage.Accounts, SettingsPage.General, forward = false)
    }

    @Test
    fun phoneLandscapeUsesItsSmallestWidthAndKeepsTheOriginalSpring() {
        renderSinglePane(840f, 360f, smallestWidth = 360)
        assertPhoneSlideFrames(SettingsPage.General, SettingsPage.Accounts, forward = true)
        assertPhoneSlideFrames(SettingsPage.Accounts, SettingsPage.General, forward = false)
    }

    @Test
    fun tabletPortraitScaleAndFadeHasNoBlankFrameAtTheHandoff() {
        renderSinglePane(800f, 1280f, smallestWidth = 800)
        assertSinglePaneFrames(SettingsPage.General, SettingsPage.Accounts, "settings-tablet-portrait")
    }

    @Test
    fun tabletRapidRetargetKeepsVisibleContentThroughAtoBtoCtoA() {
        renderSinglePane(960f, 640f, smallestWidth = 800)
        val root = bounds(RootTag)
        listOf(SettingsPage.Accounts, SettingsPage.Playback, SettingsPage.General).forEach { target ->
            composeRule.runOnIdle { selectedPage.value = target }
            repeat(3) { frame ->
                advanceFrame()
                listOf(SettingsPage.General, SettingsPage.Accounts, SettingsPage.Playback).forEach { page ->
                    sceneBoundsOrNull(page)?.let { assertCenteredScale(it, root) }
                }
                assertColoredFrameHasVisibleContent("快速切换到 $target")
                if (target == SettingsPage.General && frame == 0) {
                    composeRule.onNodeWithTag(actionTag(target), useUnmergedTree = true).assertExists()
                    composeRule.onAllNodesWithTag(actionTag(target)).assertCountEquals(0)
                    clickUnmergedAction(target)
                    composeRule.runOnIdle { assertTrue("排队期间旧页仍可点击", clickedPages.isEmpty()) }
                }
            }
        }
        repeat(FrameCount * 2) { frame ->
            advanceFrame()
            assertColoredFrameHasVisibleContent("快速切换恢复 General/$frame")
        }
        assertRectEquals(root, requireNotNull(sceneBoundsOrNull(SettingsPage.General)))
        assertTrue(sceneBoundsOrNull(SettingsPage.Accounts) == null)
        assertTrue(sceneBoundsOrNull(SettingsPage.Playback) == null)
        capture("settings-tablet-rapid-retarget")
    }

    @Test
    fun tabletPrimaryAndNestedTransitionsKeepOneFixedNavigationPane() {
        renderSplitPane()
        val left = bounds(SETTINGS_SPLIT_NAVIGATION_PANE_TAG)
        val right = bounds(SETTINGS_SPLIT_DETAIL_PANE_TAG)
        listOf(SettingsPage.Accounts, SettingsPage.Playback, SettingsPage.UsbExclusive, SettingsPage.Playback)
            .forEach { target ->
                val previous = composeRule.runOnIdle { requireNotNull(selectedPage.value) }
                composeRule.runOnIdle { selectedPage.value = target }
                var sawScaledScene = false
                repeat(FrameCount) {
                    advanceFrame()
                    composeRule.onAllNodesWithTag(SETTINGS_SPLIT_NAVIGATION_PANE_TAG).assertCountEquals(1)
                    assertRectEquals(left, bounds(SETTINGS_SPLIT_NAVIGATION_PANE_TAG))
                    assertRectEquals(right, bounds(SETTINGS_SPLIT_DETAIL_PANE_TAG))
                    listOf(previous, target).forEach { page ->
                        sceneBoundsOrNull(page)?.let { scene ->
                            assertCenteredScale(scene, right)
                            sawScaledScene = sawScaledScene || scene.width < right.width - 0.5f
                        }
                    }
                }
                assertTrue("平板右栏没有实际缩放: $previous->$target", sawScaledScene)
                assertRectEquals(right, requireNotNull(sceneBoundsOrNull(target)))
                assertFalse("旧页未结束退出: $previous->$target", sceneBoundsOrNull(previous) != null)
            }
        capture("settings-tablet-landscape")
    }

    @Test
    fun tabletScenesKeepTheirOwnRowsScrollAndTopBarStatesDuringPrimaryAndNestedChanges() {
        renderSplitPane()
        val originalScene = requireNotNull(sceneBoundsOrNull(SettingsPage.General))
        val originalRow = bounds(rowTag(SettingsPage.General, 5))
        val generalBarOffset = composeRule.runOnIdle { topBarStates.getValue(SettingsPage.General).heightOffset }
        composeRule.runOnIdle { selectedPage.value = SettingsPage.Accounts }
        repeat(4) { advanceFrame() }
        val oldScene = requireNotNull(sceneBoundsOrNull(SettingsPage.General))
        val oldRow = bounds(rowTag(SettingsPage.General, 5))
        val scale = oldScene.height / originalScene.height
        assertEquals(
            "旧场景改用了新页的滚动位置",
            oldScene.top + (originalRow.top - originalScene.top) * scale,
            oldRow.top,
            2f
        )
        finishTransition()
        composeRule.onNodeWithTag(rowTag(SettingsPage.Accounts, 2)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(5, listStates.getValue(SettingsPage.General).firstVisibleItemIndex)
            assertEquals(2, listStates.getValue(SettingsPage.Accounts).firstVisibleItemIndex)
            assertEquals(generalBarOffset, topBarStates.getValue(SettingsPage.General).heightOffset, 0.01f)
            selectedPage.value = SettingsPage.General
        }
        finishTransition()
        assertRectEquals(originalRow, bounds(rowTag(SettingsPage.General, 5)))

        composeRule.runOnIdle { selectedPage.value = SettingsPage.Playback }
        finishTransition()
        composeRule.onNodeWithTag(rowTag(SettingsPage.Playback, 7)).assertIsDisplayed()
        composeRule.runOnIdle { selectedPage.value = SettingsPage.UsbExclusive }
        finishTransition()
        composeRule.onNodeWithTag(rowTag(SettingsPage.UsbExclusive, 3)).assertIsDisplayed()
        val backLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(CoreCommonR.string.action_back)
        composeRule.onNodeWithContentDescription(backLabel).performClick()
        finishTransition()
        composeRule.runOnIdle { assertEquals(SettingsPage.Playback, selectedPage.value) }
        composeRule.onNodeWithTag(rowTag(SettingsPage.Playback, 7)).assertIsDisplayed()
    }

    @Test
    fun outgoingAndZeroAlphaTabletScenesHideActionsAndConsumeRealTouches() {
        renderSinglePane(800f, 1280f, smallestWidth = 800)
        composeRule.onNodeWithTag(actionTag(SettingsPage.General)).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(SettingsPage.General), clickedPages)
            selectedPage.value = SettingsPage.Accounts
        }
        advanceFrame()
        composeRule.onNodeWithTag(actionTag(SettingsPage.General), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(actionTag(SettingsPage.Accounts), useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithTag(actionTag(SettingsPage.General)).assertCountEquals(0)
        val incomingAlpha = composeRule.runOnIdle { opacityGetters.getValue(SettingsPage.Accounts)() }
        assertEquals("新场景刚挂载时应隔离不可见动作", 0f, incomingAlpha, 0f)
        composeRule.onAllNodesWithTag(actionTag(SettingsPage.Accounts)).assertCountEquals(0)
        clickUnmergedAction(SettingsPage.Accounts)
        clickUnmergedAction(SettingsPage.General)
        composeRule.runOnIdle { assertEquals(listOf(SettingsPage.General), clickedPages) }
        repeat(4) { advanceFrame() }
        composeRule.onNodeWithTag(actionTag(SettingsPage.Accounts)).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf(SettingsPage.General, SettingsPage.Accounts), clickedPages) }
        finishTransition()
        composeRule.onNodeWithTag(actionTag(SettingsPage.Accounts)).performClick()
        composeRule.runOnIdle { assertEquals(listOf(SettingsPage.General, SettingsPage.Accounts, SettingsPage.Accounts), clickedPages) }
        composeRule.onAllNodesWithTag(actionTag(SettingsPage.General), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun searchNavigationHighlightBackAndSavedScrollSurviveTheNewTransition() {
        lateinit var navigation: SettingsNavigationState
        lateinit var scope: CoroutineScope
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            Fixture(420f, 700f, smallestWidth = 360) {
                val homeListState = rememberLazyListState()
                val owner = rememberSettingsNavigationState(
                    context = LocalContext.current,
                    listState = homeListState,
                    dynamicColor = false,
                    mobileDataFollowDefaultAudioQuality = true,
                    backgroundImageUri = null
                )
                val ownerScope = rememberCoroutineScope()
                SideEffect {
                    navigation = owner
                    scope = ownerScope
                }
                SettingsPageHost(owner.activePage, splitLayout = false, isolateAdvancedGlassTransitions = false) { page ->
                    if (page == null) {
                        LazyColumn(Modifier.fillMaxSize().testTag("settings-search-home"), state = homeListState) {
                            items(10) { index -> Text("设置分组 $index", Modifier.height(56.dp)) }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), state = owner.detailListStates.getValue(page)) {
                            item {
                                Button(onClick = owner::navigateBack, modifier = Modifier.testTag("settings-search-back")) {
                                    Text("返回设置")
                                }
                            }
                            items(40) { index -> Text("${page.name} $index", Modifier.height(56.dp)) }
                        }
                    }
                }
            }
        }
        repeat(4) { advanceFrame() }
        composeRule.runOnIdle { navigation.activePage = SettingsPage.General }
        finishTransition(PhoneFrameCount)
        composeRule.runOnIdle {
            scope.launch { navigation.detailListStates.getValue(SettingsPage.General).scrollToItem(6, 3) }
        }
        advanceFrame()
        composeRule.runOnIdle {
            navigation.searchQueryState.value = "storage"
            navigation.selectSearchResult(
                SettingsSearchEntry(
                    id = "page:Storage", page = SettingsPage.Storage, title = "Storage",
                    description = "Storage", tokens = listOf("storage"), targetId = "page:Storage", order = 0
                )
            )
        }
        finishTransition(PhoneFrameCount)
        composeRule.runOnIdle {
            assertEquals(SettingsPage.Storage, navigation.activePage)
            assertEquals("page:Storage", navigation.highlightTargetState.value)
            assertEquals(1, navigation.highlightPulseState.intValue)
            assertEquals(0, navigation.detailListStates.getValue(SettingsPage.Storage).firstVisibleItemIndex)
            assertEquals(6, navigation.detailListStates.getValue(SettingsPage.General).firstVisibleItemIndex)
            assertEquals(3, navigation.detailListStates.getValue(SettingsPage.General).firstVisibleItemScrollOffset)
        }
        restoration.emulateSavedInstanceStateRestore()
        repeat(4) { advanceFrame() }
        composeRule.runOnIdle {
            assertEquals(SettingsPage.Storage, navigation.activePage)
            assertEquals("storage", navigation.searchQueryState.value)
            assertEquals("page:Storage", navigation.highlightTargetState.value)
            assertEquals(6, navigation.detailListStates.getValue(SettingsPage.General).firstVisibleItemIndex)
            assertEquals(3, navigation.detailListStates.getValue(SettingsPage.General).firstVisibleItemScrollOffset)
        }
        composeRule.onNodeWithTag("settings-search-back").performClick()
        finishTransition(PhoneFrameCount)
        composeRule.onNodeWithTag("settings-search-home").assertExists()
        composeRule.runOnIdle { assertEquals(null, navigation.activePage) }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun nativeGlassMasksFollowTheActualFadeAndScaledBounds() {
        assertGlassFrames(AdvancedBlurQuality.High)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun localGlassMasksFollowTheActualFadeAndScaledBounds() {
        assertGlassFrames(AdvancedBlurQuality.Low)
    }

    private fun renderSinglePane(width: Float, height: Float, smallestWidth: Int) {
        composeRule.setContent {
            Fixture(width, height, smallestWidth) {
                selectedPage = remember { mutableStateOf<SettingsPage?>(SettingsPage.General) }
                CompositionLocalProvider(LocalAdvancedGlassSceneOpacity provides { ParentOpacity }) {
                    SettingsPageHost(selectedPage.value, splitLayout = false, isolateAdvancedGlassTransitions = false) { page ->
                        val opacity = LocalAdvancedGlassSceneOpacity.current
                        SideEffect { opacityGetters[page] = opacity }
                        Box(
                            Modifier.fillMaxSize().background(pageColor(page)),
                            contentAlignment = Alignment.Center
                        ) {
                            Button(
                                onClick = { clickedPages += page },
                                modifier = Modifier.align(if (page == SettingsPage.General) Alignment.CenterStart else Alignment.CenterEnd)
                                    .testTag(actionTag(page))
                            ) {
                                Text("${page?.name}")
                            }
                        }
                    }
                }
            }
        }
        repeat(4) { advanceFrame() }
    }

    private fun renderSplitPane() {
        composeRule.setContent {
            Fixture(960f, 640f, smallestWidth = 800) {
                selectedPage = remember { mutableStateOf<SettingsPage?>(SettingsPage.General) }
                val pages = listOf(SettingsPage.General, SettingsPage.Accounts, SettingsPage.Playback, SettingsPage.UsbExclusive)
                val states = pages.associateWith { page -> rememberLazyListState(initialIndex(page)) }
                val bars = pages.associateWith { page ->
                    rememberTopAppBarState(initialHeightOffset = if (page == SettingsPage.General) -24f else 0f)
                }
                SideEffect {
                    listStates = states
                    topBarStates = bars
                }
                val page = requireNotNull(selectedPage.value)
                MiuixSettingsResponsiveDetailScaffold(
                    title = page.name,
                    onBack = { selectedPage.value = page.backTargetPage() },
                    listState = states.getValue(page),
                    topAppBarState = bars.getValue(page),
                    splitLayout = true,
                    showSplitDetailBackButton = page.backTargetPage() != null,
                    selectedPage = page,
                    homeListState = rememberLazyListState(),
                    homeTopAppBarState = rememberTopAppBarState(),
                    homeTitle = { Text("设置导航") },
                    homeContent = { item { Text("唯一左侧栏") } },
                    detailListStates = states,
                    detailTopAppBarStates = bars,
                    detailContent = { scenePage ->
                        items(40, key = { "${scenePage.name}-$it" }) { index ->
                            Text(
                                "${scenePage.name} $index",
                                Modifier.fillMaxWidth().height(56.dp).testTag(rowTag(scenePage, index))
                            )
                        }
                    },
                    content = {}
                )
            }
        }
        repeat(4) { advanceFrame() }
    }

    private fun assertSinglePaneFrames(initial: SettingsPage, target: SettingsPage, screenshot: String) {
        val root = bounds(RootTag)
        composeRule.runOnIdle { selectedPage.value = target }
        var sawScale = false
        var sawFadePixel = false
        repeat(FrameCount) {
            advanceFrame()
            listOf(initial, target).forEach { page ->
                sceneBoundsOrNull(page)?.let { scene ->
                    assertCenteredScale(scene, root)
                    sawScale = sawScale || scene.width < root.width - 0.5f
                }
            }
            val alphas = composeRule.runOnIdle {
                listOf(initial, target).associateWith { opacityGetters[it]?.invoke()?.div(ParentOpacity) ?: 0f }
            }
            val incomingAlpha = alphas.getValue(target)
            val outgoingAlpha = if (sceneBoundsOrNull(initial) == null) 0f else alphas.getValue(initial)
            val incoming = pageColor(target)
            val outgoing = pageColor(initial)
            val pixel = assertColoredFrameHasVisibleContent("$initial->$target")
            assertEquals("新旧场景透明度未作用于实际画面",
                incoming.red * incomingAlpha + outgoing.red * outgoingAlpha * (1f - incomingAlpha), pixel.red, 0.07f)
            assertEquals("新旧场景透明度未作用于实际画面",
                incoming.green * incomingAlpha + outgoing.green * outgoingAlpha * (1f - incomingAlpha), pixel.green, 0.07f)
            assertEquals("新旧场景透明度未作用于实际画面",
                incoming.blue * incomingAlpha + outgoing.blue * outgoingAlpha * (1f - incomingAlpha), pixel.blue, 0.07f)
            if (!sawFadePixel && incomingAlpha in 0.2f..0.9f && outgoingAlpha > 0f) {
                capture(screenshot)
                sawFadePixel = true
            }
        }
        assertTrue("没有实际缩放: $initial->$target", sawScale)
        assertTrue("没有同时淡入淡出的实际画面: $initial->$target", sawFadePixel)
        assertRectEquals(root, requireNotNull(sceneBoundsOrNull(target)))
        assertTrue("退出页面仍在组合中", sceneBoundsOrNull(initial) == null)
        composeRule.runOnIdle { assertEquals(ParentOpacity, opacityGetters.getValue(target)(), 0.001f) }
    }

    private fun assertPhoneSlideFrames(initial: SettingsPage, target: SettingsPage, forward: Boolean) {
        val root = bounds(RootTag)
        composeRule.runOnIdle { selectedPage.value = target }
        var sawMovingPair = false
        repeat(PhoneFrameCount) { frame ->
            advanceFrame()
            val oldScene = transformedSceneBoundsOrNull(initial)
            val newScene = transformedSceneBoundsOrNull(target)
            listOfNotNull(oldScene, newScene).forEach { scene ->
                assertEquals("手机设置页面不应缩放宽度: $frame", root.width, scene.width, 1f)
                assertEquals("手机设置页面不应缩放高度: $frame", root.height, scene.height, 1f)
                assertEquals("手机设置页面不应纵向位移: $frame", root.top, scene.top, 1f)
            }
            if (oldScene != null && newScene != null) {
                if (forward) {
                    assertTrue("手机前进方向错误: $frame", oldScene.left <= root.left + 1f && newScene.left >= root.left - 1f)
                    assertEquals("手机横移露出间隙: $frame", oldScene.right, newScene.left, 2f)
                } else {
                    assertTrue("手机返回方向错误: $frame", oldScene.left >= root.left - 1f && newScene.left <= root.left + 1f)
                    assertEquals("手机横移露出间隙: $frame", newScene.right, oldScene.left, 2f)
                }
                sawMovingPair = sawMovingPair ||
                    (abs(oldScene.left - root.left) > 1f && abs(oldScene.left - root.left) < root.width - 1f)
            }
            composeRule.runOnIdle {
                opacityGetters.values.forEach { assertEquals("手机页面不应新增玻璃淡入淡出", ParentOpacity, it(), 0f) }
            }
            val pixels = composeRule.onNodeWithTag(RootTag).captureToImage().toPixelMap()
            listOf(pixels.width / 4, pixels.width * 3 / 4).forEach { x ->
                val pixel = pixels[x, pixels.height / 4]
                assertTrue("手机横向spring画面不应变暗: $frame/$pixel", max(pixel.red, max(pixel.green, pixel.blue)) >= 0.95f)
            }
        }
        assertTrue("未观察到手机真实横向滑动: $initial->$target", sawMovingPair)
        assertRectEquals(root, requireNotNull(transformedSceneBoundsOrNull(target)))
        assertTrue("手机spring退出页未结束", sceneBoundsOrNull(initial) == null)
    }

    private fun assertColoredFrameHasVisibleContent(stage: String): Color {
        val pixels = composeRule.onNodeWithTag(RootTag).captureToImage().toPixelMap()
        val pixel = pixels[pixels.width / 4, pixels.height / 4]
        assertTrue("设置切换出现空白或近空白帧: $stage/$pixel", pixel.red + pixel.green + pixel.blue >= 0.65f)
        return pixel
    }

    private fun assertGlassFrames(quality: AdvancedBlurQuality) {
        lateinit var registry: AdvancedGlassRegionRegistry
        var rootWindowLeft = 0f
        var rootWindowTop = 0f
        composeRule.setContent {
            Fixture(400f, 240f, smallestWidth = 800) {
                selectedPage = remember { mutableStateOf<SettingsPage?>(SettingsPage.General) }
                val background = rememberAdvancedGlassBackdrop()
                val content = rememberAdvancedGlassBackdrop()
                val controller = remember {
                    AdvancedGlassController(
                        sdkInt = Build.VERSION.SDK_INT, advancedBlurEnabled = true,
                        enhancedAdvancedBlurEnabled = true, backendReady = true,
                        enhancedAdvancedBlurRadiusDp = 24f, advancedBlurQuality = quality
                    )
                }
                AdvancedGlassHost(controller, background, content) {
                    val backdrops = requireNotNull(LocalAdvancedGlassBackdrops.current)
                    SideEffect { registry = backdrops.regionRegistry }
                    Box(Modifier.fillMaxSize().onGloballyPositioned {
                        rootWindowLeft = it.positionInWindow().x
                        rootWindowTop = it.positionInWindow().y
                    }) {
                        Row(Modifier.fillMaxSize().captureAdvancedGlassBackdrop(background)) {
                            repeat(40) { index ->
                                Box(Modifier.weight(1f).fillMaxHeight().background(if (index % 2 == 0) Color.Black else Color.White))
                            }
                        }
                        Box(Modifier.fillMaxSize().captureAdvancedGlassBackdrop(content)) {
                            SettingsPageHost(selectedPage.value, splitLayout = false, isolateAdvancedGlassTransitions = true) { page ->
                                val sceneOpacity = LocalAdvancedGlassSceneOpacity.current
                                SideEffect { opacityGetters[page] = sceneOpacity }
                                CompositionLocalProvider(LocalAdvancedGlassNavigationOwner provides page) {
                                    AdvancedGlassSurface(
                                        role = AdvancedGlassRole.SettingsSection,
                                        modifier = Modifier.fillMaxSize().testTag(glassTag(page)),
                                        fallbackColor = Color.Black, tintColor = Color.Transparent
                                    ) {}
                                }
                            }
                        }
                    }
                }
            }
        }
        repeat(6) { advanceFrame() }
        val initialPixels = composeRule.onNodeWithTag(RootTag).captureToImage().toPixelMap()
        val sampleX = (initialPixels.width * 0.3625f).roundToInt()
        val sampleY = initialPixels.height / 2
        val opaquePixel = initialPixels[sampleX, sampleY]
        assertTrue("真实玻璃没有模糊背景: quality=$quality color=$opaquePixel", opaquePixel.red in 0.15f..0.85f)
        var previousPixel = opaquePixel
        var sawOverlappingMasks = false
        var sawTranslucentMask = false
        fun assertGlassFrame(stage: String) {
            advanceFrame()
            val regions = composeRule.runOnIdle { resolveCurrentAdvancedGlassRegions(registry.regions) }
            assertTrue("切换中没有实际玻璃区域: $quality/$stage", regions.isNotEmpty())
            val fixtureBounds = bounds(RootTag)
            regions.forEach { region ->
                val page = requireNotNull(region.navigationOwner as? SettingsPage)
                val surface = bounds(glassTag(page))
                assertCenteredScale(surface, fixtureBounds)
                // 居中夹具带有根布局偏移，窗口坐标需换算到测试根坐标
                assertRectEquals(
                    surface,
                    Rect(
                        region.boundsInWindow.left - rootWindowLeft + fixtureBounds.left,
                        region.boundsInWindow.top - rootWindowTop + fixtureBounds.top,
                        region.boundsInWindow.right - rootWindowLeft + fixtureBounds.left,
                        region.boundsInWindow.bottom - rootWindowTop + fixtureBounds.top
                    )
                )
                assertEquals(AdvancedGlassRole.SettingsSection, region.role)
                composeRule.runOnIdle {
                    assertEquals("玻璃遮罩透明度未跟随当前帧: $quality/$stage/$page",
                        opacityGetters.getValue(page)(), region.opacity, 0.001f)
                }
            }
            val coverage = regions.maxOf { it.opacity }
            assertTrue("玻璃交接出现透明空帧: $quality/$stage/$coverage", coverage >= 0.65f)
            val pixel = composeRule.onNodeWithTag(RootTag).captureToImage().toPixelMap()[sampleX, sampleY]
            // 两个后端都取重叠区域的最大透明度，采样点的未模糊背景为黑色
            assertEquals("实际模糊像素未随当前覆盖透明度连续变化: $quality/$stage",
                opaquePixel.red * coverage, pixel.red, 0.08f)
            assertTrue("实际模糊区域在交接中消失: $quality/$stage/$pixel", pixel.red > opaquePixel.red * 0.6f)
            assertTrue("实际画面出现突闪: $quality/$stage/$previousPixel->$pixel", abs(pixel.red - previousPixel.red) <= 0.2f)
            previousPixel = pixel
            val overlapping = regions.count { it.opacity > 0f } >= 2
            if (!sawOverlappingMasks && overlapping) capture("settings-glass-${quality.name}-overlap")
            sawOverlappingMasks = sawOverlappingMasks || overlapping
            sawTranslucentMask = sawTranslucentMask || regions.any { it.opacity in 0.1f..0.9f }
        }

        composeRule.runOnIdle { selectedPage.value = SettingsPage.Accounts }
        repeat(FrameCount) { frame -> assertGlassFrame("General->Accounts/$frame") }
        listOf(SettingsPage.General, SettingsPage.Playback, SettingsPage.Accounts).forEach { target ->
            composeRule.runOnIdle { selectedPage.value = target }
            repeat(3) { frame -> assertGlassFrame("rapid->$target/$frame") }
        }
        repeat(FrameCount * 2) { frame -> assertGlassFrame("rapid settled/$frame") }
        assertTrue("未观察到同时可见的新旧遮罩: $quality", sawOverlappingMasks)
        assertTrue("未观察到实际透明度动画: $quality", sawTranslucentMask)
        composeRule.runOnIdle {
            val finalRegion = resolveCurrentAdvancedGlassRegions(registry.regions).single()
            assertEquals(SettingsPage.Accounts, finalRegion.navigationOwner)
            assertEquals(1f, finalRegion.opacity, 0.001f)
        }
        assertTrue("快速切页后旧玻璃节点残留", sceneBoundsOrNull(SettingsPage.General) == null)
        assertTrue("快速切页后旧玻璃节点残留", sceneBoundsOrNull(SettingsPage.Playback) == null)
        val restoredPixel = composeRule.onNodeWithTag(RootTag).captureToImage().toPixelMap()[sampleX, sampleY]
        assertEquals("动画后玻璃未恢复", opaquePixel.red, restoredPixel.red, 0.06f)
        capture("settings-glass-${quality.name}-settled")
    }

    @Composable
    private fun Fixture(width: Float, height: Float, smallestWidth: Int, content: @Composable () -> Unit) {
        MaterialTheme {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val hostDensity = LocalDensity.current
                val scale = minOf(maxWidth.value / width, maxHeight.value / height)
                val density = Density(hostDensity.density * scale, fontScale = 1f)
                val configuration = Configuration(LocalConfiguration.current).apply {
                    smallestScreenWidthDp = smallestWidth
                    screenWidthDp = width.toInt()
                    screenHeightDp = height.toInt()
                }
                CompositionLocalProvider(LocalDensity provides density, LocalConfiguration provides configuration) {
                    Box(Modifier.requiredSize(width.dp, height.dp).background(Color.Black).testTag(RootTag)) {
                        content()
                    }
                }
            }
        }
    }

    private fun sceneBoundsOrNull(page: SettingsPage?): Rect? = composeRule
        .onAllNodesWithTag(settingsPageTransitionSceneTag(page), useUnmergedTree = true)
        .fetchSemanticsNodes().singleOrNull()?.boundsInRoot

    private fun transformedSceneBoundsOrNull(page: SettingsPage?): Rect? {
        val node = composeRule.onAllNodesWithTag(settingsPageTransitionSceneTag(page), useUnmergedTree = true)
            .fetchSemanticsNodes().singleOrNull() ?: return null
        val coordinates = node.layoutInfo.coordinates
        val start = coordinates.localToRoot(Offset.Zero)
        val end = coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        return Rect(start, end)
    }

    private fun clickUnmergedAction(page: SettingsPage) {
        val action = bounds(actionTag(page))
        val root = bounds(RootTag)
        composeRule.onNodeWithTag(RootTag).performTouchInput { click(action.center - root.topLeft) }
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun assertCenteredScale(actual: Rect, root: Rect) {
        assertEquals("页面发生横向位移", root.center.x, actual.center.x, 1f)
        assertEquals("页面发生纵向位移", root.center.y, actual.center.y, 1f)
        assertTrue("缩放幅度不符合轻微缩放: $actual/$root", actual.width >= root.width * 0.979f - 1f)
        assertTrue("页面越过容器", actual.width <= root.width + 1f && actual.height <= root.height + 1f)
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f)
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.right, actual.right, 1f)
        assertEquals(expected.bottom, actual.bottom, 1f)
    }

    private fun advanceFrame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun finishTransition(frameCount: Int = FrameCount) {
        repeat(frameCount) { advanceFrame() }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        val image = composeRule.onNodeWithTag(RootTag).captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png")
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun pageColor(page: SettingsPage?): Color = when (page) {
        SettingsPage.General -> Color.Red
        SettingsPage.Accounts -> Color.Blue
        else -> Color.Green
    }

    private fun initialIndex(page: SettingsPage): Int = when (page) {
        SettingsPage.General -> 5
        SettingsPage.Accounts -> 2
        SettingsPage.Playback -> 7
        else -> 3
    }

    private fun actionTag(page: SettingsPage?) = "settings-action-${page?.name}"
    private fun rowTag(page: SettingsPage, index: Int) = "settings-row-${page.name}-$index"
    private fun glassTag(page: SettingsPage?) = "settings-glass-${page?.name}"

    private companion object {
        const val RootTag = "settings-transition-root"
        const val FrameCount = 32
        const val PhoneFrameCount = 96
        const val ParentOpacity = 0.6f
    }
}
