package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable

@RunWith(AndroidJUnit4::class)
class NowPlayingPassiveCoverOwnershipRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var owner: NowPlayingCoverOwner
    private var source by mutableStateOf(NowPlayingCoverSource(null, null, emptyList(), "initial", ""))
    private var mounted by mutableStateOf(false)
    private var generation by mutableIntStateOf(0)

    @Before
    fun requireEmulatorHost() {
        assumeTrue(
            "延迟图片响应夹具只允许在模拟器运行",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.startsWith("sdk_")
        )
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun passiveHandoffDoesNotRewriteTheOwnerWhileAFreshEndpointWaitsForItsCover() {
        val png = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(android.graphics.Color.BLUE)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        val endpoint = HeldCoverResponse(png)
        val token = System.nanoTime().toString()
        val first = coverSource("content://passive-cover/$token/first", "first-$token")
        val second = coverSource(endpoint.url, "second-$token")
        val ownerWrites = AtomicInteger()
        var watchedState: Any? = null
        var disposeObserver: (() -> Unit)? = null
        source = first
        try {
            composeRule.setContent {
                owner = rememberNowPlayingCoverOwner()
                MaterialTheme(colorScheme = lightColorScheme(primaryContainer = Color.Black)) {
                    if (mounted) {
                        Row {
                            Box(Modifier.size(128.dp).background(Color.Black).testTag(EndpointTag)) {
                                key(generation) {
                                    StableNowPlayingCoverImage(
                                        owner, source.coverUrl, source.songKey, LocalContext.current,
                                        NowPlayingCoverBitmapMaxDimensionPx, false, source.cacheKey,
                                        source.contentDescription, Modifier.fillMaxSize(), source.songKeyAliases
                                    )
                                }
                            }
                            Box(Modifier.size(128.dp).background(Color.Black).testTag(PassiveTag)) {
                                PassiveNowPlayingCoverImage(owner, source, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            composeRule.runOnIdle {
                val request = checkNotNull(owner.requestFor(first.requestInput()))
                owner.presentation(request, first.songKey, first.songKeyAliases)
                owner.publishDecodedFrame(request, request.frame.copy(decodedBitmap = redBitmap()))
                val presentation = owner.presentation(request, first.songKey, first.songKeyAliases)
                val stateWrites = mutableSetOf<Any>()
                Snapshot.observe(writeObserver = { stateWrites += it }) {
                    owner.onPresented(first.songKey, presentation)
                }
                assertEquals("观察对象必须来自真实 onPresented 的唯一状态写入", 1, stateWrites.size)
                watchedState = stateWrites.single()
                mounted = true
            }
            advance(512L)
            assertRed("已解码 A 端点", centerColor(EndpointTag))
            assertRed("常驻被动 A", centerColor(PassiveTag))
            assertEquals("A 已解码时不能发出 B 请求", 0, endpoint.requestCount.get())

            val observer = Snapshot.registerGlobalWriteObserver { state ->
                if (state === watchedState) ownerWrites.incrementAndGet()
            }
            disposeObserver = observer::dispose
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                source = second
                generation++
            }
            composeRule.mainClock.advanceTimeByFrame()
            assertTrue("必须收到真实 B 图片 HTTP 请求", endpoint.requestReceived.await(10, TimeUnit.SECONDS))
            // 不在 pending 阶段依赖截图等待空闲，旧实现持续重组时也能释放响应并完成诊断
            repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
            val beforeStableFrames = ownerWrites.get()
            repeat(32) { index ->
                composeRule.mainClock.advanceTimeByFrame()
                Log.i(Tag, "pending frame=$index clock=${composeRule.mainClock.currentTime} writes=${ownerWrites.get()}")
            }
            val writesWhilePending = ownerWrites.get() - beforeStableFrames
            assertEquals("采样时真实 PNG 响应必须仍被持有", 0, endpoint.responseCount.get())
            assertEquals("真实 B 图片请求不能失败", null, endpoint.failure.get())
            endpoint.release()
            composeRule.waitUntil(10_000L) {
                owner.displayedFrame?.let { it.coverUrl == second.coverUrl && it.decodedBitmap != null } == true
            }
            advance(16L)
            val firstDecodedClock = composeRule.mainClock.currentTime
            val firstDecoded = centerColor(PassiveTag)
            advance(96L)
            val crossfade96Clock = composeRule.mainClock.currentTime
            val crossfade96 = centerColor(PassiveTag)
            advance(64L)
            val crossfade160Clock = composeRule.mainClock.currentTime
            val crossfade160 = centerColor(PassiveTag)
            advance(128L)
            val finalPassive = centerColor(PassiveTag)
            val finalEndpoint = centerColor(EndpointTag)
            Log.i(
                Tag,
                "result pendingWrites=$writesWhilePending requests=${endpoint.requestCount.get()} " +
                    "firstDecodedClock=$firstDecodedClock firstDecoded=$firstDecoded " +
                    "fade96Clock=$crossfade96Clock fade96=$crossfade96 " +
                    "fade160Clock=$crossfade160Clock fade160=$crossfade160 " +
                    "finalPassive=$finalPassive finalEndpoint=$finalEndpoint"
            )
            assertEquals("固定 pending B 输入不能让被动层和新端点持续互写 owner", 0, writesWhilePending)
            assertEquals("被动层不能额外加载或预取 B", 1, endpoint.requestCount.get())
            assertTrue("被动层不能在 B 解码首帧直接丢掉 A", firstDecoded.red > 0.2f)
            assertMixed("96ms 保留原封面渐变", crossfade96)
            // 动画帧先于重组，首个解码重组帧的下一帧才是 Crossfade 的 t0
            val fadeStartClock = firstDecodedClock + 16L
            assertCrossfade("96ms", crossfade96, crossfade96Clock - fadeStartClock)
            assertCrossfade("160ms", crossfade160, crossfade160Clock - fadeStartClock)
            assertBlue("最终被动 B", finalPassive)
            assertBlue("最终端点 B", finalEndpoint)
        } finally {
            disposeObserver?.invoke()
            endpoint.release()
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { mounted = false }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            } finally {
                endpoint.close()
            }
        }
    }

    private fun coverSource(url: String, songKey: String): NowPlayingCoverSource =
        NowPlayingCoverSource(url, songKey, emptyList(), "passive-test-$songKey", songKey)

    private fun NowPlayingCoverSource.requestInput(): NowPlayingCoverRequestInput =
        NowPlayingCoverRequestInput(coverUrl, songKey, cacheKey)

    private fun redBitmap(): ImageBitmap = ImageBitmap(16, 16).also {
        Canvas(it).drawRect(Rect(0f, 0f, 16f, 16f), Paint().apply { color = Color.Red })
    }

    private fun centerColor(tag: String): Color {
        val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun assertRed(label: String, color: Color) =
        assertTrue("$label 必须实际绘制红图: $color", color.red > 0.8f && color.blue < 0.2f)

    private fun assertBlue(label: String, color: Color) =
        assertTrue("$label 必须实际绘制蓝图: $color", color.blue > 0.8f && color.red < 0.2f)

    private fun assertMixed(label: String, color: Color) =
        assertTrue("$label 必须包含旧图和新图: $color", color.red > 0.02f && color.blue > 0.05f)

    private fun assertCrossfade(label: String, color: Color, playTimeMillis: Long) {
        val fraction = FastOutSlowInEasing.transform((playTimeMillis / 220f).coerceIn(0f, 1f))
        val expectedRed = (1f - fraction) * (1f - fraction)
        val tolerance = 3f / 255f
        Log.i(Tag, "crossfade label=$label playTime=$playTimeMillis fraction=$fraction expectedRed=$expectedRed color=$color")
        assertEquals("$label 旧红图按两层透明度叠加", expectedRed, color.red, tolerance)
        assertEquals("$label 新蓝图按默认easing淡入", fraction, color.blue, tolerance)
    }

    private fun advance(milliseconds: Long) {
        val end = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < end) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private class HeldCoverResponse(private val png: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val clients = CopyOnWriteArrayList<Socket>()
        private val responseGate = CountDownLatch(1)
        val requestReceived = CountDownLatch(1)
        val requestCount = AtomicInteger()
        val responseCount = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val url = "http://127.0.0.1:${server.localPort}/passive-${System.nanoTime()}.png"

        init {
            workers.execute {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: SocketException) { break }
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
                    check(reader.readLine()?.startsWith("GET /passive-") == true)
                    while (!reader.readLine().isNullOrEmpty()) {}
                    requestCount.incrementAndGet()
                    requestReceived.countDown()
                    check(responseGate.await(30, TimeUnit.SECONDS)) { "图片响应等待超时" }
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
        const val Tag = "PassiveCoverOwnership"
        const val EndpointTag = "passive-cover-endpoint"
        const val PassiveTag = "passive-cover-image"
    }
}
