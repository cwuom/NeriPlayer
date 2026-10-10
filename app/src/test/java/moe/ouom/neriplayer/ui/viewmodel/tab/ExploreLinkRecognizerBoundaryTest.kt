package moe.ouom.neriplayer.ui.viewmodel.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreLinkRecognizerBoundaryTest {

    @Test
    fun `input without a usable http url is rejected`() {
        assertNull(recognizeExploreLink("   "))
        assertNull(recognizeExploreLink("just some share text"))
        assertNull(recognizeExploreLink("http://"))
        assertNull(recognizeExploreLink("https://"))
        assertNull(recognizeExploreLink("https://music.163.com/song?id=1|2"))
        assertNull(recognizeExploreLink("https://bad_host.com/song?id=1"))
        assertEquals(
            ExploreLinkTarget.NeteaseSong(5L),
            recognizeExploreLink("（https://music.163.com/song?id=5）")
        )
    }

    @Test
    fun `netease links need a numeric id and a known section`() {
        assertNull(recognizeExploreLink("https://music.163.com/song"))
        assertNull(recognizeExploreLink("https://music.163.com/song?id=abc"))
        assertNull(recognizeExploreLink("https://music.163.com/album?id=1"))
        assertNull(recognizeExploreLink("https://music.163.com/#/playlist"))
        assertEquals(ExploreLinkTarget.NeteaseArtist(9L), recognizeExploreLink("https://music.163.com/#/artist?id=9"))
    }

    @Test
    fun `bilibili av links keep part context`() {
        assertEquals(
            ExploreLinkTarget.BiliVideo(avid = 170001L, page = 3),
            recognizeExploreLink("https://www.bilibili.com/video/av170001?p=3")
        )
        assertEquals(
            ExploreLinkTarget.BiliVideo(avid = 170002L),
            recognizeExploreLink("https://m.bilibili.com/video/?aid=170002&p=0&cid=-1")
        )
        assertEquals(
            ExploreLinkTarget.BiliVideo(bvid = "BV1rXNY6CE2u", isCollectionShare = true),
            recognizeExploreLink("https://www.bilibili.com/video/BV1rXNY6CE2u?share_from=SEASON")
        )
        assertNull(recognizeExploreLink("https://www.bilibili.com/video/?aid=0"))
    }

    @Test
    fun `bilibili space links fall back from lists and favorites to the uploader`() {
        val uploader = ExploreLinkTarget.Unsupported(platform = "Bilibili", type = "artist/UP 123")

        assertEquals(
            ExploreLinkTarget.Unsupported(platform = "Bilibili", type = "series playlist"),
            recognizeExploreLink("https://space.bilibili.com/123/lists/456?type=SERIES")
        )
        assertEquals(uploader, recognizeExploreLink("https://space.bilibili.com/123/lists/abc"))
        assertEquals(uploader, recognizeExploreLink("https://space.bilibili.com/123/favlist"))
        assertEquals(
            ExploreLinkTarget.BiliFavoriteFolderByOwner(ownerMid = 123L, folderId = 456L),
            recognizeExploreLink("https://space.bilibili.com/123/favlist?fid=ml456")
        )
        assertNull(recognizeExploreLink("https://space.bilibili.com/0/lists/5"))
    }

    @Test
    fun `bilibili media lists and space paths on the main site`() {
        assertEquals(
            ExploreLinkTarget.BiliFavoriteFolder(mediaId = 42L),
            recognizeExploreLink("https://www.bilibili.com/medialist/play/42")
        )
        assertNull(recognizeExploreLink("https://www.bilibili.com/medialist/play/ml0"))
        assertEquals(
            ExploreLinkTarget.Unsupported(platform = "Bilibili", type = "artist/UP 456"),
            recognizeExploreLink("https://m.bilibili.com/space/456")
        )
        assertNull(recognizeExploreLink("https://www.bilibili.com/space/abc"))
    }

    @Test
    fun `youtube video path sections take precedence over query ids`() {
        assertEquals(
            ExploreLinkTarget.YouTubeVideo(videoId = "embedded"),
            recognizeExploreLink("https://www.youtube-nocookie.com/embed/embedded")
        )
        assertEquals(
            ExploreLinkTarget.YouTubeVideo(videoId = "short-id"),
            recognizeExploreLink("https://youtube.com/shorts/short-id?v=ignored")
        )
        assertNull(recognizeExploreLink("https://www.youtube.com/embed"))
        assertNull(recognizeExploreLink("https://www.youtube.com/shorts?v=ignored"))
        assertNull(recognizeExploreLink("https://m.youtube.com/live"))
        assertNull(recognizeExploreLink("https://www.youtube.com/shorts/%20?v=ignored"))
        assertEquals(
            ExploreLinkTarget.YouTubeVideo(videoId = "abc"),
            recognizeExploreLink("https://youtu.be/abc/extra")
        )
        assertNull(recognizeExploreLink("https://youtu.be/"))
    }

    @Test
    fun `youtube channel style paths are artists and other pages are ignored`() {
        val artist = ExploreLinkTarget.Unsupported(platform = "YouTube", type = "artist")

        listOf("channel/UC123", "c/demo", "browse/UC456").forEach { path ->
            assertEquals(path, artist, recognizeExploreLink("https://music.youtube.com/$path"))
        }
        assertNull(recognizeExploreLink("https://www.youtube.com/watch?v="))
        assertNull(recognizeExploreLink("https://www.youtube.com/feed"))
    }
}
