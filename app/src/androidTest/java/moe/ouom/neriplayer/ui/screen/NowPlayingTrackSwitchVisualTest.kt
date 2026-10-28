package moe.ouom.neriplayer.ui.screen

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.activity.AppUiDensityRoot
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.FittedTestViewport
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
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NowPlayingTrackSwitchVisualTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private var savedPreferences: Preferences? = null
    private var savedCleanMotion = false
    private var mounted by mutableStateOf(true)
    private var fixtureDirectory: File? = null
    private lateinit var songs: List<SongItem>
    private val coverColors = listOf(Color.rgb(47, 120, 137), Color.rgb(135, 108, 159), Color.rgb(186, 118, 73))

    @Before
    fun prepareLocalPlaylist() {
        savedCleanMotion = NeriMotionStyle.clean
        assumeComposeHostAvailable()
        val context = composeRule.activity.applicationContext
        composeRule.runOnUiThread {
            composeRule.activity.actionBar?.hide()
            composeRule.activity.window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            WindowCompat.getInsetsController(composeRule.activity.window, composeRule.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        savedPreferences = runBlocking { context.dataStore.data.first() }
        runBlocking {
            SettingsRepository(context).apply {
                setNowPlayingImmersiveMode(false)
                setNowPlayingToolbarDockEnabled(true)
                setNowPlayingProgressShowQualitySwitch(true)
                setNowPlayingProgressShowAudioCodec(true)
                setNowPlayingProgressShowAudioSpec(true)
                setNowPlayingDynamicBackgroundEnabled(false)
                setNowPlayingAudioReactiveEnabled(false)
                setPlaybackControlLayoutPreferences(PlaybackControlLayoutPreferences())
                setPlaybackCrossfadeNext(false)
                setPlaybackFadeIn(false)
                setUiDensityScale(1f)
            }
        }
        val directory = File(context.cacheDir, "track-switch-${System.nanoTime()}")
        check(directory.mkdirs())
        fixtureDirectory = directory
        songs = listOf("Ocean Study", "Evening Study", "Autumn Study").mapIndexed { index, name ->
            val audio = File(directory, "track-$index.wav")
            writeSilentWave(audio)
            val cover = File(directory, "cover-$index.png")
            writeCover(cover, index, name)
            val lyric = """
                [00:00.00]让海风慢慢经过
                [00:04.00]把今天轻轻收藏
                [00:08.00]远处灯火映着波光
                [00:12.00]脚步停在安静地方
                [00:16.00]听见这一刻的回响
                [00:20.00]沿着晨光继续前往
            """.trimIndent()
            File(directory, "track-$index.lrc").writeText(lyric)
            SongItem(
                id = -538_200L - index,
                name = name,
                customName = name,
                artist = "NeriPlayer Visual Fixture",
                customArtist = "NeriPlayer Visual Fixture",
                album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
                albumId = 0L,
                durationMs = 60_000L,
                coverUrl = Uri.fromFile(cover).toString(),
                matchedLyric = lyric,
                lyricSyncEdited = true,
                mediaUri = Uri.fromFile(audio).toString(),
                localFilePath = audio.absolutePath
            )
        }
        composeRule.runOnUiThread {
            PlayerManager.release()
            PlayerManager.initialize(composeRule.activity.application)
            PlayerManager.playPlaylist(songs, startIndex = 0)
        }
        composeRule.waitUntil(10_000) { PlayerManager.currentSongFlow.value?.id == songs.first().id }
        composeRule.waitUntil(10_000) { PlayerManager.currentPlaybackAudioInfoFlow.value != null }
        composeRule.runOnUiThread {
            PlayerManager.pause()
            PlayerManager.setShuffle(false)
            repeat(3) {
                if (PlayerManager.repeatModeFlow.value != Player.REPEAT_MODE_ALL) {
                    PlayerManager.cycleRepeatMode()
                }
            }
        }
        assertEquals(Player.REPEAT_MODE_ALL, PlayerManager.repeatModeFlow.value)
    }

    @After
    fun restoreSettingsAndReleaseFixtures() {
        try {
            try {
                composeRule.runOnUiThread {
                    PlayerManager.pause()
                    mounted = false
                }
                composeRule.waitForIdle()
            } finally {
                composeRule.runOnUiThread { PlayerManager.release() }
            }
        } finally {
            try {
                savedPreferences?.let { preferences ->
                    val context = composeRule.activity.applicationContext
                    runBlocking {
                        context.dataStore.updateData { preferences }
                        assertEquals(preferences, context.dataStore.data.first())
                    }
                }
            } finally {
                NeriMotionStyle.clean = savedCleanMotion
                fixtureDirectory?.let { check(it.deleteRecursively()) }
            }
        }
    }

    @Test
    fun phoneNextButtonKeepsArtworkStableAcrossThreeTrackChanges() =
        captureTrackChanges("phone", width = 360, height = 800)

    @Test
    fun tabletNextButtonKeepsArtworkStableAcrossThreeTrackChanges() =
        captureTrackChanges("tablet", width = 1280, height = 800)

    private fun captureTrackChanges(layout: String, width: Int, height: Int) {
        render(width, height)
        val initialCover = awaitArtwork(0)
        capture(layout, "initial")
        holdForRecording()
        repeat(3) { step ->
            composeRule.onNode(
                hasContentDescription(composeRule.activity.getString(CoreCommonR.string.player_next)) and
                    hasClickAction()
            ).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
            val index = (step + 1) % songs.size
            composeRule.waitUntil(10_000) { PlayerManager.currentSongFlow.value?.id == songs[index].id }
            composeRule.waitUntil(10_000) { PlayerManager.currentPlaybackAudioInfoFlow.value != null }
            composeRule.runOnUiThread { PlayerManager.pause() }
            assertFixedCover(initialCover, awaitArtwork(index, initialCover))
            capture(layout, "next-${step + 1}")
            holdForRecording()
        }
    }

    private fun render(width: Int, height: Int) {
        composeRule.setContent {
            if (mounted) {
                val configuration = LocalConfiguration.current
                val simulated = remember(configuration, width, height) {
                    Configuration(configuration).apply {
                        screenWidthDp = width
                        screenHeightDp = height
                        smallestScreenWidthDp = minOf(width, height)
                        orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE
                            else Configuration.ORIENTATION_PORTRAIT
                        uiMode = Configuration.UI_MODE_TYPE_NORMAL
                        fontScale = 1f
                    }
                }
                CompositionLocalProvider(LocalConfiguration provides simulated) {
                    FittedTestViewport(width.dp, height.dp) {
                        val density = LocalDensity.current
                        val actualWindow = LocalWindowInfo.current
                        val size = with(density) { IntSize(width.dp.roundToPx(), height.dp.roundToPx()) }
                        val window = remember(actualWindow, size, width, height) {
                            object : WindowInfo by actualWindow {
                                override val containerSize = size
                                override val containerDpSize = DpSize(width.dp, height.dp)
                            }
                        }
                        CompositionLocalProvider(LocalWindowInfo provides window) {
                            AppUiDensityRoot(1f) {
                                NeriTheme(false, true, false, "336699", uiStyle = UiStyle.Clean) {
                                    Surface(Modifier.fillMaxSize()) {
                                        val song by PlayerManager.currentSongFlow.collectAsState()
                                        Box(Modifier.fillMaxSize().testTag(ViewportTag)) {
                                            NowPlayingScreen(
                                                onNavigateUp = {},
                                                showLyricsScreen = false,
                                                onShowLyricsScreenChange = {},
                                                onEnterAlbum = {},
                                                lyricBlurEnabled = false,
                                                lyricBlurAmount = 0f,
                                                lyricFontScales = LyricFontScales(1f, 1f, 1f, 1f),
                                                onLyricFontScaleChange = { _, _ -> },
                                                advancedLyricsEnabled = false,
                                                offlineMode = true,
                                                resolvedCoverUrl = song?.coverUrl,
                                                visualCoverUrl = song?.coverUrl,
                                                playbackSongKey = song?.playbackVisualKey(),
                                                visualCoverSongKey = song?.playbackVisualKey()
                                            )
                                        }
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

    private fun awaitArtwork(index: Int, initialCover: Rect? = null): Rect {
        val expected = coverColors[index]
        var loadedCover: Rect? = null
        composeRule.waitUntil(10_000) {
            val covers = composeRule.onAllNodesWithContentDescription(songs[index].name, useUnmergedTree = true)
                .fetchSemanticsNodes()
            val cover = covers.map { it.boundsInRoot }
                .filter { it.width > 0f && it.height > 0f && abs(it.width - it.height) <= 1f }
                .maxByOrNull { it.width * it.height } ?: return@waitUntil false
            initialCover?.let { assertFixedCover(it, cover) }
            val viewport = composeRule.onNodeWithTag(ViewportTag, useUnmergedTree = true)
            val origin = viewport.fetchSemanticsNode().boundsInRoot.topLeft
            val pixels = viewport.captureToImage().toPixelMap()
            val center = cover.center - origin
            val x = center.x.toInt()
            val y = center.y.toInt()
            if (x !in 0 until pixels.width || y !in 0 until pixels.height) return@waitUntil false
            val actual = pixels[x, y]
            val loaded = abs(actual.red * 255 - Color.red(expected)) < 4 &&
                abs(actual.green * 255 - Color.green(expected)) < 4 &&
                abs(actual.blue * 255 - Color.blue(expected)) < 4
            if (loaded) {
                loadedCover = cover
            }
            loaded
        }
        composeRule.waitForIdle()
        return checkNotNull(loadedCover)
    }

    private fun assertFixedCover(initial: Rect, actual: Rect) {
        val message = "切歌时主封面尺寸和中心必须固定: initial=$initial actual=$actual"
        assertEquals(message, initial.width, actual.width, 1f)
        assertEquals(message, initial.height, actual.height, 1f)
        assertEquals(message, initial.center.x, actual.center.x, 1f)
        assertEquals(message, initial.center.y, actual.center.y, 1f)
    }

    private fun capture(layout: String, stage: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf(String::isNotBlank) ?: "track-switch"
        val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "track-switch-visual")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$prefix-$layout-$stage.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun holdForRecording() {
        if (InstrumentationRegistry.getArguments().getString("visualHold") == "true") {
            Thread.sleep(2_000)
        }
    }

    private fun writeSilentWave(file: File) {
        val pcm = ByteArray(8_000 * 60 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size)
        }.array()
        file.outputStream().use { it.write(header); it.write(pcm) }
    }

    private fun writeCover(file: File, index: Int, name: String) {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(coverColors[index])
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(100, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            canvas.drawCircle(256f, 220f, 140f, paint)
            canvas.drawCircle(256f, 220f, 105f, paint)
            paint.apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                textAlign = Paint.Align.CENTER
                textSize = 30f
            }
            canvas.drawText(name, 256f, 425f, paint)
            paint.textSize = 18f
            canvas.drawText("NERIPLAYER  /  0${index + 1}", 256f, 460f, paint)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ViewportTag = "now-playing-track-switch-visual"
    }
}
