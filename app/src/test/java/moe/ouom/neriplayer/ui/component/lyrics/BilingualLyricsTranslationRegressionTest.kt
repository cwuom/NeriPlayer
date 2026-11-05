package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilingualLyricsTranslationRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val source = listOf(
        LyricEntry("First voice", 1_000L, 1_000L),
        LyricEntry("Second voice", 1_000L, 4_000L),
        LyricEntry("Next line", 4_000L, 8_000L)
    )
    private val translations = listOf(
        LyricEntry("第一声部译", 1_000L, 1_000L),
        LyricEntry("第二声部译", 1_000L, 4_000L),
        LyricEntry("下一句译", 4_000L, 8_000L)
    )
    private val combinedTranslation = "第一声部译\n第二声部译"

    @Test
    fun `advanced display retains every external translation from simultaneous source rows`() {
        val result = buildAdvancedSyncedLyrics(null, null, source, translations)

        assertEquals(2, result.lines.size)
        assertEquals("First voice\nSecond voice", (result.lines.first() as SyncedLine).content)
        assertEquals(combinedTranslation, (result.lines.first() as SyncedLine).translation)
        assertEquals("下一句译", (result.lines.last() as SyncedLine).translation)
    }

    @Test
    fun `advanced display retains every simultaneous raw translation`() {
        val result = buildAdvancedSyncedLyrics(
            rawLyrics = null,
            rawTranslatedLyrics = """
                [00:01.000]第一声部译
                [00:01.000]第二声部译
                [00:04.000]下一句译
            """.trimIndent(),
            lyrics = source,
            translatedLyrics = emptyList()
        )

        assertEquals(combinedTranslation, (result.lines.first() as SyncedLine).translation)
        assertEquals("下一句译", (result.lines.last() as SyncedLine).translation)
    }

    @Test
    fun `embedded display retains every external translation from simultaneous source rows`() {
        val position = mutableLongStateOf(1_500L)
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SyncedLyricsView(
                    lyrics = source,
                    translatedLyrics = translations,
                    currentTimeMs = position.longValue,
                    lyricBlurEnabled = false,
                    visualEffectsEnabled = false,
                    smoothActiveLineProgress = false
                )
            }
        }

        composeRule.onNodeWithText(combinedTranslation).assertExists()
        composeRule.runOnIdle { position.longValue = 4_500L }
        composeRule.onNodeWithText("下一句译").assertExists()
    }
}
