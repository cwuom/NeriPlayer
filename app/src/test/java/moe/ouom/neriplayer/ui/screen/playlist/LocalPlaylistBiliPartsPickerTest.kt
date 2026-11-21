package moe.ouom.neriplayer.ui.screen.playlist

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LocalPlaylistBiliPartsPickerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `desktop whole video is eligible but saved parts and local files play directly`() {
        assertEquals("BV1uVRiBXETe", desktopFavorite.biliPartsPickerBvidOrNull())
        assertNull(desktopFavorite.copy(subAudioId = "42903667400").biliPartsPickerBvidOrNull())
        assertNull(desktopFavorite.copy(localFilePath = "/music/part.m4a").biliPartsPickerBvidOrNull())
    }

    @Test
    fun `multipart video waits for a selection and starts at the selected part`() {
        val video = videoInfo(*Array(173) { index ->
            val page = index + 1
            VideoPage(cid = 40_000_000_000L + page, page, "Track $page", 230, 0, 0)
        })
        var selected: Pair<VideoBasicInfo, Int>? = null
        composeRule.setContent {
            MaterialTheme {
                LocalPlaylistBiliPartsPicker(
                    bvid = "BV1uVRiBXETe",
                    title = desktopFavorite.name,
                    loadVideo = { bvid ->
                        assertEquals("BV1uVRiBXETe", bvid)
                        video
                    },
                    onDismiss = {},
                    onPlayOriginal = { error("Multipart playback must wait for a selection") },
                    onPlayPart = { info, index ->
                        selected = info to index
                    }
                )
            }
        }

        composeRule.onNodeWithText("Track 1").assertExists()
        composeRule.runOnIdle { assertNull(selected) }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(172)
        composeRule.onNodeWithText("Track 173").performClick()

        composeRule.runOnIdle {
            assertEquals(173, selected?.first?.pages?.size)
            assertEquals(172, selected?.second)
            assertEquals(40_000_000_173L, selected?.first?.pages?.get(172)?.cid)
        }
    }

    @Test
    fun `single part uses the original playlist playback action`() {
        assertOriginalPlayback(VideoPage(42_903_669_033L, 1, "Only track", 230, 0, 0))
    }

    @Test
    fun `valid video with no pages uses the original playlist playback action`() {
        assertOriginalPlayback()
    }

    @Test
    fun `missing video identity offers retry instead of starting playback`() {
        val invalidVideo = videoInfo().copy(aid = 0L, bvid = "")
        composeRule.setContent {
            MaterialTheme {
                LocalPlaylistBiliPartsPicker(
                    bvid = desktopFavorite.audioId.orEmpty(),
                    title = desktopFavorite.name,
                    loadVideo = { invalidVideo },
                    onDismiss = { error("The failed request should keep the picker open") },
                    onPlayOriginal = { error("The failed request should not start playback") },
                    onPlayPart = { _, _ -> error("The failed request has no part to select") }
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(AppR.string.bili_get_parts_failed)).assertExists()
    }

    private fun assertOriginalPlayback(vararg pages: VideoPage) {
        var played = false
        composeRule.setContent {
            MaterialTheme {
                LocalPlaylistBiliPartsPicker(
                    bvid = "BV1uVRiBXETe",
                    title = desktopFavorite.name,
                    loadVideo = { videoInfo(*pages) },
                    onDismiss = {},
                    onPlayOriginal = { played = true },
                    onPlayPart = { _, _ -> error("A video without a choice of parts should play directly") }
                )
            }
        }

        composeRule.runOnIdle { assertTrue(played) }
    }

    private val desktopFavorite = SongItem(
        id = 3_078_922_585_826_408_280L,
        name = "Whole video title",
        artist = "Uploader",
        album = "",
        albumId = 0L,
        durationMs = 41_255_000L,
        coverUrl = null,
        channelId = "bilibili",
        audioId = "BV1uVRiBXETe"
    )

    private fun videoInfo(vararg pages: VideoPage) = VideoBasicInfo(
        aid = 116_516_876_124_856L,
        bvid = "BV1uVRiBXETe",
        title = desktopFavorite.name,
        coverUrl = "",
        desc = "",
        durationSec = 41_255,
        ownerMid = 0L,
        ownerName = desktopFavorite.artist,
        ownerFace = "",
        stats = VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
        pages = pages.toList()
    )
}
