package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingDialogsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fillOptionsDialog_confirmsAllEnabledByDefault() {
        val context = targetContext
        var confirmArgs: List<Boolean>? = null

        composeRule.setContent {
            MaterialTheme {
                Box {
                    FillOptionsDialog(
                        songResult = demoSearchSong(),
                        onDismiss = { },
                        onConfirm = { fillCover, fillTitle, fillArtist, fillLyrics ->
                            confirmArgs = listOf(fillCover, fillTitle, fillArtist, fillLyrics)
                        }
                    )
                }
            }
        }

        waitForText("夜航星")
        waitForText("不才")
        composeRule.onNodeWithText(context.getString(R.string.action_confirm)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(true, true, true, true), confirmArgs)
        }
    }

    @Test
    fun fillOptionsDialog_toggledOptionsAreReturnedOnConfirm() {
        val context = targetContext
        var confirmArgs: List<Boolean>? = null

        composeRule.setContent {
            MaterialTheme {
                Box {
                    FillOptionsDialog(
                        songResult = demoSearchSong(),
                        onDismiss = { },
                        onConfirm = { fillCover, fillTitle, fillArtist, fillLyrics ->
                            confirmArgs = listOf(fillCover, fillTitle, fillArtist, fillLyrics)
                        }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.music_auto_fill_cover))
        composeRule.onNodeWithText(context.getString(R.string.music_auto_fill_cover)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.music_auto_fill_lyrics)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_confirm)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(false, true, true, false), confirmArgs)
        }
    }

    @Test
    fun lyricsEditorSheet_switchesTabsAndClearOnlyAffectsCurrentTab() {
        val context = targetContext

        composeRule.setContent {
            MaterialTheme {
                Box {
                    LyricsEditorSheet(
                        originalSong = demoSongItem(),
                        initialLyrics = "原文A",
                        initialTranslatedLyrics = "译文B",
                        onDismiss = { }
                    )
                }
            }
        }

        waitForText("原文A")
        composeRule.onNodeWithText(context.getString(R.string.action_clear)).performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("原文A").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithText(context.getString(R.string.lyrics_translation)).performClick()
        waitForText("译文B")
        composeRule.onNodeWithText(context.getString(R.string.action_clear)).performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("译文B").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun lyricFontSizeSheet_doneCommitsScaleAndDismisses() {
        val context = targetContext
        val committedScales = mutableListOf<Float>()
        var dismissed = false

        composeRule.setContent {
            MaterialTheme {
                Box {
                    LyricFontSizeSheet(
                        currentScale = 1.25f,
                        onScaleCommit = { committedScales += it },
                        onDismiss = { dismissed = true }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.nowplaying_lyrics_sample))
        composeRule.onNodeWithText(context.getString(R.string.action_done)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(1.25f), committedScales)
            assertTrue(dismissed)
        }
    }

    @Test
    fun lyricOffsetSheet_showsCurrentOffsetAndDismisses() {
        val context = targetContext
        var dismissed = false

        composeRule.setContent {
            MaterialTheme {
                Box {
                    LyricOffsetSheet(
                        song = demoSongItem().copy(userLyricOffsetMs = 150L),
                        onDismiss = { dismissed = true }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.lyrics_adjust_offset))
        composeRule.onNodeWithText(context.getString(R.string.action_done)).performClick()

        composeRule.runOnIdle {
            assertTrue(dismissed)
        }
    }

    @Test
    fun qualityOptionsDialog_marksSelectedOptionAndRoutesSelection() {
        val context = targetContext
        val options = listOf(
            PlaybackQualityOption(key = "high", label = "高品质"),
            PlaybackQualityOption(key = "lossless", label = "无损"),
            PlaybackQualityOption(key = "master", label = "Hi-Res")
        )
        var selectedKey: String? = null
        var dismissed = false

        composeRule.setContent {
            MaterialTheme {
                Box {
                    NowPlayingQualityOptionsDialog(
                        title = context.getString(R.string.nowplaying_quality_switch_title),
                        selectedKey = "lossless",
                        options = options,
                        onDismiss = { dismissed = true },
                        onSelect = { option -> selectedKey = option.key }
                    )
                }
            }
        }

        waitForText("无损")
        waitForText(context.getString(R.string.common_selected))
        composeRule.onNodeWithText("Hi-Res").performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_close)).performClick()

        composeRule.runOnIdle {
            assertEquals("master", selectedKey)
            assertTrue(dismissed)
        }
    }

    private fun demoSearchSong(): SongSearchInfo {
        return SongSearchInfo(
            id = "song-1",
            songName = "夜航星",
            singer = "不才",
            duration = "03:42",
            source = MusicPlatform.CLOUD_MUSIC,
            albumName = "海上钢琴师",
            coverUrl = "https://example.com/cover.jpg"
        )
    }

    private fun demoSongItem(): SongItem {
        return SongItem(
            id = 1L,
            name = "夜航星",
            artist = "不才",
            album = "专辑A",
            albumId = 10L,
            durationMs = 222_000L,
            coverUrl = null,
            matchedLyric = "原文A",
            matchedTranslatedLyric = "译文B"
        )
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
