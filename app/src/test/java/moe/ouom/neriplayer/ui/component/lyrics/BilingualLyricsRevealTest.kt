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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingLyricsPane
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricActions
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricPlayback
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricStyle
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.buildNowPlayingSyncedLyricContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BilingualLyricsRevealTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val bilingualLine = LyricEntry(
        text = "MMMMMMMMMM\nMMMMMMMMMM",
        startTimeMs = 0L,
        endTimeMs = 10_000L
    )

    @Test
    fun `explicit paragraphs both reveal at quarter progress`() {
        val image = renderActiveLine(bilingualLine, currentTimeMs = 2_500L)

        for (rows in image.paragraphRows()) {
            assertTrue("each paragraph must contain highlighted glyphs", image.redColumns(rows).isNotEmpty())
            assertTrue("each paragraph must retain trailing dimmed glyphs", image.blueColumns(rows).isNotEmpty())
        }
    }

    @Test
    fun `explicit paragraphs are both fully highlighted at the end`() {
        val image = renderActiveLine(bilingualLine, currentTimeMs = 10_000L)

        for (rows in image.paragraphRows()) {
            assertTrue("each paragraph must be painted", image.redColumns(rows).isNotEmpty())
            assertEquals(emptyList<Int>(), image.blueColumns(rows))
        }
    }

    @Test
    fun `soft wrapped text still reveals the first visual row before the second`() {
        val line = LyricEntry(
            text = "MMMMMM MMMMMM",
            startTimeMs = 0L,
            endTimeMs = 10_000L
        )
        val image = renderActiveLine(line, currentTimeMs = 2_500L, width = 150.dp)
        val (topRows, bottomRows) = image.paragraphRows()

        assertTrue(image.redColumns(topRows).isNotEmpty())
        assertTrue(image.blueColumns(topRows).isNotEmpty())
        assertEquals(emptyList<Int>(), image.redColumns(bottomRows))
        assertTrue(image.blueColumns(bottomRows).isNotEmpty())
    }

    @Test
    fun `word timing keeps the second explicit paragraph dimmed before its word starts`() {
        val image = renderActiveLine(wordTimedLine(), currentTimeMs = 2_500L)
        val (topRows, bottomRows) = image.paragraphRows()

        assertTrue(image.redColumns(topRows).isNotEmpty())
        assertTrue(image.blueColumns(topRows).isNotEmpty())
        assertEquals(emptyList<Int>(), image.redColumns(bottomRows))
        assertTrue(image.blueColumns(bottomRows).isNotEmpty())
    }

    @Test
    fun `word timing reveals the second explicit paragraph only after the first word finishes`() {
        val image = renderActiveLine(wordTimedLine(), currentTimeMs = 7_500L)
        val (topRows, bottomRows) = image.paragraphRows()

        assertTrue(image.redColumns(topRows).isNotEmpty())
        assertEquals(emptyList<Int>(), image.blueColumns(topRows))
        assertTrue(image.redColumns(bottomRows).isNotEmpty())
        assertTrue(image.blueColumns(bottomRows).isNotEmpty())
    }

    @Test
    fun `embedded plain mode keeps flattened word timed paragraphs sequential`() {
        val content = buildNowPlayingSyncedLyricContent(
            listOf(wordTimedLine()), emptyList(), false, false, null, 0L
        )
        lateinit var hostView: View
        composeRule.setContent {
            hostView = LocalView.current
            Box(Modifier.width(320.dp).background(Color.Black)) {
                NowPlayingLyricsPane(
                    content = content,
                    style = NowPlayingSyncedLyricStyle(
                        Color.Red, 24.sp, 14.sp, LyricVisualSpec(), false, 0f
                    ),
                    playback = NowPlayingSyncedLyricPlayback(
                        MutableStateFlow(2_500L), null, false, 1f
                    ),
                    actions = NowPlayingSyncedLyricActions({}, {}),
                    modifier = Modifier.width(320.dp)
                )
            }
        }
        composeRule.waitForIdle()
        val bounds = composeRule.onAllNodesWithText(bilingualLine.text, useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        val image = captureLine(hostView, bounds)
        val (topRows, bottomRows) = image.paragraphRows()

        assertTrue(image.brightRedColumns(topRows).isNotEmpty())
        assertEquals(emptyList<Int>(), image.brightRedColumns(bottomRows))
        assertTrue(image.redColumns(bottomRows, minRed = 0.1f).isNotEmpty())
    }

    private fun wordTimedLine(): LyricEntry = bilingualLine.copy(
        words = listOf(
            WordTiming(startTimeMs = 0L, endTimeMs = 5_000L, charCount = 11),
            WordTiming(startTimeMs = 5_000L, endTimeMs = 10_000L, charCount = 10)
        )
    )

    private fun renderActiveLine(
        line: LyricEntry,
        currentTimeMs: Long,
        width: Dp = 320.dp
    ): ImageBitmap {
        lateinit var hostView: View
        composeRule.setContent {
            hostView = LocalView.current
            Box(Modifier.width(width).background(Color.Black).testTag("bilingual-line")) {
                SyncedLyricsActiveLine(
                    line = line,
                    currentTimeMs = currentTimeMs,
                    activeColor = Color.Red,
                    inactiveColor = Color.Blue,
                    fontSize = 24.sp,
                    fadeWidth = 0.dp,
                    animateProgress = false
                )
            }
        }
        val bounds = composeRule.onNodeWithTag("bilingual-line").fetchSemanticsNode().boundsInRoot
        return captureLine(hostView, bounds)
    }

    private fun captureLine(hostView: View, bounds: Rect): ImageBitmap =
        composeRule.runOnIdle {
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

    private fun ImageBitmap.paragraphRows(): List<IntRange> = listOf(
        0 until height / 2,
        height / 2 until height
    )

    private fun ImageBitmap.redColumns(rows: IntRange, minRed: Float = 0.5f): List<Int> {
        val pixels = toPixelMap()
        return (0 until width).filter { x ->
            rows.any { y -> pixels[x, y].let { it.red > minRed && it.blue < 0.3f } }
        }
    }

    private fun ImageBitmap.brightRedColumns(rows: IntRange): List<Int> {
        val pixels = toPixelMap()
        return (0 until width).filter { x ->
            rows.any { y -> pixels[x, y].let { it.red > 0.7f && it.blue < 0.1f } }
        }
    }

    private fun ImageBitmap.blueColumns(rows: IntRange): List<Int> {
        val pixels = toPixelMap()
        return (0 until width).filter { x ->
            rows.any { y -> pixels[x, y].let { it.blue > 0.5f && it.red < 0.3f } }
        }
    }
}
