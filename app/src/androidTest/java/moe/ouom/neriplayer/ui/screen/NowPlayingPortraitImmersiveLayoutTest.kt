package moe.ouom.neriplayer.ui.screen

import android.content.res.Configuration
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingScreen
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class NowPlayingPortraitImmersiveLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private var savedPreferences: Preferences? = null
    private var savedCleanMotion = false
    private var visible by mutableStateOf(true)
    private var backCallbacks = 0
    private var audioFixture: File? = null

    @Before
    fun prepareSettings() {
        savedCleanMotion = NeriMotionStyle.clean
        assumeComposeHostAvailable()
        val context = composeRule.activity.applicationContext
        savedPreferences = runBlocking { context.dataStore.data.first() }
        runBlocking {
            SettingsRepository(context).apply {
                setNowPlayingImmersiveMode(true)
                setNowPlayingToolbarDockEnabled(true)
                setUiDensityScale(1f)
            }
        }
        val audio = File.createTempFile("immersive-controls-", ".wav", context.cacheDir)
        audioFixture = audio
        val pcm = ByteArray(8_000 * 30 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size)
        }.array()
        audio.outputStream().use { it.write(header); it.write(pcm) }
        composeRule.runOnUiThread {
            PlayerManager.release()
            PlayerManager.initialize(composeRule.activity.application)
            PlayerManager.playPlaylist(listOf(SongItem(
                id = -538_181L,
                name = "The Big Black",
                artist = "The Quick Brown Fox",
                album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
                albumId = 0L,
                durationMs = 30_000L,
                coverUrl = null,
                mediaUri = Uri.fromFile(audio).toString(),
                localFilePath = audio.absolutePath
            )), startIndex = 0)
        }
        composeRule.waitUntil(10_000) { PlayerManager.currentSongFlow.value?.id == -538_181L }
        composeRule.runOnUiThread { PlayerManager.pause() }
    }

    @After
    fun restoreSettings() {
        try {
            try {
                composeRule.runOnIdle { visible = false }
                composeRule.waitForIdle()
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
            composeRule.runOnUiThread { PlayerManager.release() }
            audioFixture?.let { check(it.delete()) }
            NeriMotionStyle.clean = savedCleanMotion
        }
    }

    @Test
    fun shortPortraitImmersiveCoverKeepsPlaybackAndToolbarActionsReachable() =
        assertActionsReachable(height = 640, fontScale = 1f, placement = NowPlayingControlPlacement.LOWER)

    @Test
    fun shortPortraitImmersiveCoverKeepsBottomProgressAndLargeTextActionsReachable() =
        assertActionsReachable(height = 640, fontScale = 1.3f,
            placement = NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)

    @Test
    fun tallPortraitImmersiveCoverKeepsItsActionsReachable() =
        assertActionsReachable(height = 800, fontScale = 1f, placement = NowPlayingControlPlacement.LOWER)

    @Test
    fun compactPortraitImmersiveCoverKeepsLargeControlsReachable() =
        assertActionsReachable(height = 480, fontScale = 1.3f, placement = NowPlayingControlPlacement.LOWER,
            style = UiStyle.Material, toggleAtRuntime = true)

    @Test
    fun largeTextImmersiveCoverKeepsBottomProgressAndControlsReachable() =
        assertActionsReachable(height = 568, fontScale = 2f,
            placement = NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)

    @Test
    fun cleanImmersiveModeCanBeEnabledWithALoadedSongOnAShortPortraitWindow() =
        assertActionsReachable(height = 560, fontScale = 1.3f, placement = NowPlayingControlPlacement.LOWER,
            toggleAtRuntime = true)

    @Test
    fun cleanImmersiveModeLongPressTogglesWithoutSnackbarOrChangingPlayback() {
        val settings = SettingsRepository(composeRule.activity.applicationContext)
        runBlocking { settings.setNowPlayingImmersiveMode(false) }
        render(height = 800, fontScale = 1f, style = UiStyle.Clean)
        val transport = hasContentDescription(label(CoreCommonR.string.player_play)) or
            hasContentDescription(label(CoreCommonR.string.player_pause)) or
            hasContentDescription(label(CoreCommonR.string.player_restore_volume)) or
            hasContentDescription(label(CoreCommonR.string.player_waiting))
        val wasPlaying = PlayerManager.isPlayingFlow.value

        for (enabled in listOf(true, false)) {
            composeRule.onNode(transport and hasClickAction()).performTouchInput { longClick() }
            composeRule.waitUntil(5_000) {
                runBlocking { settings.nowPlayingImmersiveModeFlow.first() == enabled }
            }
            composeRule.waitForIdle()
            // 播放页的提示会排队到更多面板挂载 host 后才显示
            button(CoreCommonR.string.nowplaying_more_options).performTouchInput { click() }
            composeRule.onNodeWithText(label(CoreCommonR.string.local_song_open_details)).assertIsDisplayed()
            composeRule.onNodeWithText(label(CoreCommonR.string.nowplaying_immersive_mode_on))
                .assertDoesNotExist()
            composeRule.onNodeWithText(label(CoreCommonR.string.nowplaying_immersive_mode_off))
                .assertDoesNotExist()
            assertEquals("长按切换沉浸模式不能触发播放或暂停", wasPlaying, PlayerManager.isPlayingFlow.value)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            composeRule.onNodeWithText(label(CoreCommonR.string.local_song_open_details)).assertDoesNotExist()
        }
    }

    private fun assertActionsReachable(
        height: Int,
        fontScale: Float,
        placement: NowPlayingControlPlacement,
        style: UiStyle = UiStyle.Clean,
        toggleAtRuntime: Boolean = false
    ) =
        UiFailureDiagnostics.onFailure("now-playing-immersive-360x$height-font-$fontScale-$placement") {
            runBlocking {
                SettingsRepository(composeRule.activity.applicationContext).apply {
                    setNowPlayingImmersiveMode(!toggleAtRuntime)
                    setPlaybackControlLayoutPreferences(PlaybackControlLayoutPreferences(
                        nowPlayingPlacement = placement, nowPlayingSize = PlaybackControlSize.LARGE))
                }
            }
            render(height, fontScale, style)
            val transport = hasContentDescription(label(CoreCommonR.string.player_play)) or
                hasContentDescription(label(CoreCommonR.string.player_pause)) or
                hasContentDescription(label(CoreCommonR.string.player_restore_volume)) or
                hasContentDescription(label(CoreCommonR.string.player_waiting))
            if (toggleAtRuntime) {
                val initialViewport = composeRule.onNodeWithTag(ViewportTag, useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                val initialControl = composeRule.onNode(transport and hasClickAction())
                assertFullyVisible(initialControl, initialViewport)
                initialControl.performTouchInput { longClick() }
                composeRule.waitUntil(5_000) {
                    runBlocking {
                        SettingsRepository(composeRule.activity.applicationContext).nowPlayingImmersiveModeFlow.first()
                    }
                }
                composeRule.waitForIdle()
            }
            val viewport = composeRule.onNodeWithTag(ViewportTag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertEquals(360f, (viewport.right - viewport.left).value, 1f)
            assertEquals(height - 48f, (viewport.bottom - viewport.top).value, 1f)

            val playbackButtons = listOf(CoreCommonR.string.player_shuffle, CoreCommonR.string.player_previous,
                CoreCommonR.string.player_next, CoreCommonR.string.player_repeat).map(::button)
            val primary = composeRule.onNode(transport and hasClickAction()).assertHasClickAction().assertIsEnabled()
            (playbackButtons + primary).forEach { assertFullyVisible(it, viewport) }
            val toolbarButtons = listOf(CoreCommonR.string.playlist_queue, CoreCommonR.string.sleep_timer_short,
                CoreCommonR.string.playlist_add_to).map(::button)
            toolbarButtons.forEach { assertFullyVisible(it, viewport) }
            assertFullyVisible(composeRule.onNodeWithTag("nowPlayingLyricsAction"), viewport)
            val playbackBottom = (playbackButtons + primary).maxOf { it.getUnclippedBoundsInRoot().bottom }
            val toolbarTop = toolbarButtons.minOf { it.getUnclippedBoundsInRoot().top }
            assertTrue("播放控件不能与底部工具栏重叠: playbackBottom=$playbackBottom toolbarTop=$toolbarTop",
                playbackBottom <= toolbarTop + 1.dp)

            button(CoreCommonR.string.sleep_timer_short).performTouchInput { click() }
            composeRule.onNodeWithText(label(CoreCommonR.string.sleep_timer_title)).assertIsDisplayed()
            composeRule.onNodeWithText(label(CoreCommonR.string.sleep_timer_close))
                .assertIsDisplayed().performTouchInput { click() }
            val back = button(CoreCommonR.string.action_back)
            assertFullyVisible(back, viewport)
            back.performTouchInput { click() }
            composeRule.runOnIdle { assertEquals(1, backCallbacks) }
        }

    private fun render(height: Int, fontScale: Float, style: UiStyle) {
        composeRule.setContent {
            if (visible) {
                val configuration = LocalConfiguration.current
                val portrait = remember(configuration, height, fontScale) {
                    Configuration(configuration).apply {
                        screenWidthDp = 360
                        screenHeightDp = height
                        smallestScreenWidthDp = 360
                        orientation = Configuration.ORIENTATION_PORTRAIT
                        uiMode = Configuration.UI_MODE_TYPE_NORMAL
                        this.fontScale = fontScale
                    }
                }
                CompositionLocalProvider(LocalConfiguration provides portrait) {
                    FittedTestViewport(360.dp, height.dp, fontScale = fontScale) {
                        val density = LocalDensity.current
                        val actualWindow = LocalWindowInfo.current
                        val size = with(density) { IntSize(360.dp.roundToPx(), height.dp.roundToPx()) }
                        val window = remember(actualWindow, size, height) {
                            object : WindowInfo by actualWindow {
                                override val containerSize = size
                                override val containerDpSize = DpSize(360.dp, height.dp)
                            }
                        }
                        CompositionLocalProvider(LocalWindowInfo provides window) {
                            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                                seedColorHex = "6750A4", uiStyle = style) {
                                // 预留系统栏后再测实际页面，避免宿主设备的 inset 大小影响短屏契约
                                Box(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 24.dp)) {
                                    Box(Modifier.fillMaxSize().testTag(ViewportTag)) {
                                        NowPlayingScreen(
                                            onNavigateUp = { backCallbacks++ },
                                            showLyricsScreen = false,
                                            onShowLyricsScreenChange = {},
                                            onEnterAlbum = {},
                                            lyricBlurEnabled = false,
                                            lyricBlurAmount = 2.5f,
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
            }
        }
        composeRule.waitForIdle()
    }

    private fun button(resource: Int): SemanticsNodeInteraction = composeRule.onNode(
        hasContentDescription(label(resource)) and hasClickAction()
    ).assertHasClickAction().assertIsEnabled()

    private fun assertFullyVisible(node: SemanticsNodeInteraction, viewport: DpRect) {
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        val semanticsNode = node.fetchSemanticsNode()
        val density = semanticsNode.layoutInfo.density
        val minimumHeightPx = with(density) { 40.dp.roundToPx() }
        assertTrue("控件必须有完整触摸高度: density=$density size=${semanticsNode.size} " +
            "minimumHeightPx=$minimumHeightPx bounds=$bounds viewport=$viewport",
            semanticsNode.size.width > 0 && semanticsNode.size.height >= minimumHeightPx)
        assertTrue("控件必须完整位于有效视窗: bounds=$bounds viewport=$viewport",
            bounds.left >= viewport.left - 1.dp && bounds.right <= viewport.right + 1.dp &&
                bounds.top >= viewport.top - 1.dp && bounds.bottom <= viewport.bottom + 1.dp)
    }

    private fun label(resource: Int): String = composeRule.activity.getString(resource)

    private companion object {
        const val ViewportTag = "now-playing-portrait-immersive-viewport"
    }
}
