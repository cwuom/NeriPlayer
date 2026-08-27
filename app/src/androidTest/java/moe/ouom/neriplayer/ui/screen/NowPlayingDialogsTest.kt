package moe.ouom.neriplayer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import moe.ouom.neriplayer.ui.component.playback.NowPlayingCoverPreviewDialog
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingDialogsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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
    fun editSongMetadataWriteBackDialog_offersWriteAppOnlyAndCancel() {
        val context = targetContext
        var wroteToLocal = false
        var savedInAppOnly = false
        var cancelled = false

        composeRule.setContent {
            MaterialTheme {
                Box {
                    EditSongLocalMetadataWriteBackConfirmDialog(
                        isSaving = false,
                        onWriteToLocal = { wroteToLocal = true },
                        onSaveInAppOnly = { savedInAppOnly = true },
                        onCancel = { cancelled = true }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.local_song_metadata_write_confirm_write))
        waitForText(context.getString(R.string.local_song_metadata_write_confirm_app_only))
        waitForText(context.getString(R.string.action_cancel))
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()

        composeRule.runOnIdle {
            assertTrue(cancelled)
            assertFalse(wroteToLocal)
            assertFalse(savedInAppOnly)
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
                        onSaveLyrics = { true },
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
    fun lyricsEditorSheet_systemBackReturnsToSongInfoBeforeOuterMenu() {
        var lyricsEditorDismissed = false
        var outerMenuBackHandled = false

        composeRule.setContent {
            val lyricsEditorShown = remember { mutableStateOf(true) }
            MaterialTheme {
                Box {
                    BackHandler {
                        outerMenuBackHandled = true
                    }
                    if (lyricsEditorShown.value) {
                        LyricsEditorSheet(
                            originalSong = demoSongItem(),
                            initialLyrics = "原文A",
                            initialTranslatedLyrics = "译文B",
                            onSaveLyrics = { true },
                            onDismiss = {
                                lyricsEditorDismissed = true
                                lyricsEditorShown.value = false
                            }
                        )
                    } else {
                        Text("编辑歌曲信息仍在")
                    }
                }
            }
        }

        waitForText("原文A")
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        waitForText("编辑歌曲信息仍在")

        composeRule.runOnIdle {
            assertTrue(lyricsEditorDismissed)
            assertTrue(!outerMenuBackHandled)
        }
    }

    @Test
    fun lyricsEditorSheet_dismissesOnlyAfterLyricsWriteSucceeds() {
        val context = targetContext
        val savedDrafts = mutableListOf<EditSongLyricsDraft>()
        var dismissed = false

        composeRule.setContent {
            val lyricsEditorShown = remember { mutableStateOf(true) }
            MaterialTheme {
                Box {
                    if (lyricsEditorShown.value) {
                        LyricsEditorSheet(
                            originalSong = demoSongItem(),
                            initialLyrics = "原文A",
                            initialTranslatedLyrics = "译文B",
                            onSaveLyrics = { draft ->
                                savedDrafts += draft
                                true
                            },
                            onDismiss = {
                                dismissed = true
                                lyricsEditorShown.value = false
                            }
                        )
                    }
                }
            }
        }

        waitForText("原文A")
        composeRule.onNodeWithText(context.getString(R.string.music_save_changes)).performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { dismissed }

        composeRule.runOnIdle {
            assertEquals(
                EditSongLyricsDraft(
                    lyric = "原文A",
                    translatedLyric = "译文B",
                    romanizedLyric = "",
                    writeLocalMetadata = false
                ),
                savedDrafts.single()
            )
        }
    }

    @Test
    fun lyricsEditorSheet_keepsEditorOpenWhenLyricsWriteFails() {
        val context = targetContext
        var saveFailureCount = 0
        var dismissed = false

        composeRule.setContent {
            MaterialTheme {
                Box {
                    LyricsEditorSheet(
                        originalSong = demoSongItem(),
                        initialLyrics = "原文A",
                        initialTranslatedLyrics = "译文B",
                        onSaveLyrics = { false },
                        onSaveFailed = { saveFailureCount += 1 },
                        onDismiss = { dismissed = true }
                    )
                }
            }
        }

        waitForText("原文A")
        composeRule.onNodeWithText(context.getString(R.string.music_save_changes)).performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { saveFailureCount == 1 }
        waitForText("原文A")

        composeRule.runOnIdle {
            assertFalse(dismissed)
            assertEquals(1, saveFailureCount)
        }
    }

    @Test
    fun lyricFontSizeSheet_doneCommitsScaleAndDismisses() {
        val context = targetContext
        val committedLyricScales = mutableListOf<Float>()
        val committedTranslationScales = mutableListOf<Float>()
        var dismissed = false

        composeRule.setContent {
            MaterialTheme {
                Box {
                    LyricFontSizeSheet(
                        currentLyricScale = 1.25f,
                        currentTranslationScale = 1.25f,
                        onLyricScaleCommit = { committedLyricScales += it },
                        onTranslationScaleCommit = { committedTranslationScales += it },
                        onDismiss = { dismissed = true }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.nowplaying_lyrics_sample))
        composeRule.onNodeWithText(context.getString(R.string.action_done)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(1.25f), committedLyricScales)
            assertEquals(listOf(1.25f), committedTranslationScales)
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

    @Test
    fun coverPreviewDialog_routesDownloadAndDismissActions() {
        val context = targetContext
        var downloadRequested = false
        var dismissed = false

        composeRule.setContent {
            MaterialTheme {
                NowPlayingCoverPreviewDialog(
                    coverUrl = "file:///cover-preview-test.jpg",
                    songName = "夜航星",
                    offlineMode = true,
                    onDownload = { downloadRequested = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        waitForText("夜航星")
        waitForText(context.getString(R.string.cover_preview_zoom_percent, 100))
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.cover_preview_reset_zoom)
        ).fetchSemanticsNode()
        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.cover_preview_image_content_description_named,
                "夜航星"
            )
        ).fetchSemanticsNode()
        composeRule.onNodeWithText(
            context.getString(R.string.action_download_cover)
        ).performClick()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.action_close)
        ).performClick()

        composeRule.runOnIdle {
            assertTrue(downloadRequested)
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
