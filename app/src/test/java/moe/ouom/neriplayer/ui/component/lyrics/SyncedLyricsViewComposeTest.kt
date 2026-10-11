package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncedLyricsViewComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val lyrics = List(8) { index ->
        LyricEntry(
            text = "Line $index",
            startTimeMs = index * 1_000L,
            endTimeMs = (index + 1) * 1_000L
        )
    }
    private val translations = List(8) { index ->
        LyricEntry(
            text = "Trans $index",
            startTimeMs = index * 1_000L,
            endTimeMs = (index + 1) * 1_000L
        )
    }

    @Test
    fun `only the active line shows its translation and taps report the tapped line`() {
        val clicked = mutableListOf<LyricEntry>()
        val longClicked = mutableListOf<LyricEntry>()
        composeRule.setContent {
            SyncedLyricsView(
                lyrics = lyrics,
                currentTimeMs = 2_500L,
                modifier = Modifier.size(360.dp, 640.dp),
                translatedLyrics = translations,
                onLyricClick = { clicked += it },
                onLyricLongClick = { longClicked += it }
            )
        }

        composeRule.onNodeWithText("Trans 2").assertExists()
        composeRule.onNodeWithText("Trans 1").assertDoesNotExist()
        composeRule.onNodeWithText("Trans 3").assertDoesNotExist()

        composeRule.onNodeWithText("Line 4").performClick()
        composeRule.onNodeWithText("Line 1").performTouchInput { longClick() }

        assertEquals(listOf(lyrics[4]), clicked)
        assertEquals(listOf(lyrics[1]), longClicked)
    }

    @Test
    fun `translation follows playback to the next active line`() {
        var positionMs by mutableLongStateOf(2_500L)
        composeRule.setContent {
            SyncedLyricsView(
                lyrics = lyrics,
                currentTimeMs = positionMs,
                modifier = Modifier.size(360.dp, 640.dp),
                translatedLyrics = translations,
                isPlaying = true
            )
        }
        composeRule.onNodeWithText("Trans 2").assertExists()

        positionMs = 3_400L
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Trans 3").assertExists()
        composeRule.onNodeWithText("Trans 2").assertDoesNotExist()
    }

    @Test
    fun `manual scroll reveals every translation until playback moves on`() {
        var positionMs by mutableLongStateOf(2_500L)
        composeRule.setContent {
            SyncedLyricsView(
                lyrics = lyrics,
                currentTimeMs = positionMs,
                modifier = Modifier
                    .size(360.dp, 640.dp)
                    .testTag("lyrics"),
                translatedLyrics = translations
            )
        }

        composeRule.onNodeWithTag("lyrics").performTouchInput {
            swipeUp(startY = centerY + 60f, endY = centerY - 60f)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Trans 3").assertExists()
        composeRule.onNodeWithText("Trans 4").assertExists()

        positionMs = 3_500L
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Trans 3").assertExists()
        composeRule.onNodeWithText("Trans 4").assertDoesNotExist()
    }

    @Test
    fun `embedded view uses inline translations only when enabled`() {
        val embeddedLyrics = lyrics.mapIndexed { index, line ->
            line.copy(translation = "Inline $index")
        }
        var showTranslations by mutableStateOf(true)
        composeRule.setContent {
            SyncedLyricsView(
                lyrics = embeddedLyrics,
                currentTimeMs = 1_200L,
                modifier = Modifier.size(360.dp, 400.dp),
                lyricBlurEnabled = false,
                interpolatePlaybackPosition = true,
                visualEffectsEnabled = false,
                edgeFadeHeight = resolveLyricEdgeFadeHeight(isEmbedded = true),
                showEmbeddedTranslations = showTranslations,
                playbackSessionKey = "song-a",
                stableEmbeddedViewport = true
            )
        }

        composeRule.onNodeWithText("Inline 1").assertExists()
        composeRule.onNodeWithText("Inline 2").assertDoesNotExist()

        showTranslations = false
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Inline 1").assertDoesNotExist()
    }

    @Test
    fun `multi line translation renders under a blank lyric with default sizing`() {
        val translation = "First\n\nSecond"
        composeRule.setContent {
            SyncedLyricsView(
                lyrics = listOf(
                    LyricEntry(text = "", startTimeMs = 0L, endTimeMs = 5_000L, translation = translation)
                ),
                currentTimeMs = 1_000L,
                modifier = Modifier.size(360.dp, 400.dp),
                fontSize = TextUnit.Unspecified,
                translationFontSize = TextUnit.Unspecified
            )
        }

        composeRule.onNodeWithText(translation).assertExists()
    }

    @Test
    fun `active line keeps a dimmed base and a revealed copy of word timed text`() {
        val wordTimed = LyricEntry(
            text = "こんにちは world",
            startTimeMs = 0L,
            endTimeMs = 2_000L,
            words = listOf(
                WordTiming(0L, 1_000L, charCount = 6),
                WordTiming(1_000L, 2_000L, charCount = 5)
            )
        )
        composeRule.setContent {
            SyncedLyricsActiveLine(
                line = wordTimed,
                currentTimeMs = 900L,
                activeColor = Color.White,
                inactiveColor = Color.Gray,
                fontSize = TextUnit.Unspecified,
                lyricOffsetMs = 100L,
                isPlaying = true
            )
        }

        composeRule.onAllNodesWithText("こんにちは world").assertCountEquals(2)
    }
}
