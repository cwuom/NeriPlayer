package moe.ouom.neriplayer.ui.screen

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingScreen
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.buildNowPlayingCoverCacheKey
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.buildNowPlayingCoverRequest
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingLyricsFirstFrameRenderingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private var mounted by mutableStateOf(true)
    private var lyricsVisible by mutableStateOf(false)

    @Before
    fun requireTaskEmulator() {
        assumeTrue(
            "真实播放夹具只允许在模拟器运行",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.startsWith("sdk_")
        )
        assumeComposeHostAvailable()
    }

    @Test
    fun enteringLyricsKeepsDecodedArtworkVisibleBeforeItsColdHeaderRequestCompletes() {
        val context = composeRule.activity.applicationContext
        val savedCleanMotion = NeriMotionStyle.clean
        val audio = File.createTempFile("lyrics-first-frame-", ".wav", context.cacheDir)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }
        val png = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        val endpoint = HeldCoverResponse(png)
        try {
            writeSilentWave(audio)
            val fixture = SongItem(
                id = -System.nanoTime(),
                name = "First frame cover fixture",
                artist = "Fixture artist",
                album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
                albumId = 0L,
                durationMs = 30_000L,
                coverUrl = endpoint.url,
                mediaUri = Uri.fromFile(audio).toString(),
                localFilePath = audio.absolutePath,
                customCoverUrl = endpoint.url
            )
            composeRule.runOnUiThread {
                PlayerManager.release()
                PlayerManager.initialize(composeRule.activity.application)
                PlayerManager.playPlaylist(listOf(fixture), startIndex = 0)
            }
            composeRule.waitUntil(10_000L) { PlayerManager.currentSongFlow.value?.id == fixture.id }
            composeRule.runOnUiThread { PlayerManager.pause() }
            val actualSong = checkNotNull(PlayerManager.currentSongFlow.value)
            val songKey = actualSong.playbackVisualKey()
            assertEquals(endpoint.url, actualSong.customCoverUrl)
            val coverCacheKey = buildNowPlayingCoverCacheKey(
                endpoint.url,
                GlobalDownloadManager.downloadPresenceVersion.value,
                LocalAssetInvalidationBus.rootGenerationFlow.value,
                LocalAssetInvalidationBus.currentSongRevision(actualSong.stableKey())
            )
            val frame = checkNotNull(buildNowPlayingCoverRequest(endpoint.url, songKey, coverCacheKey)).frame
            RetainedPlaybackCoverBitmapCache.put(songKey, endpoint.url, frame.cacheKey, bitmap.asImageBitmap())
            render(songKey, endpoint.url)
            composeRule.waitForIdle()
            assertTrue("真实大封面必须先从当前歌曲的已解码帧绘制", artworkPixels() >= 500)
            assertEquals("大封面命中自身已解码缓存后不能提前发出歌词冷请求", 0, endpoint.requestCount.get())

            composeRule.mainClock.autoAdvance = false
            val frames = mutableListOf(composeRule.mainClock.currentTime to artworkPixels())
            composeRule.runOnIdle { lyricsVisible = true }
            // 不跳过首段转场，冷请求期间的每个动画帧都必须保留封面
            repeat(32) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                frames += composeRule.mainClock.currentTime to artworkPixels()
            }
            composeRule.onNodeWithTag("lyricsScreen").assertExists()
            val missingArtwork = frames.any { (_, count) -> count < 32 }
            assertTrue(
                "出现空帧时必须确实收到歌词 HTTP 请求，不能把网络配置错误当作复现",
                !missingArtwork || endpoint.requestCount.get() > 0
            )
            assertEquals("真实图片请求不能因端点错误失败", null, endpoint.failure.get())
            assertEquals("像素采样时响应必须仍处于受控等待", 0, endpoint.responseCount.get())

            endpoint.release()
            composeRule.waitUntil(10_000L) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                (endpoint.requestCount.get() == 0 || endpoint.responseCount.get() > 0) && artworkPixels() >= 32
            }
            assertTrue("释放真实 PNG 响应后歌词封面必须实际解码绘制", artworkPixels() >= 32)
            Log.i(Tag, "coldHeaderRequests=${endpoint.requestCount.get()} frames=$frames")
            assertTrue(
                "已有同歌曲同 URL 封面时，歌词切页从第一帧起都不能丢图: frames=$frames",
                frames.all { (_, count) -> count >= 32 }
            )
        } finally {
            try {
                composeRule.runOnIdle { mounted = false }
                composeRule.waitForIdle()
            } finally {
                try {
                    composeRule.runOnUiThread { PlayerManager.release() }
                } finally {
                    try {
                        endpoint.close()
                    } finally {
                        NeriMotionStyle.clean = savedCleanMotion
                        check(audio.delete())
                    }
                }
            }
        }
    }

    private fun render(songKey: String, coverUrl: String) {
        composeRule.setContent {
            if (mounted) {
                val configuration = LocalConfiguration.current
                val portrait = remember(configuration) {
                    Configuration(configuration).apply {
                        smallestScreenWidthDp = 360
                        screenWidthDp = 360
                        screenHeightDp = 800
                        orientation = Configuration.ORIENTATION_PORTRAIT
                    }
                }
                CompositionLocalProvider(LocalConfiguration provides portrait) {
                    FittedTestViewport(360.dp, 800.dp) {
                        val density = LocalDensity.current
                        val actualWindow = LocalWindowInfo.current
                        val size = with(density) { IntSize(360.dp.roundToPx(), 800.dp.roundToPx()) }
                        val window = remember(actualWindow, size) {
                            object : WindowInfo by actualWindow {
                                override val containerSize = size
                                override val containerDpSize = DpSize(360.dp, 800.dp)
                            }
                        }
                        CompositionLocalProvider(LocalWindowInfo provides window) {
                            NeriTheme(
                                followSystemDark = false,
                                forceDark = true,
                                dynamicColor = false,
                                seedColorHex = "336699",
                                uiStyle = UiStyle.Material
                            ) {
                                Box(Modifier.fillMaxSize().background(Color.Black).testTag(ViewportTag)) {
                                    NowPlayingScreen(
                                        onNavigateUp = {},
                                        showLyricsScreen = lyricsVisible,
                                        onShowLyricsScreenChange = { lyricsVisible = it },
                                        onEnterAlbum = {},
                                        lyricBlurEnabled = false,
                                        lyricBlurAmount = 0f,
                                        lyricFontScales = LyricFontScales(1f, 1f, 1f, 1f),
                                        onLyricFontScaleChange = { _, _ -> },
                                        advancedLyricsEnabled = false,
                                        showCoverSourceBadge = false,
                                        offlineMode = false,
                                        resolvedCoverUrl = coverUrl,
                                        visualCoverUrl = coverUrl,
                                        playbackSongKey = songKey,
                                        visualCoverSongKey = songKey
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun artworkPixels(): Int {
        val pixels = composeRule.onNodeWithTag(ViewportTag).captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height step 2) {
            for (x in 0 until pixels.width step 2) {
                val color = pixels[x, y]
                if (color.red > 0.75f && color.blue > 0.75f && color.green < 0.25f) count++
            }
        }
        return count
    }

    private fun writeSilentWave(file: File) {
        val pcm = ByteArray(8_000 * 30 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size)
        }.array()
        file.outputStream().use { it.write(header); it.write(pcm) }
    }

    private class HeldCoverResponse(private val png: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val clients = CopyOnWriteArrayList<Socket>()
        private val responseGate = CountDownLatch(1)
        val requestCount = AtomicInteger()
        val responseCount = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val url = "http://127.0.0.1:${server.localPort}/cover-${System.nanoTime()}.png"

        init {
            workers.execute {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    clients += client
                    try {
                        workers.execute { serve(client) }
                    } catch (error: RejectedExecutionException) {
                        runCatching { client.close() }
                        clients.remove(client)
                        if (!server.isClosed) failure.compareAndSet(null, error)
                        break
                    }
                }
            }
        }

        private fun serve(client: Socket) {
            try {
                client.use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val firstLine = reader.readLine() ?: return
                    check(firstLine.startsWith("GET /cover-")) { "未知图片请求: $firstLine" }
                    while (!reader.readLine().isNullOrEmpty()) {}
                    requestCount.incrementAndGet()
                    if (!responseGate.await(60, TimeUnit.SECONDS)) return
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                            "Content-Length: ${png.size}\r\nCache-Control: no-store\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(png)
                        flush()
                    }
                    responseCount.incrementAndGet()
                }
            } catch (error: Exception) {
                if (!server.isClosed) failure.compareAndSet(null, error)
            } finally {
                clients.remove(client)
            }
        }

        fun release() = responseGate.countDown()

        override fun close() {
            release()
            server.close()
            clients.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "图片端点线程没有退出" }
        }
    }

    private companion object {
        const val Tag = "LyricsFirstFrameTest"
        const val ViewportTag = "lyrics-first-frame-viewport"
    }
}
