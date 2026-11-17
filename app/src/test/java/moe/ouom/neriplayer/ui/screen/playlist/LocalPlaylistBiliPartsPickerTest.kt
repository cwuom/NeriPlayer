package moe.ouom.neriplayer.ui.screen.playlist

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                    onPlaySinglePart = { error("Multipart playback must wait for a selection") },
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
        var played = false
        composeRule.setContent {
            MaterialTheme {
                LocalPlaylistBiliPartsPicker(
                    bvid = "BV1uVRiBXETe",
                    title = desktopFavorite.name,
                    loadVideo = { videoInfo(VideoPage(42_903_669_033L, 1, "Only track", 230, 0, 0)) },
                    onDismiss = {},
                    onPlaySinglePart = { played = true },
                    onPlayPart = { _, _ -> error("A single part should play directly") }
                )
            }
        }

        composeRule.runOnIdle { assertEquals(true, played) }
        composeRule.onNodeWithText("Only track").assertDoesNotExist()
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
