package moe.ouom.neriplayer.ui

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.activity.BackEventCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.playback.visual.AppNowPlayingOverlay
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayBackground
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayCover
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingScreen
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingPageBackNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private var savedPreferences: Preferences? = null
    private var savedCleanMotion = false
    private var visible by mutableStateOf(false)
    private var lyricsPage by mutableStateOf(false)
    private var tabletPortrait by mutableStateOf(false)
    private var closeRequests = 0
    private val queue = MutableStateFlow<List<SongItem>>(emptyList())

    @Before
    fun prepareSettings() {
        savedCleanMotion = NeriMotionStyle.clean
        assumeComposeHostAvailable()
        val context = composeRule.activity.applicationContext
        savedPreferences = runBlocking { context.dataStore.data.first() }
        runBlocking {
            SettingsRepository(context).apply {
                setNowPlayingImmersiveMode(false)
                setNowPlayingDynamicBackgroundEnabled(false)
                setNowPlayingCoverBlurBackgroundEnabled(false)
                setUiDensityScale(1f)
            }
        }
        composeRule.mainClock.autoAdvance = false
    }

    @After
    fun restoreSettings() {
        try {
            try {
                composeRule.runOnIdle { visible = false }
                advance(400L)
            } finally {
                savedPreferences?.let { preferences ->
                    val context = composeRule.activity.applicationContext
                    runBlocking {
                        context.dataStore.updateData { preferences }
                        assertEquals(preferences, context.dataStore.data.first())
                    }
                }
            }
        } finally {
            NeriMotionStyle.clean = savedCleanMotion
        }
    }

    @Test
    fun materialTabletClosesWithOneSystemBackRegardlessOfRetainedLyrics() =
        assertTabletCloses(UiStyle.Material, topBar = false)

    @Test
    fun cleanTabletClosesWithOneSystemBackRegardlessOfRetainedLyrics() =
        assertTabletCloses(UiStyle.Clean, topBar = false)

    @Test
    fun materialTabletClosesWithOneTopBarBackRegardlessOfRetainedLyrics() =
        assertTabletCloses(UiStyle.Material, topBar = true)

    @Test
    fun cleanTabletClosesWithOneTopBarBackRegardlessOfRetainedLyrics() =
        assertTabletCloses(UiStyle.Clean, topBar = true)

    @Test
    fun materialCompactLandscapeClosesWithOneEdgeBackWithPreview() =
        assertLandscapeEdgeBack(UiStyle.Material, tablet = false, previewEnabled = true)

    @Test
    fun materialCompactLandscapeClosesWithOneEdgeBackWithoutPreview() =
        assertLandscapeEdgeBack(UiStyle.Material, tablet = false, previewEnabled = false)

    @Test
    fun cleanCompactLandscapeClosesWithOneEdgeBackWithPreview() =
        assertLandscapeEdgeBack(UiStyle.Clean, tablet = false, previewEnabled = true)

    @Test
    fun cleanCompactLandscapeClosesWithOneEdgeBackWithoutPreview() =
        assertLandscapeEdgeBack(UiStyle.Clean, tablet = false, previewEnabled = false)

    @Test
    fun materialTabletLandscapeClosesWithOneEdgeBackWithPreview() =
        assertLandscapeEdgeBack(UiStyle.Material, tablet = true, previewEnabled = true)

    @Test
    fun materialTabletLandscapeClosesWithOneEdgeBackWithoutPreview() =
        assertLandscapeEdgeBack(UiStyle.Material, tablet = true, previewEnabled = false)

    @Test
    fun cleanTabletLandscapeClosesWithOneEdgeBackWithPreview() =
        assertLandscapeEdgeBack(UiStyle.Clean, tablet = true, previewEnabled = true)

    @Test
    fun cleanTabletLandscapeClosesWithOneEdgeBackWithoutPreview() =
        assertLandscapeEdgeBack(UiStyle.Clean, tablet = true, previewEnabled = false)

    private fun assertLandscapeEdgeBack(style: UiStyle, tablet: Boolean, previewEnabled: Boolean) {
        assumeTrue(
            "需要系统手势导航",
            Settings.Secure.getInt(composeRule.activity.contentResolver, "navigation_mode", -1) == 2
        )
        render(style, tablet, retainedLyricsPage = true, previewEnabled, compactLandscape = !tablet)
        composeRule.onNodeWithTag("nowPlayingWideLayout", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("lyricsScreen", useUnmergedTree = true).assertDoesNotExist()
        val (width, height) = composeRule.runOnIdle {
            composeRule.activity.window.decorView.let { it.width to it.height }
        }
        composeRule.mainClock.autoAdvance = true
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(
                "input swipe ${width - 1} ${height / 2} ${width / 2} ${height / 2} 350"
            )
        ).use { it.readBytes() }
        composeRule.waitUntil(timeoutMillis = 5_000) { !visible }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, closeRequests) }
    }

    @Test
    fun materialTabletRotationDuringLyricsBackStillClosesWithOneLandscapeBack() =
        assertRotationDuringLyricsBack(UiStyle.Material, previewEnabled = true)

    @Test
    fun cleanTabletRotationDuringLyricsBackStillClosesWithOneLandscapeBack() =
        assertRotationDuringLyricsBack(UiStyle.Clean, previewEnabled = true)

    @Test
    fun tabletRotationDuringLyricsBackWithPreviewDisabledClosesWithOneLandscapeBack() =
        assertRotationDuringLyricsBack(UiStyle.Material, previewEnabled = false)

    @Test
    fun cancellingLyricsBackAfterTabletRotationKeepsThePlayerUntilTheNextBack() =
        assertRotationDuringLyricsBack(UiStyle.Material, previewEnabled = true, cancelAfterRotation = true)

    private fun assertRotationDuringLyricsBack(
        style: UiStyle,
        previewEnabled: Boolean,
        cancelAfterRotation: Boolean = false
    ) {
        tabletPortrait = true
        render(style, tablet = true, retainedLyricsPage = true, previewEnabled = previewEnabled)
        composeRule.onNodeWithTag("lyricsScreen", useUnmergedTree = true).assertIsDisplayed()
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.5f)) }
        advance(32L)

        composeRule.runOnIdle { tabletPortrait = false }
        advance(400L)
        composeRule.onAllNodesWithTag("nowPlayingWideLayout", useUnmergedTree = true)
            .onFirst().assertIsDisplayed()

        if (cancelAfterRotation) {
            composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
            advance(400L)
            composeRule.runOnIdle {
                assertTrue(visible)
                assertEquals(0, closeRequests)
            }
        }
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.7f)) }
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        advance(400L)

        composeRule.runOnIdle {
            assertFalse("切到横屏后第一次返回就应退出播放页", visible)
            assertEquals(1, closeRequests)
        }
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun backEvent(progress: Float, swipeEdge: Int = BackEventCompat.EDGE_RIGHT) =
        BackEventCompat(0f, 0f, progress, swipeEdge)

    @Test
    fun materialPhoneLyricsSystemBackReturnsToCoverBeforeClosing() =
        assertPhoneLyricsBack(UiStyle.Material)

    @Test
    fun cleanPhoneLyricsSystemBackReturnsToCoverBeforeClosing() =
        assertPhoneLyricsBack(UiStyle.Clean)

    @Test
    fun materialPhoneLyricsSystemBackWithoutPreviewReturnsToCoverBeforeClosing() =
        assertPhoneLyricsBack(UiStyle.Material, previewEnabled = false)

    @Test
    fun cleanPhoneLyricsSystemBackWithoutPreviewReturnsToCoverBeforeClosing() =
        assertPhoneLyricsBack(UiStyle.Clean, previewEnabled = false)

    @Test
    fun materialPhoneLyricsPredictiveCancelRestoresBothImmersiveModes() =
        assertPhoneLyricsPredictiveCancel(UiStyle.Material)

    @Test
    fun cleanPhoneLyricsPredictiveCancelRestoresBothImmersiveModes() =
        assertPhoneLyricsPredictiveCancel(UiStyle.Clean)

    private fun assertTabletCloses(style: UiStyle, topBar: Boolean) {
        render(style, tablet = true, retainedLyricsPage = false)
        listOf(false, true).forEachIndexed { index, retainedLyricsPage ->
            if (index > 0) {
                composeRule.runOnIdle {
                    lyricsPage = retainedLyricsPage
                    visible = true
                }
                advance(400L)
            }
            composeRule.onNodeWithTag("nowPlayingWideLayout", useUnmergedTree = true).assertIsDisplayed()
            if (topBar) {
                composeRule.onNodeWithTag("nowPlayingBackButton", useUnmergedTree = true)
                    .assertIsDisplayed().performTouchInput { click() }
            } else {
                pressSystemBack()
            }
            advance(400L)

            composeRule.runOnIdle {
                assertFalse("平板单次返回必须退出整个播放页: retainedLyrics=$retainedLyricsPage", visible)
                assertEquals("返回不能先消费到隐藏的歌词页状态", index + 1, closeRequests)
            }
            composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun assertPhoneLyricsBack(style: UiStyle, previewEnabled: Boolean = true) {
        render(style, tablet = false, retainedLyricsPage = true, previewEnabled = previewEnabled)
        composeRule.onNodeWithTag("lyricsScreen", useUnmergedTree = true).assertIsDisplayed()

        if (!previewEnabled) {
            val dispatcher = composeRule.activity.onBackPressedDispatcher
            composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
            composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.7f)) }
            advance(80L)
            composeRule.runOnIdle {
                assertTrue("关闭预览后拖动不能提交歌词导航: $style", lyricsPage)
                assertTrue(visible)
                assertEquals(0, closeRequests)
            }
            composeRule.onNodeWithTag("nowPlayingBackButton", useUnmergedTree = true).assertDoesNotExist()
            composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
            advance(80L)
            composeRule.runOnIdle {
                assertTrue("关闭预览后取消不能提交歌词导航: $style", lyricsPage)
                assertTrue(visible)
                assertEquals(0, closeRequests)
            }
            composeRule.onNodeWithTag("lyricsScreen", useUnmergedTree = true).assertIsDisplayed()
        }

        val backStarted = composeRule.mainClock.currentTime
        pressSystemBack()
        advance(400L)

        composeRule.runOnIdle {
            assertTrue("手机歌词页第一次返回仍应保留正在播放页", visible)
            assertFalse(lyricsPage)
            assertEquals(0, closeRequests)
        }
        // 共享元素使用默认弹簧，出场内容的卸载会晚于歌词页自身的 300ms 淡出
        val deadline = backStarted + 1_500L
        while (composeRule.mainClock.currentTime < deadline &&
            composeRule.onAllNodesWithTag("lyricsScreen", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            advance(16L)
        }
        Log.i("PageBackNavigationTest", "phone=$style lyricsExitElapsed=" +
            (composeRule.mainClock.currentTime - backStarted))
        composeRule.onNodeWithTag("lyricsScreen", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertIsDisplayed()

        pressSystemBack()
        advance(400L)
        composeRule.runOnIdle {
            assertFalse(visible)
            assertEquals(1, closeRequests)
        }
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun assertPhoneLyricsPredictiveCancel(style: UiStyle) {
        render(style, tablet = false, retainedLyricsPage = true)
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        val settings = SettingsRepository(composeRule.activity.applicationContext)
        listOf(false, true).forEachIndexed { index, immersive ->
            runBlocking {
                settings.setNowPlayingImmersiveMode(immersive)
                settings.nowPlayingImmersiveModeFlow.first { it == immersive }
            }
            composeRule.runOnIdle {
                visible = true
                lyricsPage = true
            }
            advance(600L)
            composeRule.onNodeWithTag("lyricsScreen", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("nowPlayingBackButton", useUnmergedTree = true).assertDoesNotExist()

            composeRule.runOnIdle {
                dispatcher.dispatchOnBackStarted(backEvent(0f, swipeEdge = BackEventCompat.EDGE_LEFT))
            }
            listOf(0.2f, 0.7f, 0.15f).forEach { progress ->
                composeRule.runOnIdle {
                    dispatcher.dispatchOnBackProgressed(backEvent(progress, swipeEdge = BackEventCompat.EDGE_LEFT))
                }
                advance(80L)
                composeRule.runOnIdle {
                    assertTrue("预览与回拖不能提交歌词导航: $style immersive=$immersive", lyricsPage)
                    assertTrue(visible)
                    assertEquals(index, closeRequests)
                }
            }
            // 目标页实际加入转场，避免把没有进入歌词预览的取消当作通过
            composeRule.onNodeWithTag("nowPlayingBackButton", useUnmergedTree = true).assertExists()

            composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
            advance(600L)
            composeRule.runOnIdle {
                assertTrue("取消后仍应显示歌词: $style immersive=$immersive", lyricsPage)
                assertTrue(visible)
                assertEquals(index, closeRequests)
            }
            composeRule.onNodeWithTag("lyricsScreen", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("nowPlayingBackButton", useUnmergedTree = true).assertDoesNotExist()

            pressSystemBack()
            advance(1_500L)
            composeRule.runOnIdle {
                assertFalse("取消后的普通返回应先回封面", lyricsPage)
                assertTrue(visible)
                assertEquals(index, closeRequests)
            }
            composeRule.onNodeWithTag("lyricsScreen", useUnmergedTree = true).assertDoesNotExist()

            pressSystemBack()
            advance(400L)
            composeRule.runOnIdle {
                assertFalse(visible)
                assertEquals(index + 1, closeRequests)
            }
        }
    }

    private fun render(
        style: UiStyle,
        tablet: Boolean,
        retainedLyricsPage: Boolean,
        previewEnabled: Boolean = true,
        compactLandscape: Boolean = false
    ) {
        lyricsPage = retainedLyricsPage
        composeRule.setContent {
            val width = if (tablet) { if (tabletPortrait) 800 else 1280 } else if (compactLandscape) 1280 else 360
            val height = if (tablet && tabletPortrait) 1280 else 800
            val actualConfiguration = LocalConfiguration.current
            val configuration = remember(actualConfiguration, tablet, tabletPortrait) {
                Configuration(actualConfiguration).apply {
                    screenWidthDp = width
                    screenHeightDp = height
                    smallestScreenWidthDp = if (tablet) 800 else 360
                    orientation = if ((tablet && !tabletPortrait) || compactLandscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    uiMode = Configuration.UI_MODE_TYPE_NORMAL
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalPredictiveBackPreviewEnabled provides previewEnabled
            ) {
                FittedTestViewport(width.dp, height.dp) {
                    val density = LocalDensity.current
                    val actualWindow = LocalWindowInfo.current
                    val size = with(density) { IntSize(width.dp.roundToPx(), height.dp.roundToPx()) }
                    val window = remember(actualWindow, size) {
                        object : WindowInfo by actualWindow {
                            override val containerSize = size
                            override val containerDpSize = DpSize(width.dp, height.dp)
                        }
                    }
                    CompositionLocalProvider(LocalWindowInfo provides window) {
                        AppNowPlayingOverlay(
                            visible = visible,
                            cover = NowPlayingOverlayCover(null, null, null, 0),
                            queueFlow = queue,
                            theme = NowPlayingOverlayTheme(
                                false, null, "6750A4", PaletteStyle.TonalSpot,
                                ColorSpec.SpecVersion.Default, uiStyle = style
                            ),
                            background = NowPlayingOverlayBackground(false, 0f, 0f, false, true),
                            onVisibilityChanged = {},
                            onClose = ::closePlayer
                        ) {
                            Box(Modifier.fillMaxSize().testTag(PlayerContent)) {
                                NowPlayingScreen(
                                    onNavigateUp = ::closePlayer,
                                    showLyricsScreen = lyricsPage,
                                    onShowLyricsScreenChange = { lyricsPage = it },
                                    onEnterAlbum = {},
                                    lyricBlurEnabled = false,
                                    lyricBlurAmount = 0f,
                                    lyricFontScales = LyricFontScales(1f, 1f, 1f, 1f),
                                    onLyricFontScaleChange = { _, _ -> },
                                    advancedLyricsEnabled = false,
                                    offlineMode = true
                                )
                            }
                        }
                    }
                }
            }
        }
        advance(32L)
        composeRule.runOnIdle { visible = true }
        advance(400L)
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun closePlayer() {
        closeRequests += 1
        visible = false
    }

    private fun pressSystemBack() {
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun advance(milliseconds: Long) {
        val end = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < end) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private companion object {
        const val PlayerContent = "page-back-player-content"
    }
}
