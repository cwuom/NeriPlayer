package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.player.PlayerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreBiliLinkResolutionTest {

    @Test
    fun `selected Bilibili page keeps the video title and page-specific playback`() {
        val song = videoInfo().toExploreLinkSong(
            ExploreLinkTarget.BiliVideo(bvid = "BV1rXNY6CE2u", page = 2)
        )

        assertEquals("Video title", song.name)
        assertEquals("Uploader", song.artist)
        assertEquals("${PlayerManager.BILI_SOURCE_TAG}|200", song.album)
        assertEquals("200", song.subAudioId)
        assertEquals(20_000L, song.durationMs)
    }

    @Test
    fun `collection share uses the video's UGC season`() {
        val target = videoInfo(
            ugcSeason = BiliClient.UgcSeason(
                id = 4002195L,
                mid = 670363050L,
                title = "直播切片"
            )
        ).toExploreLinkCollectionTarget(
            ExploreLinkTarget.BiliVideo(
                bvid = "BV1V4m2BMEWN",
                isCollectionShare = true
            )
        )

        assertEquals(
            ExploreLinkTarget.BiliCollection(ownerMid = 670363050L, seasonId = 4002195L),
            target
        )
    }

    @Test
    fun `collection share uses its explicit season when video details omit it`() {
        val target = videoInfo().toExploreLinkCollectionTarget(
            ExploreLinkTarget.BiliVideo(
                bvid = "BV1V4m2BMEWN",
                seasonId = 4002195L,
                isCollectionShare = true
            )
        )

        assertEquals(
            ExploreLinkTarget.BiliCollection(ownerMid = 100L, seasonId = 4002195L),
            target
        )
    }

    @Test
    fun `ordinary video link does not open its UGC season`() {
        val target = videoInfo(
            ugcSeason = BiliClient.UgcSeason(
                id = 4002195L,
                mid = 670363050L,
                title = "直播切片"
            )
        ).toExploreLinkCollectionTarget(
            ExploreLinkTarget.BiliVideo(bvid = "BV1V4m2BMEWN")
        )

        assertNull(target)
    }

    private fun videoInfo(ugcSeason: BiliClient.UgcSeason? = null): BiliClient.VideoBasicInfo {
        return BiliClient.VideoBasicInfo(
            aid = 1L,
            bvid = "BV1rXNY6CE2u",
            title = "Video title",
            coverUrl = "https://example.com/cover.jpg",
            desc = "",
            durationSec = 30,
            ownerMid = 100L,
            ownerName = "Uploader",
            ownerFace = "",
            stats = BiliClient.VideoStats(
                view = 0L,
                danmaku = 0L,
                reply = 0L,
                favorite = 0L,
                coin = 0L,
                share = 0L,
                like = 0L
            ),
            pages = listOf(
                BiliClient.VideoPage(
                    cid = 100L,
                    page = 1,
                    part = "First part",
                    durationSec = 10,
                    width = 0,
                    height = 0
                ),
                BiliClient.VideoPage(
                    cid = 200L,
                    page = 2,
                    part = "2. Selected part - Selected artist",
                    durationSec = 20,
                    width = 0,
                    height = 0
                )
            ),
            ugcSeason = ugcSeason
        )
    }
}
