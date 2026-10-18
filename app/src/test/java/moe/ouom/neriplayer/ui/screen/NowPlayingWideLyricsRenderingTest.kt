package moe.ouom.neriplayer.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLyricsContent
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLyricsPreferences
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricActions
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricContent
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricPlayback
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingWideLyricsRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fullPage = mutableStateOf(false)
    private val advancedEnabled = mutableStateOf(false)
    private val seeks = mutableListOf<Long>()
    private val readingLine = LyricEntry("Tablet lyrics fixture", 6_000L, 10_000L)
    private val lines = listOf(LyricEntry("Previous fixture line", 1_000L, 5_000L), readingLine)
    private val position = MutableStateFlow(1_500L)

    @Test
    fun narrowTabletKeepsTheFullAdvancedReadingStyleAcrossPageAndPreferenceChanges() {
        verifyTabletReadingStyle(600.dp)
    }

    @Test
    fun wideTabletKeepsTheFullAdvancedReadingStyleAcrossPageAndPreferenceChanges() {
        verifyTabletReadingStyle(1280.dp)
    }

    private fun verifyTabletReadingStyle(width: Dp) {
        render(width)
        val original = assertFullAdvancedStyle()
        listOf(true to false, true to true, false to true, false to false).forEach { (page, advanced) ->
            composeRule.runOnIdle {
                fullPage.value = page
                advancedEnabled.value = advanced
            }
            val current = assertFullAdvancedStyle()
            assertEquals(original.layoutInput.style, current.layoutInput.style)
            assertEquals(original.size, current.size)
        }
        composeRule.onNodeWithText(readingLine.text, useUnmergedTree = true)
            .performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(listOf(readingLine.startTimeMs), seeks) }
    }

    private fun assertFullAdvancedStyle(): TextLayoutResult {
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText(readingLine.text, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val line = composeRule.onNodeWithText(readingLine.text, useUnmergedTree = true)
            .assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        line.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertEquals(24f * 1.3f, result.layoutInput.style.fontSize.value, 0.001f)
        assertEquals(FontWeight.Bold, result.layoutInput.style.fontWeight)
        assertEquals(TextAlign.Start, result.layoutInput.style.textAlign)
        return result
    }

    private fun render(width: Dp) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width, 600.dp)) {
                    NowPlayingWideLyrics(
                        fullPage = fullPage.value,
                        compactHeight = false,
                        phoneLandscape = false,
                        availableWidth = width,
                        content = NowPlayingWideLyricsContent(
                            lyrics = lines,
                            translatedLyrics = emptyList(),
                            phoneticLyrics = emptyList(),
                            plainTranslatedLyrics = emptyList(),
                            rawLyrics = null,
                            rawTranslatedLyrics = null,
                            synced = NowPlayingSyncedLyricContent(
                                lines, null, false, "tablet-rendering-fixture", 0L
                            )
                        ),
                        preferences = NowPlayingWideLyricsPreferences(
                            advancedEnabled = advancedEnabled.value,
                            showSecondary = false,
                            usePhonetic = false,
                            fontScales = LyricFontScales(0.9f, 1.1f, 1.3f, 1.5f),
                            blurEnabled = false,
                            blurAmount = 0f
                        ),
                        playback = NowPlayingSyncedLyricPlayback(
                            position, 1_500L, false, 1f
                        ),
                        lowPowerRendering = true,
                        actions = NowPlayingSyncedLyricActions({}, {}),
                        onSeekTo = { seeks += it }
                    )
                }
            }
        }
    }
}
