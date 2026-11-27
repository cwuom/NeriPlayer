package moe.ouom.neriplayer.ui.screen.playlist

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
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
    fun `single part plays from the original playlist without showing the picker`() {
        assertOriginalPlayback(VideoPage(42_903_669_033L, 1, "Only track", 230, 0, 0))
    }

    @Test
    fun `valid video with no pages uses the original playlist playback action`() {
        assertOriginalPlayback()
    }

    @Test
    fun `missing video identity keeps the picker open through retry and part selection`() {
        val invalidVideo = videoInfo().copy(aid = 0L, bvid = "")
        val multipartVideo = videoInfo(
            VideoPage(101L, 1, "First track", 230, 0, 0),
            VideoPage(102L, 2, "Second track", 230, 0, 0)
        )
        val retryResponse = CompletableDeferred<VideoBasicInfo>()
        var requests = 0
        var dismissals = 0
        var selected: Pair<VideoBasicInfo, Int>? = null
        composeRule.setContent {
            MaterialTheme {
                LocalPlaylistBiliPartsPicker(
                    bvid = desktopFavorite.audioId.orEmpty(),
                    title = desktopFavorite.name,
                    loadVideo = {
                        requests++
                        if (requests == 1) invalidVideo else retryResponse.await()
                    },
                    onDismiss = { dismissals++ },
                    onPlayOriginal = { error("Playback must wait for a part selection") },
                    onPlayPart = { info, index -> selected = info to index }
                )
            }
        }

        val failure = composeRule.onNodeWithText(composeRule.activity.getString(AppR.string.bili_get_parts_failed))
        failure.assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(CoreCommonR.string.action_retry)).performClick()
        failure.assertDoesNotExist()
        composeRule.onNodeWithText(desktopFavorite.name).assertExists()
        composeRule.runOnIdle {
            assertEquals(2, requests)
            assertEquals(0, dismissals)
            assertNull(selected)
            retryResponse.complete(multipartVideo)
        }

        composeRule.onNodeWithText("Second track").performClick()
        composeRule.runOnIdle {
            assertEquals(multipartVideo to 1, selected)
            assertEquals(1, dismissals)
        }
    }

    private fun assertOriginalPlayback(vararg pages: VideoPage) {
        val response = CompletableDeferred<VideoBasicInfo>()
        var plays = 0
        composeRule.setContent {
            MaterialTheme {
                LocalPlaylistBiliPartsPicker(
                    bvid = "BV1uVRiBXETe",
                    title = desktopFavorite.name,
                    loadVideo = { response.await() },
                    onDismiss = {},
                    onPlayOriginal = { plays++ },
                    onPlayPart = { _, _ -> error("A video without a choice of parts should play directly") }
                )
            }
        }

        composeRule.onNode(isDialog()).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, plays)
            response.complete(videoInfo(*pages))
        }
        composeRule.runOnIdle { assertEquals(1, plays) }
        composeRule.onNode(isDialog()).assertDoesNotExist()
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
