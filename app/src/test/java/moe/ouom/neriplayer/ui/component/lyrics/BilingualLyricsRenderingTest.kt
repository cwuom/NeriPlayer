package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilingualLyricsRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val combinedText = "Where do we go\n我们去哪里"

    @Test
    fun `advanced raw lrc groups simultaneous bilingual lines for the whole interval`() {
        for (translationStart in listOf("20.50", "20.53")) {
            val raw = bilingualLrc(translationStart)
            val result = buildAdvancedSyncedLyrics(raw, null, parseNeteaseLrc(raw), emptyList())

            assertEquals(2, result.lines.size)
            val first = result.lines.first() as SyncedLine
            assertEquals(combinedText, first.content)
            assertEquals(20_500, first.start)
            assertEquals(24_000, first.end)
        }
    }

    @Test
    fun `advanced fallback entries split mixed sentences without depending on translation mode`() {
        val source = listOf(LyricEntry("Where do we go 我们去哪里", 20_500, 24_000))

        for (phonetic in listOf(false, true)) {
            val result = buildAdvancedSyncedLyrics(null, "", source, emptyList(), phonetic)

            assertEquals(combinedText, (result.lines.single() as SyncedLine).content)
            assertEquals("Where do we go 我们去哪里", source.single().text)
        }
    }

    @Test
    fun `embedded bilingual block stays active until the following sentence and reports source entries`() {
        val source = parseNeteaseLrc(bilingualLrc("20.53"))
        val position = mutableLongStateOf(20_500L)
        var clicked: LyricEntry? = null
        var pressed: LyricEntry? = null
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SyncedLyricsView(
                    lyrics = source,
                    currentTimeMs = position.longValue,
                    lyricBlurEnabled = false,
                    visualEffectsEnabled = false,
                    smoothActiveLineProgress = false,
                    stableEmbeddedViewport = true,
                    onLyricClick = { clicked = it },
                    onLyricLongClick = { pressed = it }
                )
            }
        }

        for (time in listOf(20_500L, 20_530L, 23_900L)) {
            position.longValue = time
            composeRule.waitForIdle()
            assertEquals(3, textNodeCount(combinedText))
        }
        composeRule.onAllNodesWithText(combinedText)[0].performClick()
        composeRule.onAllNodesWithText(combinedText)[0].performTouchInput { longClick() }
        assertSame(source.first(), clicked)
        assertSame(source.first(), pressed)

        position.longValue = 24_000L
        composeRule.waitForIdle()
        assertEquals(1, textNodeCount(combinedText))
        assertEquals(3, textNodeCount("Next line"))
    }

    @Test
    fun `embedded mixed sentence uses explicit language boundary`() {
        composeRule.setContent {
            SyncedLyricsView(
                lyrics = listOf(LyricEntry("Where do we go 我们去哪里", 0L, 4_000L)),
                currentTimeMs = 1_000L,
                modifier = Modifier.size(400.dp, 600.dp),
                lyricBlurEnabled = false,
                visualEffectsEnabled = false,
                smoothActiveLineProgress = false
            )
        }

        composeRule.waitForIdle()
        assertEquals(3, textNodeCount(combinedText))
    }

    @Test
    fun `pressing a bilingual block skips preceding metadata with the same timestamp`() {
        val source = listOf(
            LyricEntry("OP：某公司", 1_000L, 1_000L),
            LyricEntry("Where do we go", 1_000L, 1_000L),
            LyricEntry("我们去哪里", 1_000L, 4_000L),
            LyricEntry("Next line", 4_000L, 8_000L)
        )
        var pressed: LyricEntry? = null
        composeRule.setContent {
            SyncedLyricsView(
                lyrics = source,
                currentTimeMs = 1_500L,
                modifier = Modifier.size(400.dp, 800.dp),
                lyricBlurEnabled = false,
                visualEffectsEnabled = false,
                smoothActiveLineProgress = false,
                onLyricLongClick = { pressed = it }
            )
        }

        composeRule.onAllNodesWithText(combinedText)[0].performTouchInput { longClick() }

        assertSame(source[1], pressed)
    }

    private fun textNodeCount(text: String): Int =
        composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun bilingualLrc(translationStart: String): String = """
        [00:20.50]Where do we go
        [00:$translationStart]我们去哪里
        [00:24.00]Next line
    """.trimIndent()
}
