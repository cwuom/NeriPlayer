package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalSharedTransitionApi::class)
class NowPlayingCoverTrackStabilityRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val token = System.nanoTime().toString()
    private lateinit var owner: NowPlayingCoverOwner
    private var source by mutableStateOf(source("first"))
    private var mounted by mutableStateOf(false)
    private var generation by mutableIntStateOf(0)
    private val measuredSizes = mutableListOf<IntSize>()

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun phoneTrackChangesKeepTheArtworkAndBadgeAtTheirFixedSize() {
        setHost(tabletLandscape = false)
        assertTrackChangeStability()
    }

    @Test
    fun tabletTrackChangesKeepTheArtworkAndBadgeAtTheirFixedSize() {
        setHost(tabletLandscape = true)
        assertTrackChangeStability()
    }

    @Test
    fun differentSongsWithTheSameCoverKeepTheArtworkAtItsFixedSize() {
        setHost()
        val initial = capture()
        publish(source("second").copy(coverUrl = source.coverUrl), Color.Red)
        repeat(24) {
            advance(16)
            assertFixedBounds(initial, capture())
        }
    }

    @Test
    fun delayedArtworkAndCacheRefreshKeepTheArtworkAtItsFixedSize() {
        setHost()
        val initial = capture()
        val next = source("second")
        composeRule.runOnIdle { source = next.copy(coverUrl = null) }
        repeat(24) {
            advance(16)
            assertFixedBounds(initial, capture())
        }
        assertColor(Color.Red, capture().center)

        publish(next, Color.Blue)
        repeat(8) {
            advance(16)
            assertFixedBounds(initial, capture())
        }
        advance(240)
        assertColor(Color.Blue, capture().center)

        publish(next.copy(cacheKey = "updated-cache-$token"), Color.Green)
        repeat(8) {
            advance(16)
            assertFixedBounds(initial, capture())
        }
        advance(240)
        assertColor(Color.Green, capture().center)
    }

    @Test
    fun firstMountAndSameSongPanelReentryKeepTheArtworkAtFullSize() {
        setHost(settle = false)
        val initial = capture()
        assertEquals("首次挂载保留完整封面", 200f, initial.width, 1f)
        repeat(8) {
            advance(16)
            assertFixedBounds(initial, capture())
        }

        composeRule.runOnIdle { generation++ }
        repeat(8) {
            advance(16)
            assertFixedBounds(initial, capture())
        }
    }

    @Test
    fun songIdentityEnrichmentKeepsTheArtworkAtItsFixedSize() {
        setHost()
        val initial = capture()
        val previousKey = checkNotNull(source.songKey)
        publish(
            source.copy(
                songKey = "enriched-$token",
                songKeyAliases = listOf(previousKey, "enriched-$token")
            ),
            Color.Red
        )
        repeat(8) {
            advance(16)
            assertFixedBounds(initial, capture())
        }
    }

    @Test
    fun rapidTrackChangesKeepFixedBoundsAndFinishWithTheLatestArtwork() {
        setHost()
        val initial = capture()
        publish(source("second"), Color.Blue)
        repeat(3) {
            advance(16)
            assertFixedBounds(initial, capture())
        }
        publish(source("third"), Color.Green)
        repeat(24) {
            advance(16)
            assertFixedBounds(initial, capture())
        }
        val final = capture()
        assertFixedBounds(initial, final)
        assertColor(Color.Green, final.center)
        assertUnchangedLayout()
    }

    private fun setHost(tabletLandscape: Boolean = false, settle: Boolean = true) {
        val configuration = Configuration(
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        ).apply {
            orientation = if (tabletLandscape) Configuration.ORIENTATION_LANDSCAPE
            else Configuration.ORIENTATION_PORTRAIT
            smallestScreenWidthDp = if (tabletLandscape) 800 else 360
            screenWidthDp = if (tabletLandscape) 1280 else 360
            screenHeightDp = 800
        }
        composeRule.setContent {
            owner = rememberNowPlayingCoverOwner()
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(1f)
            ) {
                MaterialTheme(colorScheme = lightColorScheme(
                    primaryContainer = Color.Black, surface = Color.White, onSurface = Color.White
                )) {
                    Box(
                        modifier = Modifier.size(240.dp).background(Color.Black).testTag(HostTag),
                        contentAlignment = Alignment.Center
                    ) {
                        if (mounted) {
                            SharedTransitionLayout {
                                AnimatedVisibility(visible = true) {
                                    key(generation) {
                                        NowPlayingCoverPanel(
                                            owner, source, null, null, true,
                                            this@SharedTransitionLayout, this@AnimatedVisibility,
                                            true, false, PlaybackSourceType.LOCAL, {},
                                            Modifier.size(200.dp).onSizeChanged { measuredSizes += it },
                                            preferredSize = 200.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        frame()
        publish(source, Color.Red)
        composeRule.runOnIdle { mounted = true }
        frame()
        if (settle) advance(400)
    }

    private fun assertTrackChangeStability() {
        val initial = capture()
        assertEquals("初始封面完整可见", 200f, initial.width, 1f)
        assertEquals("初始封面完整可见", 200f, initial.height, 1f)
        publish(source("second"), Color.Blue)
        repeat(24) {
            advance(16)
            assertFixedBounds(initial, capture())
        }
        val final = capture()
        assertFixedBounds(initial, final)
        assertColor(Color.Blue, final.center)
        assertUnchangedLayout()
    }

    private fun publish(next: NowPlayingCoverSource, color: Color) {
        composeRule.runOnIdle {
            val request = checkNotNull(owner.requestFor(
                NowPlayingCoverRequestInput(next.coverUrl, next.songKey, next.cacheKey)
            ))
            owner.presentation(request, next.songKey, next.songKeyAliases)
            owner.publishDecodedFrame(request, request.frame.copy(decodedBitmap = bitmap(color)))
            source = next
        }
    }

    private fun source(id: String): NowPlayingCoverSource {
        val key = "track-stability-$token-$id"
        return NowPlayingCoverSource("content://track-stability/$key", key, listOf(key), "cache-$key", id)
    }

    private fun bitmap(color: Color): ImageBitmap = ImageBitmap(16, 16).also {
        Canvas(it).drawRect(Rect(0f, 0f, 16f, 16f), Paint().apply { this.color = color })
    }

    private fun capture(): CoverFrame {
        val pixels = composeRule.onNodeWithTag(HostTag).captureToImage().toPixelMap()
        val row = pixels.height / 2
        val column = pixels.width / 2
        val artworkXs = (0 until pixels.width).filter { x ->
            val color = pixels[x, row]
            maxOf(color.red, color.green, color.blue) > 0.1f
        }
        assertTrue("封面中心行必须有实际图片像素", artworkXs.isNotEmpty())
        val artworkYs = (0 until pixels.height).filter { y ->
            val color = pixels[column, y]
            maxOf(color.red, color.green, color.blue) > 0.1f
        }
        assertTrue("封面中心列必须有实际图片像素", artworkYs.isNotEmpty())
        var left = pixels.width
        var top = pixels.height
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (minOf(color.red, color.green, color.blue) > 0.65f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("真实来源标签必须有白色像素", right >= left && bottom >= top)
        return CoverFrame(
            width = (artworkXs.last() - artworkXs.first() + 1).toFloat(),
            height = (artworkYs.last() - artworkYs.first() + 1).toFloat(),
            centerX = (artworkXs.first() + artworkXs.last() + 1) / 2f,
            centerY = (artworkYs.first() + artworkYs.last() + 1) / 2f,
            badge = Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat()),
            center = pixels[pixels.width / 2, pixels.height / 2]
        )
    }

    private fun assertFixedBounds(initial: CoverFrame, current: CoverFrame) {
        assertEquals("切歌期间封面宽度固定", initial.width, current.width, 1f)
        assertEquals("切歌期间封面高度固定", initial.height, current.height, 1f)
        assertEquals("封面横向中心不漂移", initial.centerX, current.centerX, 1f)
        assertEquals("封面纵向中心不漂移", initial.centerY, current.centerY, 1f)
        assertEquals("来源标签横向位置固定", initial.badge.left, current.badge.left, 1f)
        assertEquals("来源标签纵向位置固定", initial.badge.top, current.badge.top, 1f)
        assertEquals("来源标签宽度固定", initial.badge.width, current.badge.width, 1f)
        assertEquals("来源标签高度固定", initial.badge.height, current.badge.height, 1f)
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals("当前封面红色分量", expected.red, actual.red, 0.05f)
        assertEquals("当前封面绿色分量", expected.green, actual.green, 0.05f)
        assertEquals("当前封面蓝色分量", expected.blue, actual.blue, 0.05f)
    }

    private fun assertUnchangedLayout() {
        assertTrue("必须实际测量封面面板", measuredSizes.isNotEmpty())
        assertTrue("切歌不改变面板布局尺寸", measuredSizes.all { it == IntSize(200, 200) })
    }

    private fun frame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        val end = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < end) frame()
    }

    private data class CoverFrame(
        val width: Float, val height: Float, val centerX: Float, val centerY: Float,
        val badge: Rect, val center: Color
    )

    private companion object {
        const val HostTag = "cover-track-stability-host"
    }
}
