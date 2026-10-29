package moe.ouom.neriplayer.ui.screen

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingScreen
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.buildNowPlayingCoverCacheKey
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.buildNowPlayingCoverRequest
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
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
        val savedImageLoader = Coil.imageLoader(context)
        val memoryCache = MemoryCache.Builder(context).build()
        // 媒体元数据和取色共用 URL，只为它们预热，保留 192px 歌词请求的冷缓存
        val backgroundCacheKeys = listOf(96, 512).associate { size ->
            Size(size, size) to MemoryCache.Key("lyrics-first-frame-background|${endpoint.url}|$size")
        }
        backgroundCacheKeys.values.forEach { key -> memoryCache[key] = MemoryCache.Value(bitmap) }
        val backgroundRequestSizes = CopyOnWriteArrayList<Size>()
        val imageLoader = savedImageLoader.newBuilder()
            .memoryCache(memoryCache)
            .diskCache(null)
            .components {
                add(Interceptor { chain ->
                    val request = chain.request
                    val cacheKey = backgroundCacheKeys[chain.size]
                        .takeIf { request.data == endpoint.url && request.memoryCacheKey == null }
                    if (cacheKey != null) backgroundRequestSizes += chain.size
                    chain.proceed(
                        if (cacheKey == null) request
                        else request.newBuilder().memoryCacheKey(cacheKey).build()
                    )
                })
            }
            .build()
        var coldHeaderRequest: Disposable? = null
        try {
            Coil.setImageLoader(imageLoader)
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
            val transitionHeaderRequests = endpoint.requestCount.get()
            assertTrue(
                "出现空帧时必须确实收到歌词 HTTP 请求，不能把网络配置错误当作复现",
                !missingArtwork || transitionHeaderRequests > 0
            )
            assertEquals("真实图片请求不能因端点错误失败", null, endpoint.failure.get())
            assertEquals("像素采样时响应必须仍处于受控等待", 0, endpoint.responseCount.get())

            // 采样结束后验证旧歌词页使用的尺寸仍会真正访问受控端点
            val coldHeaderResult = AtomicReference<ImageResult?>()
            coldHeaderRequest = imageLoader.enqueue(
                offlineCachedImageRequest(
                    context = context,
                    data = endpoint.url,
                    sizePx = 192,
                    allowHardware = false,
                    crossfade = false,
                    offlineMode = false
                ).newBuilder().listener(
                    onError = { _, result -> coldHeaderResult.set(result) },
                    onSuccess = { _, result -> coldHeaderResult.set(result) }
                ).build()
            )
            composeRule.waitUntil(10_000L) { endpoint.requestCount.get() > transitionHeaderRequests }
            assertEquals("192px 冷请求收到响应前必须仍被持有", 0, endpoint.responseCount.get())
            endpoint.release()
            composeRule.waitUntil(10_000L) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                coldHeaderResult.get() != null && endpoint.responseCount.get() > 0 && artworkPixels() >= 32
            }
            val headerResult = coldHeaderResult.get()
            assertTrue("192px 歌词请求必须从真实 HTTP 响应解码: $headerResult",
                headerResult is SuccessResult && headerResult.dataSource == DataSource.NETWORK)
            assertTrue("释放真实 PNG 响应后歌词封面必须实际解码绘制", artworkPixels() >= 32)
            Log.i(Tag, "backgroundRequestSizes=$backgroundRequestSizes transitionHeaderRequests=$transitionHeaderRequests " +
                "coldProbeRequests=${endpoint.requestCount.get()} frames=$frames")
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
                        coldHeaderRequest?.dispose()
                        endpoint.close()
                    } finally {
                        Coil.setImageLoader(savedImageLoader)
                        imageLoader.shutdown()
                        NeriMotionStyle.clean = savedCleanMotion
                        check(audio.delete())
                    }
                }
            }
        }
    }

    @Test
    fun materialLyricsActionReturnDeceleratesTheActualCoverPixels() =
        assertOrdinaryReturnDecelerates(UiStyle.Material)

    @Test
    fun cleanLyricsActionReturnDeceleratesTheActualCoverPixels() =
        assertOrdinaryReturnDecelerates(UiStyle.Clean)

    private fun assertOrdinaryReturnDecelerates(style: UiStyle) {
        val context = composeRule.activity.applicationContext
        val savedCleanMotion = NeriMotionStyle.clean
        val savedPreferences = runBlocking { context.dataStore.data.first() }
        val directory = File(context.cacheDir, "lyrics-return-${System.nanoTime()}")
        check(directory.mkdirs())
        val audio = File(directory, "fixture.wav")
        val cover = File(directory, "cover.png")
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }
        val record = InstrumentationRegistry.getArguments().getString("recordLyricsReturn") == "true"
        try {
            val captures = File(
                checkNotNull(context.getExternalFilesDir(null)), "lyrics-return-${style.name.lowercase()}"
            ).also { check(it.isDirectory || it.mkdirs()) }
            runBlocking {
                SettingsRepository(context).apply {
                    setNowPlayingImmersiveMode(false)
                    setNowPlayingDynamicBackgroundEnabled(false)
                    setNowPlayingCoverBlurBackgroundEnabled(false)
                    setNowPlayingAudioReactiveEnabled(false)
                }
            }
            writeSilentWave(audio)
            cover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val coverUrl = Uri.fromFile(cover).toString()
            val fixture = SongItem(
                id = -System.nanoTime(),
                name = "Return timing fixture",
                customName = "Return timing fixture",
                artist = "Fixture artist",
                customArtist = "Fixture artist",
                album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
                albumId = 0L,
                durationMs = 30_000L,
                coverUrl = coverUrl,
                customCoverUrl = coverUrl,
                matchedLyric = "[00:00.00]Return timing lyric",
                lyricSyncEdited = true,
                mediaUri = Uri.fromFile(audio).toString(),
                localFilePath = audio.absolutePath
            )
            composeRule.runOnUiThread {
                PlayerManager.release()
                PlayerManager.initialize(composeRule.activity.application)
                PlayerManager.playPlaylist(listOf(fixture), startIndex = 0)
            }
            composeRule.waitUntil(10_000L) { PlayerManager.currentSongFlow.value?.id == fixture.id }
            composeRule.runOnUiThread {
                PlayerManager.pause()
                lyricsVisible = true
                NeriMotionStyle.clean = style == UiStyle.Clean
            }
            val actualSong = checkNotNull(PlayerManager.currentSongFlow.value)
            val songKey = actualSong.playbackVisualKey()
            assertEquals(coverUrl, actualSong.customCoverUrl)
            val cacheKey = buildNowPlayingCoverCacheKey(
                coverUrl,
                GlobalDownloadManager.downloadPresenceVersion.value,
                LocalAssetInvalidationBus.rootGenerationFlow.value,
                LocalAssetInvalidationBus.currentSongRevision(actualSong.stableKey())
            )
            val frame = checkNotNull(buildNowPlayingCoverRequest(coverUrl, songKey, cacheKey)).frame
            RetainedPlaybackCoverBitmapCache.put(songKey, coverUrl, frame.cacheKey, bitmap.asImageBitmap())
            render(songKey, coverUrl, style = style, offlineMode = true, previewEnabled = false)
            composeRule.waitForIdle()
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(1_024L)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("lyricsScreen").assertIsDisplayed()
            val frames = mutableListOf(
                composeRule.mainClock.currentTime to artworkBounds(captures.resolve("frame-00.png"))
            )

            composeRule.onNodeWithTag("nowPlayingLyricsAction").assertIsDisplayed().performClick()
            repeat(48) { index ->
                composeRule.mainClock.advanceTimeBy(16L)
                composeRule.waitForIdle()
                frames += composeRule.mainClock.currentTime to
                    artworkBounds(captures.resolve("frame-${(index + 1).toString().padStart(2, '0')}.png"))
            }
            val widths = frames.map { (_, bounds) -> bounds.width }
            val evidence = "$style return pixel samples=${frames.map { (time, bounds) ->
                time - frames.first().first to bounds.width
            }}"
            Log.i(Tag, evidence)
            captures.resolve("widths.txt").writeText(evidence)
            assertTrue("必须确实从歌词小封面展开回播放页: $evidence", widths.last() > widths.first() * 2)
            assertTrue("封面展开不能倒退或抖动: $evidence", widths.zipWithNext().all { (a, b) -> b >= a - 2 })
            // 按实际行程取完整步，避开启动等待和最后一步被端点截短造成的假减速
            val middleSpeed = averageSpeedInTravelWindow(frames, 0.20f, 0.60f)
            val tailSpeed = averageSpeedInTravelWindow(frames, 0.85f, 0.98f)
            val speeds = "middle20-60=$middleSpeed px/ms tail85-98=$tailSpeed px/ms"
            Log.i(Tag, speeds)
            captures.resolve("widths.txt").appendText("\n$speeds")
            assertTrue("实际行程尾段应明显减速: $speeds $evidence", middleSpeed > tailSpeed * 2f)

            composeRule.mainClock.advanceTimeBy(1_024L)
            composeRule.waitForIdle()
            val settled = artworkBounds(captures.resolve("cover-page.png"))
            assertTrue("采样结束应已落到最终大封面", settled.width in widths.last() - 2..widths.last() + 2)
            assertTrue("最终封面应保持正方形", settled.height in settled.width - 2..settled.width + 2)
            composeRule.onNodeWithTag("lyricsScreen").assertDoesNotExist()
            composeRule.onNodeWithText(fixture.name).assertIsDisplayed()
            composeRule.onNodeWithText(fixture.artist).assertIsDisplayed()
            if (record) {
                Log.i(Tag, "realTimeReplayStart style=$style")
                recordOrdinaryReturns()
                Log.i(Tag, "realTimeReplayEnd style=$style")
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
            try {
                composeRule.runOnIdle { mounted = false }
                composeRule.waitForIdle()
            } finally {
                try {
                    composeRule.runOnUiThread { PlayerManager.release() }
                } finally {
                    try {
                        runBlocking {
                            context.dataStore.updateData { savedPreferences }
                            assertEquals(savedPreferences, context.dataStore.data.first())
                        }
                    } finally {
                        NeriMotionStyle.clean = savedCleanMotion
                        check(directory.deleteRecursively())
                    }
                }
            }
        }
    }

    private fun averageSpeedInTravelWindow(
        frames: List<Pair<Long, IntRect>>,
        startFraction: Float,
        endFraction: Float
    ): Float {
        val initialWidth = frames.first().second.width
        val finalWidth = frames.last().second.width
        val travel = (finalWidth - initialWidth).toFloat()
        val steps = frames.zipWithNext().filter { (start, end) ->
            val from = (start.second.width - initialWidth) / travel
            val to = (end.second.width - initialWidth) / travel
            from in startFraction..endFraction && to in startFraction..endFraction &&
                end.second.width < finalWidth
        }
        assertTrue("行程窗口 $startFraction..$endFraction 必须包含至少两个完整采样步", steps.size >= 2)
        val displacement = steps.sumOf { (start, end) -> end.second.width - start.second.width }
        val elapsed = steps.sumOf { (start, end) -> end.first - start.first }
        assertTrue("行程窗口必须包含实际位移和经过时间", displacement > 0 && elapsed > 0L)
        return displacement.toFloat() / elapsed
    }

    private fun artworkBounds(captureFile: File? = null): IntRect {
        val image = composeRule.onNodeWithTag(ViewportTag).captureToImage()
        captureFile?.outputStream()?.use { check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val pixels = image.toPixelMap()
        var left = pixels.width
        var top = pixels.height
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.red > 0.9f && color.blue > 0.9f && color.green < 0.1f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("每次像素采样都必须有实际绘制的封面", right >= left && bottom >= top)
        return IntRect(left, top, right + 1, bottom + 1)
    }

    private fun recordOrdinaryReturns() {
        composeRule.mainClock.autoAdvance = false
        repeat(3) {
            composeRule.onNodeWithTag("nowPlayingLyricsAction").performClick()
            replayPageTransition()
            composeRule.onNodeWithTag("nowPlayingLyricsAction").performClick()
            replayPageTransition()
        }
    }

    private fun replayPageTransition() {
        // 按测试时钟逐帧回放，避免自动快进让录屏跳过中间帧
        val started = SystemClock.uptimeMillis()
        repeat(ReplayFramesPerPage) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            val remaining = started + (frame + 1) * ReplayFrameMillis - SystemClock.uptimeMillis()
            if (remaining > 0L) SystemClock.sleep(remaining)
        }
    }

    private fun render(
        songKey: String,
        coverUrl: String,
        style: UiStyle = UiStyle.Material,
        offlineMode: Boolean = false,
        previewEnabled: Boolean? = null
    ) {
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
                CompositionLocalProvider(
                    LocalConfiguration provides portrait,
                    LocalPredictiveBackPreviewEnabled provides
                        (previewEnabled ?: LocalPredictiveBackPreviewEnabled.current)
                ) {
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
                                uiStyle = style
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
                                        offlineMode = offlineMode,
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
        const val ReplayFramesPerPage = 64
        const val ReplayFrameMillis = 16L
        const val Tag = "LyricsFirstFrameTest"
        const val ViewportTag = "lyrics-first-frame-viewport"
    }
}
