package moe.ouom.neriplayer.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongSearchPlatforms
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongSearchResultCard
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricMatchResultsContent
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricsEditorContent
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsEditorOwner
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingEditorOverlaysComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val appearance = mutableStateOf(UiStyle.Clean to false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owners = mutableListOf<NowPlayingLyricsEditorOwner>()
    private val song = SongItem(
        id = 17L, name = "Fixture Song", artist = "Fixture Artist", album = "Album",
        albumId = 0L, durationMs = 120_000L, coverUrl = null
    )

    @After
    fun releaseOwners() {
        owners.forEach { it.dispose() }
        scope.cancel()
    }

    @Test
    fun lyricsTabsKeepTheirOwnDraftAndDisableSelectionWhileSavingInBothThemes() {
        val owner = NowPlayingLyricsEditorOwner(
            song, "Original draft", "Translated draft", "Romanized draft", scope,
            matchLyrics = { emptyList() }
        ).also(owners::add)
        composeRule.setContent {
            TestTheme {
                LyricsEditorContent(song, owner, {}, {}, {}, {})
            }
        }

        forEachAppearance { variant ->
            composeRule.runOnIdle { owner.isSavingState.value = false }
            val expected = listOf(owner.lyricsTextState, owner.translatedLyricsTextState,
                owner.romanizedLyricsTextState)
            for (index in 0..2) {
                composeRule.onNodeWithTag("lyrics-editor-tab-$index").performClick().assertIsSelected()
                composeRule.onNodeWithTag("lyrics-editor-input").assertTextEquals(expected[index].value)
                for (other in 0..2) {
                    if (other != index) {
                        composeRule.onNodeWithTag("lyrics-editor-tab-$other").assertIsNotSelected()
                    }
                }
            }
            val replacement = "Edited translation $variant"
            composeRule.onNodeWithTag("lyrics-editor-tab-1").performClick()
            composeRule.onNodeWithTag("lyrics-editor-input").performTextReplacement(replacement)
            composeRule.runOnIdle {
                assertEquals(replacement, owner.translatedLyricsTextState.value)
                assertEquals("Original draft", owner.lyricsTextState.value)
                assertEquals("Romanized draft", owner.romanizedLyricsTextState.value)
                owner.isSavingState.value = true
            }
            for (index in 0..2) {
                composeRule.onNodeWithTag("lyrics-editor-tab-$index").assertIsNotEnabled()
            }
            composeRule.onNodeWithTag("lyrics-editor-tab-1").assertIsSelected()
        }
    }

    @Test
    fun metadataPlatformTabsAndResultCardsPreserveSelectionAndCandidateIdentity() {
        val selected = mutableStateOf(MusicPlatform.CLOUD_MUSIC)
        val selectedPlatforms = mutableListOf<MusicPlatform>()
        val result = SongSearchInfo("result-17", "Matched title", "Matched artist", "2:00",
            MusicPlatform.CLOUD_MUSIC, "Matched album", null)
        val applied = mutableListOf<SongSearchInfo>()
        composeRule.setContent {
            TestTheme {
                Column {
                    EditSongSearchPlatforms(selected.value) {
                        selectedPlatforms += it
                        selected.value = it
                    }
                    EditSongSearchResultCard(result, offlineMode = true) { applied += result }
                }
            }
        }

        forEachAppearance {
            for (platform in MusicPlatform.entries) {
                val label = context.getString(if (platform == MusicPlatform.CLOUD_MUSIC) {
                    CoreCommonR.string.platform_netease_short
                } else CoreCommonR.string.settings_qq_music)
                composeRule.onNodeWithText(label).performClick().assertIsSelected()
                composeRule.runOnIdle {
                    assertEquals(platform, selected.value)
                    assertEquals(platform, selectedPlatforms.last())
                }
            }
            composeRule.onNodeWithText(result.singer).assertIsDisplayed()
            val before = applied.size
            composeRule.onNodeWithText(result.songName).assertIsDisplayed().performClick()
            composeRule.runOnIdle {
                assertEquals(before + 1, applied.size)
                assertSame(result, applied.last())
            }
        }
    }

    @Test
    fun matchingLyricsCardsApplyTheDisplayedCandidateInBothThemes() {
        val result = RankedEditableLyricMatch(
            EditableLyricMatchCandidate("candidate-17", EditableLyricMatchSource.KUGOU,
                "Matched lyric title", "Matched lyric artist", lyrics = "[00:01.00]A lyric"),
            score = 90, durationDeltaMs = 0L
        )
        val applied = mutableListOf<RankedEditableLyricMatch>()
        var dismissCount = 0
        composeRule.setContent {
            TestTheme {
                LyricMatchResultsContent(
                    query = "Fixture Song", onQueryChange = {}, results = listOf(result),
                    isLoading = false, errorMessage = null, hasSearched = true,
                    selectedSources = setOf(EditableLyricMatchSource.KUGOU),
                    onSourceToggle = {}, onSearch = {}, onApply = { applied += it },
                    onDismiss = { dismissCount++ }
                )
            }
        }

        forEachAppearance {
            composeRule.onNodeWithText(result.candidate.title).assertIsDisplayed()
            composeRule.onNodeWithText(result.candidate.artist).assertIsDisplayed()
            val before = applied.size
            composeRule.onNodeWithTag("lyrics-match-result-candidate-17").performClick()
            composeRule.runOnIdle {
                assertEquals(before + 1, applied.size)
                assertSame(result, applied.last())
            }
            composeRule.onNodeWithTag("lyrics-match-cancel").performClick()
        }
        composeRule.runOnIdle { assertEquals(4, dismissCount) }
    }

    private fun forEachAppearance(block: (String) -> Unit) {
        for ((style, dark) in listOf(UiStyle.Clean to false, UiStyle.Clean to true,
            UiStyle.Material to false, UiStyle.Material to true)) {
            composeRule.runOnIdle { appearance.value = style to dark }
            composeRule.waitForIdle()
            block("$style-$dark")
        }
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        val (style, dark) = appearance.value
        NeriTheme(
            followSystemDark = false, forceDark = dark, dynamicColor = false,
            seedColorHex = "D88467", uiStyle = style, animateColors = false
        ) {
            Surface(Modifier.fillMaxSize(), content = content)
        }
    }
}
