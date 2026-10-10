package moe.ouom.neriplayer.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScalePage
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsFontSettings
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsLyricContent
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsSheet
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsSheetNavigation
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingPhoneLandscape
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverActionToolbar
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverToolbarActions
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverToolbarLayoutSpec
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverToolbarStatus
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingLyricsToolbarAction
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.shouldAdjustNowPlayingLyricsBehavior
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(AndroidJUnit4::class)
class NowPlayingLyricsToolbarBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val viewModelStore = ViewModelStore()
    private lateinit var viewModel: NowPlayingViewModel
    private val currentPage = mutableIntStateOf(CoverPage)
    private val sheetVisible = mutableStateOf(false)
    private var adjustmentRequests = 0
    private var pageSwitches = 0
    private var sheetDismissals = 0

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(viewModelStore, ViewModelProvider.NewInstanceFactory())[
                NowPlayingViewModel::class.java
            ]
        }
    }

    @After
    fun clearFixture() {
        instrumentation.runOnMainSync {
            sheetVisible.value = false
            viewModelStore.clear()
        }
    }

    @Test
    fun tabletCoverActionOpensLyricBehaviorAndDoneClosesTheSheet() {
        render(wideLandscape = true, lyricsShowing = false)
        openLyricBehavior()
        assertLyricBehaviorOptions(expectedPage = CoverPage)

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_done))
            .assertIsDisplayed().performClick()
        assertSheetDismissed(expectedPage = CoverPage)
    }

    @Test
    fun tabletWideLyricsActionOpensLyricBehaviorAndBackClosesTheSheet() {
        render(wideLandscape = true, lyricsShowing = true)
        openLyricBehavior()
        assertLyricBehaviorOptions(expectedPage = LyricsPage)

        Espresso.pressBack()
        assertSheetDismissed(expectedPage = LyricsPage)
    }

    @Test
    fun narrowTabletLandscapeToolbarUsesAdjustmentAndDoneKeepsTheCoverSelected() {
        render(
            wideLandscape = false,
            lyricsShowing = false,
            isLandscape = true,
            smallestScreenWidthDp = 600,
            width = 400.dp,
            height = 300.dp
        )
        openLyricBehavior()
        assertLyricBehaviorOptions(expectedPage = CoverPage)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_done))
            .assertIsDisplayed().performClick()
        assertSheetDismissed(expectedPage = CoverPage)
    }

    @Test
    fun narrowTabletLandscapeToolbarUsesAdjustmentAndBackKeepsLyricsSelected() {
        render(
            wideLandscape = false,
            lyricsShowing = true,
            isLandscape = true,
            smallestScreenWidthDp = 600,
            width = 400.dp,
            height = 300.dp
        )
        openLyricBehavior()
        assertLyricBehaviorOptions(expectedPage = LyricsPage)
        Espresso.pressBack()
        assertSheetDismissed(expectedPage = LyricsPage)
    }

    @Test
    fun portraitLyricsActionSwitchesPagesWithoutOpeningTheSheet() {
        render(wideLandscape = false, lyricsShowing = false)
        val lyricsDescription = context.getString(CoreCommonR.string.lyrics_title)
        composeRule.onNodeWithContentDescription(lyricsDescription)
            .assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertPortraitPage(expectedPage = LyricsPage, expectedSwitches = 1)

        composeRule.onNodeWithContentDescription(lyricsDescription)
            .assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertPortraitPage(expectedPage = CoverPage, expectedSwitches = 2)
    }

    private fun render(
        wideLandscape: Boolean,
        lyricsShowing: Boolean,
        isLandscape: Boolean = wideLandscape,
        smallestScreenWidthDp: Int = if (wideLandscape) 800 else 360,
        width: Dp = if (wideLandscape) 1280.dp else 360.dp,
        height: Dp = if (wideLandscape) 800.dp else 840.dp
    ) {
        val lyricsAdjustBehavior = shouldAdjustNowPlayingLyricsBehavior(
            isLandscape,
            isNowPlayingPhoneLandscape(isLandscape, smallestScreenWidthDp)
        )
        currentPage.intValue = if (lyricsShowing) LyricsPage else CoverPage
        val song = SongItem(
            id = -493_101L,
            name = "歌词工具栏测试歌曲",
            artist = "合成艺术家",
            album = "合成专辑",
            albumId = -493_101L,
            durationMs = 60_000L,
            coverUrl = null
        )
        val lyricContent = MoreOptionsLyricContent(
            lyrics = listOf(LyricEntry("合成原文", 0L, 60_000L)),
            translatedLyrics = listOf(LyricEntry("合成译文", 0L, 60_000L)),
            romanizedLyrics = listOf(LyricEntry("gou sei gen bun", 0L, 60_000L)),
            hasTranslation = true,
            hasPhonetic = true
        )
        val actions = NowPlayingCoverToolbarActions(
            onQueue = {},
            onSleepTimer = {},
            onVolume = {},
            onLyrics = resolveNowPlayingLyricsToolbarAction(
                lyricsAdjustBehavior = lyricsAdjustBehavior,
                onAdjust = {
                    adjustmentRequests++
                    sheetVisible.value = true
                },
                onSwitchPage = {
                    pageSwitches++
                    currentPage.intValue = if (currentPage.intValue == CoverPage) LyricsPage else CoverPage
                }
            ),
            onAddToPlaylist = {}
        )
        composeRule.setContent {
            MaterialTheme {
                FittedTestViewport(
                    width = width,
                    height = height,
                    layoutOnlyTextInput = true
                ) {
                    Box(Modifier.fillMaxSize()) {
                        SharedTransitionLayout {
                            val sharedTransitionScope = this
                            AnimatedContent(
                                targetState = currentPage.intValue,
                                label = "lyrics_toolbar_fixture_page"
                            ) { page ->
                                NowPlayingCoverActionToolbar(
                                    spec = NowPlayingCoverToolbarLayoutSpec(
                                        wideLandscape = wideLandscape,
                                        compactPortrait = false,
                                        docked = true,
                                        iconSize = 24.dp,
                                        minimumTouchTarget = 48.dp,
                                        lyricsAdjustBehavior = lyricsAdjustBehavior
                                    ),
                                    status = NowPlayingCoverToolbarStatus(
                                        sleepTimerActive = false,
                                        lyricsAvailable = true,
                                        lyricsShowing = page == LyricsPage,
                                        activeColor = Color.Magenta
                                    ),
                                    actions = actions,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = this
                                )
                            }
                        }
                        if (sheetVisible.value) {
                            MoreOptionsSheet(
                                viewModel = viewModel,
                                originalSong = song,
                                queue = listOf(song),
                                lyricContent = lyricContent,
                                navigation = MoreOptionsSheetNavigation(
                                    onDismiss = {
                                        sheetDismissals++
                                        sheetVisible.value = false
                                    },
                                    onShowSongDetails = { error("歌词行为入口不能打开歌曲详情") },
                                    onEnterAlbum = { error("歌词行为入口不能进入专辑") },
                                    onNavigateUp = { error("歌词行为入口不能退出播放页") },
                                    onShowQualitySwitch = { error("歌词行为入口不能切换音质") },
                                    startWithLyricBehavior = true
                                ),
                                snackbarHostState = remember { SnackbarHostState() },
                                fontSettings = MoreOptionsFontSettings(
                                    page = if (currentPage.intValue == LyricsPage) {
                                        LyricFontScalePage.LYRICS
                                    } else {
                                        LyricFontScalePage.COVER
                                    },
                                    scales = LyricFontScales(1f, 1f, 1f, 1f),
                                    onChange = { _, _ -> error("此测试不能保存歌词字号") }
                                ),
                                biliClient = AppContainer.biliClient,
                                currentPlaybackAudioInfo = null,
                                offlineMode = true
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun openLyricBehavior() {
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.lyrics_title))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.lyrics_adjust_behavior))
            .assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.waitUntil(UiTimeoutMs) {
            composeRule.onAllNodesWithText(context.getString(CoreCommonR.string.lyrics_adjust_behavior))
                .fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()
    }

    private fun assertLyricBehaviorOptions(expectedPage: Int) {
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.lyrics_adjust_behavior)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.settings_show_lyric_translation)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.lyrics_translation_use_phonetic)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(expectedPage, currentPage.intValue)
            assertEquals(1, adjustmentRequests)
            assertEquals(0, pageSwitches)
            assertEquals(0, sheetDismissals)
        }
    }

    private fun assertSheetDismissed(expectedPage: Int) {
        composeRule.waitUntil(UiTimeoutMs) { !sheetVisible.value && sheetDismissals == 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.lyrics_adjust_behavior)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.lyrics_adjust_behavior))
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(expectedPage, currentPage.intValue)
            assertEquals(1, adjustmentRequests)
            assertEquals(0, pageSwitches)
            assertEquals(1, sheetDismissals)
        }
    }

    private fun assertPortraitPage(expectedPage: Int, expectedSwitches: Int) {
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.lyrics_adjust_behavior)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.lyrics_adjust_behavior))
            .assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(expectedPage, currentPage.intValue)
            assertEquals(expectedSwitches, pageSwitches)
            assertEquals(0, adjustmentRequests)
            assertEquals(0, sheetDismissals)
            assertFalse(sheetVisible.value)
        }
    }

    private companion object {
        const val CoverPage = 0
        const val LyricsPage = 1
        const val UiTimeoutMs = 10_000L
    }
}
