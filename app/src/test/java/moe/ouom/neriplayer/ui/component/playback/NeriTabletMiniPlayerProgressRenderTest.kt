package moe.ouom.neriplayer.ui.component.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeriTabletMiniPlayerProgressRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var hostView: View

    @Test
    fun thumbAndTrackShareAStableCenterAtRestAndWhileDragging() {
        val position = mutableStateOf(30_000L)
        composeRule.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta, onSecondaryContainer = Color.White)) {
                    Box(Modifier.requiredSize(240.dp, 80.dp).background(Color.Black)) {
                        TabletMiniPlayerContent(
                            controls = MiniPlayerTabletControls(
                                trackKey = "track",
                                positionMs = position.value,
                                durationMs = 120_000L,
                                seekEnabled = true,
                                shuffleEnabled = false,
                                repeatMode = Player.REPEAT_MODE_OFF,
                                onSeek = {}, onShuffle = {}, onRepeat = {}, onVolume = {},
                                onListenTogether = {}, onQueue = {}
                            ),
                            playPauseEnabled = true,
                            onPlayPause = {}, onPrevious = {}, onNext = {}, onExpand = {},
                            cover = {}, metadata = {}, playPauseIcon = {}
                        )
                    }
                }
            }
        }

        listOf(0f, 0.25f, 1f).forEach { progress ->
            composeRule.runOnIdle { position.value = (progress * 120_000L).toLong() }
            assertThumbCentered(progress, dragging = false)
        }
        val node = composeRule.onNodeWithTag("miniPlayerProgress")
        listOf(0f, 0.5f, 1f).forEach { progress ->
            node.performTouchInput {
                down(Offset(width * 0.25f, centerY))
                moveTo(Offset(width * progress, centerY), delayMillis = 100L)
            }
            assertThumbCentered(progress, dragging = true)
            node.performTouchInput { up() }
        }
    }

    private fun assertThumbCentered(progress: Float, dragging: Boolean) {
        val bounds = composeRule.onNodeWithTag("miniPlayerProgress").fetchSemanticsNode().boundsInRoot
        val pixels = composeRule.runOnIdle {
            val bitmap = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
            hostView.draw(Canvas(bitmap))
            Bitmap.createBitmap(bitmap, bounds.left.toInt(), bounds.top.toInt(),
                bounds.width.toInt(), bounds.height.toInt()).asImageBitmap().toPixelMap()
        }
        val radius = if (dragging) 10f else 6f
        val thumbX = (progress * pixels.width).coerceIn(radius, pixels.width - radius)
            .roundToInt().coerceIn(0, pixels.width - 1)
        val trackX = (pixels.width * 0.7f).toInt()
        val trackRows = (0 until pixels.height).filter { y -> pixels[trackX, y].red > 0.03f }
        val thumbRows = (0 until pixels.height).filter { y -> pixels[thumbX, y].red > 0.5f }
        assertTrue("进度线未绘制", trackRows.isNotEmpty())
        assertTrue("进度圆点未绘制", thumbRows.isNotEmpty())
        val trackCenter = (trackRows.first() + trackRows.last() + 1) / 2f
        val thumbCenter = (thumbRows.first() + thumbRows.last() + 1) / 2f
        val state = "progress=$progress dragging=$dragging"
        assertEquals("圆点与轨道中心不对齐: $state", trackCenter, thumbCenter, 1f)
        assertEquals("拖动时轨道应保持固定: $state", pixels.height / 2f, trackCenter, 1f)
        assertEquals("圆点被裁切: $state", radius * 2f, thumbRows.size.toFloat(), 1f)
    }
}
