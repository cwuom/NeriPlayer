package moe.ouom.neriplayer.ui.component.lyrics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncedLyricsActiveLineRevealTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val line = LyricEntry(text = "MMMMMMMMMM", startTimeMs = 0L, endTimeMs = 10_000L)

    private fun renderActiveLine(
        currentTimeMs: Long,
        fadeWidth: Dp,
        interpolatePlaybackPosition: Boolean = false,
        line: LyricEntry = this.line,
        width: Dp = 320.dp
    ): ImageBitmap {
        lateinit var hostView: View
        composeRule.setContent {
            hostView = LocalView.current
            Box(
                Modifier
                    .width(width)
                    .background(Color.Black)
                    .testTag("line")
            ) {
                SyncedLyricsActiveLine(
                    line = line,
                    currentTimeMs = currentTimeMs,
                    activeColor = Color.Red,
                    inactiveColor = Color.Blue,
                    fontSize = 24.sp,
                    fadeWidth = fadeWidth,
                    interpolatePlaybackPosition = interpolatePlaybackPosition,
                    animateProgress = false
                )
            }
        }
        val bounds = composeRule.onNodeWithTag("line").fetchSemanticsNode().boundsInRoot
        return composeRule.runOnIdle {
            val bitmap = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
            hostView.draw(Canvas(bitmap))
            Bitmap.createBitmap(
                bitmap,
                bounds.left.toInt(),
                bounds.top.toInt(),
                bounds.width.toInt(),
                bounds.height.toInt()
            ).asImageBitmap()
        }
    }

    private fun ImageBitmap.redColumns(rows: IntRange = 0 until height): List<Int> {
        val pixels = toPixelMap()
        return (0 until width).filter { x ->
            rows.any { y ->
                val c = pixels[x, y]
                c.red > 0.5f && c.blue < 0.3f
            }
        }
    }

    private fun ImageBitmap.blueColumns(rows: IntRange = 0 until height): List<Int> {
        val pixels = toPixelMap()
        return (0 until width).filter { x ->
            rows.any { y ->
                val c = pixels[x, y]
                c.blue > 0.5f && c.red < 0.3f
            }
        }
    }

    @Test
    fun `half progress highlights only the leading half of the line`() {
        val image = renderActiveLine(currentTimeMs = 5_000L, fadeWidth = 0.dp)

        val red = image.redColumns()
        val blue = image.blueColumns()
        assertTrue("expected highlighted glyphs", red.isNotEmpty())
        assertTrue("expected dimmed glyphs", blue.isNotEmpty())
        assertTrue(red.max() < blue.max())
        assertTrue(red.max() <= image.width / 2 + 4)
    }

    @Test
    fun `soft fade still keeps the trailing glyphs dimmed`() {
        val image = renderActiveLine(currentTimeMs = 3_000L, fadeWidth = 12.dp)

        val red = image.redColumns()
        val blue = image.blueColumns()
        assertTrue(red.isNotEmpty())
        assertTrue(red.max() < blue.max())
    }

    @Test
    fun `finished line is fully highlighted`() {
        val image = renderActiveLine(currentTimeMs = 10_000L, fadeWidth = 12.dp)

        assertEquals(emptyList<Int>(), image.blueColumns())
        assertTrue(image.redColumns().isNotEmpty())
    }

    @Test
    fun `interpolated playback reveals from the rendered position at draw time`() {
        val image = renderActiveLine(
            currentTimeMs = 5_000L,
            fadeWidth = 0.dp,
            interpolatePlaybackPosition = true
        )

        val red = image.redColumns()
        val blue = image.blueColumns()
        assertTrue(red.isNotEmpty())
        assertTrue(red.max() < blue.max())
    }

    @Test
    fun `wrapped line highlights completed rows fully and the current row partially`() {
        val wrapped = LyricEntry(text = "MMMMMM MMMMMM", startTimeMs = 0L, endTimeMs = 10_000L)
        val image = renderActiveLine(
            currentTimeMs = 8_000L,
            fadeWidth = 12.dp,
            line = wrapped,
            width = 150.dp
        )
        val topRows = 0 until image.height / 2
        val bottomRows = image.height / 2 until image.height

        assertTrue(image.redColumns(topRows).isNotEmpty())
        assertEquals(emptyList<Int>(), image.blueColumns(topRows))
        assertTrue(image.redColumns(bottomRows).isNotEmpty())
        assertTrue(image.blueColumns(bottomRows).isNotEmpty())
    }

    @Test
    fun `blank active line draws no glyphs`() {
        val image = renderActiveLine(
            currentTimeMs = 5_000L,
            fadeWidth = 12.dp,
            line = LyricEntry(text = "", startTimeMs = 0L, endTimeMs = 10_000L)
        )

        assertEquals(emptyList<Int>(), image.redColumns())
        assertEquals(emptyList<Int>(), image.blueColumns())
    }
}
