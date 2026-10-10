package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncedLyricsViewActiveLineTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val lyrics = (0 until 8).map { index ->
        LyricEntry(
            text = "lyric line $index",
            startTimeMs = index * 4_000L,
            endTimeMs = index * 4_000L + 3_900L
        )
    }

    @Test
    fun `crossing a line boundary moves the highlight to the next line`() {
        val position = mutableLongStateOf(1_000L)
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SyncedLyricsView(
                    lyrics = lyrics,
                    currentTimeMs = position.longValue,
                    lyricBlurEnabled = false,
                    interpolatePlaybackPosition = true,
                    visualEffectsEnabled = false,
                    smoothActiveLineProgress = false,
                    edgeFadeHeight = resolveLyricEdgeFadeHeight(isEmbedded = true),
                    stableEmbeddedViewport = true
                )
            }
        }
        composeRule.waitForIdle()
        assertActiveLine(0)

        position.longValue = 4_100L
        composeRule.waitForIdle()

        assertActiveLine(1)
        assertEquals(1, textNodeCount("lyric line 0"))
    }

    // 当前行由底版、高亮和清晰态三层文本组成，其余行只有一层
    private fun assertActiveLine(index: Int) {
        assertEquals(3, textNodeCount("lyric line $index"))
    }

    private fun textNodeCount(text: String): Int =
        composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
}
