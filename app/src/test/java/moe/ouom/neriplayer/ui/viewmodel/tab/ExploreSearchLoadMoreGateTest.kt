package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreSearchLoadMoreGateTest {
    @Test
    fun `load more triggers once the last visible row enters the prefetch window`() {
        assertTrue(gate(lastVisible = 14))
        assertTrue(gate(lastVisible = 19))
        assertFalse(gate(lastVisible = 13))
        assertTrue(gate(lastVisible = 10, prefetchDistance = 10))
    }

    @Test
    fun `load more is blocked by any busy or terminal state`() {
        assertFalse(gate(hasMore = false))
        assertFalse(gate(searching = true))
        assertFalse(gate(loadingMore = true))
        assertFalse(gate(loadMoreFailed = true))
        assertFalse(gate(resultCount = 0, lastVisible = 0))
        assertFalse(gate(lastVisible = null))
    }

    @Test
    fun `song extraction keeps only song results in their original order`() {
        val first = song(1L)
        val second = song(2L)
        val items = listOf(
            ExploreSearchResult.Song(first),
            ExploreSearchResult.Playlist(PlaylistSummary(9L, "List", "", 0L, 0)),
            ExploreSearchResult.Notice(title = "notice", message = "message"),
            ExploreSearchResult.Song(second)
        )

        assertEquals(listOf(first, second), searchSongItems(items))
        assertTrue(searchSongItems(emptyList()).isEmpty())
    }

    @Test
    fun `shared link cid selects the matching part before the page number`() {
        val song = videoInfo().toExploreLinkSong(
            ExploreLinkTarget.BiliVideo(bvid = BVID, page = 1, cid = 200L)
        )

        assertEquals("200", song.subAudioId)
        assertEquals(20_000L, song.durationMs)
        assertEquals("${PlayerManager.BILI_SOURCE_TAG}|200|$BVID", song.album)
    }

    @Test
    fun `unknown cid falls back to the requested page number`() {
        val song = videoInfo().toExploreLinkSong(
            ExploreLinkTarget.BiliVideo(bvid = BVID, page = 1, cid = 999L)
        )

        assertEquals("100", song.subAudioId)
        assertEquals(10_000L, song.durationMs)
    }

    @Test
    fun `links without a matching part keep the whole video`() {
        val plain = videoInfo().toExploreLinkSong(ExploreLinkTarget.BiliVideo(bvid = BVID))
        val unknownPage = videoInfo().toExploreLinkSong(
            ExploreLinkTarget.BiliVideo(bvid = BVID, page = 7)
        )

        assertNull(plain.subAudioId)
        assertEquals(30_000L, plain.durationMs)
        assertEquals(plain, unknownPage)
    }

    private fun gate(
        resultCount: Int = 20,
        lastVisible: Int? = 19,
        hasMore: Boolean = true,
        searching: Boolean = false,
        loadingMore: Boolean = false,
        loadMoreFailed: Boolean = false,
        prefetchDistance: Int = 6
    ) = shouldLoadExploreSearchMore(
        resultCount = resultCount,
        lastVisibleItemIndex = lastVisible,
        hasMore = hasMore,
        searching = searching,
        loadingMore = loadingMore,
        loadMoreFailed = loadMoreFailed,
        prefetchDistance = prefetchDistance
    )

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        channelId = "netease",
        audioId = id.toString()
    )

    private fun videoInfo() = VideoBasicInfo(
        aid = 1L,
        bvid = BVID,
        title = "Video title",
        coverUrl = "https://example.com/cover.jpg",
        desc = "",
        durationSec = 30,
        ownerMid = 100L,
        ownerName = "Uploader",
        ownerFace = "",
        stats = VideoStats(
            view = 0L,
            danmaku = 0L,
            reply = 0L,
            favorite = 0L,
            coin = 0L,
            share = 0L,
            like = 0L
        ),
        pages = listOf(
            VideoPage(cid = 100L, page = 1, part = "First", durationSec = 10, width = 0, height = 0),
            VideoPage(cid = 200L, page = 2, part = "Second", durationSec = 20, width = 0, height = 0)
        )
    )

    private companion object {
        const val BVID = "BV1rXNY6CE2u"
    }
}
