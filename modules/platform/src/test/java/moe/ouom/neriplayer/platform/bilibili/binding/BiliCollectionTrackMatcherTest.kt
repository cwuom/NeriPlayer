package moe.ouom.neriplayer.platform.bilibili.binding

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliCollectionTrackMatcherTest {

    @Test
    fun `parses bvid from share url`() {
        val id = parseBiliCollectionVideoId("https://www.bilibili.com/video/BV1xx411c7mD?p=3&t=12")
        assertEquals("BV1xx411c7mD", id?.bvid)
    }

    @Test
    fun `parses avid from text`() {
        val id = parseBiliCollectionVideoId("https://www.bilibili.com/video/av12345")
        assertEquals(12345L, id?.avid)
    }

    @Test
    fun `rejects unrelated input`() {
        assertNull(parseBiliCollectionVideoId("这不是链接"))
    }

    @Test
    fun `extracts short link from mobile share text`() {
        val shareText = "【周杰伦 - 晴天-哔哩哔哩】 https://b23.tv/AbCdEf，快来看吧"
        assertEquals("https://b23.tv/AbCdEf", extractBiliShareUrl(shareText))
    }

    @Test
    fun `resolves b23 short link through redirect before parsing bvid`() = runTest {
        var requested: String? = null
        val id = resolveBiliCollectionVideoId("【晴天】 https://b23.tv/AbCdEf") { url ->
            requested = url
            "https://www.bilibili.com/video/BV1xx411c7mD?share_source=copy_web"
        }

        assertEquals("https://b23.tv/AbCdEf", requested)
        assertEquals("BV1xx411c7mD", id?.bvid)
    }

    @Test
    fun `short link without a usable redirect resolves to null`() = runTest {
        assertNull(resolveBiliCollectionVideoId("https://b23.tv/broken") { null })
    }

    @Test
    fun `matches songs by title and duration`() {
        val songs = listOf(song(1L, "晴天", 269_000L), song(2L, "夜曲", 226_000L))
        val pages = listOf(
            page(500L, 1, "周杰伦 - 夜曲", 226),
            page(501L, 2, "周杰伦 - 晴天", 269)
        )

        val matches = matchSongsToPages(songs, pages)

        assertEquals(2, matches.size)
        assertEquals(501L, matches.first { it.songId == 1L }.page.cid)
        assertEquals(500L, matches.first { it.songId == 2L }.page.cid)
    }

    @Test
    fun `each page is used at most once`() {
        val songs = listOf(song(1L, "Intro", 60_000L), song(2L, "Intro", 60_000L))
        val pages = listOf(page(700L, 1, "Intro", 60))

        val matches = matchSongsToPages(songs, pages)

        assertEquals(1, matches.size)
        assertTrue(matches.single().page.cid == 700L)
    }

    @Test
    fun `unrelated pages are skipped`() {
        val songs = listOf(song(1L, "晴天", 269_000L))
        val pages = listOf(page(800L, 1, "完全无关的直播回放", 7_200))

        assertTrue(matchSongsToPages(songs, pages).isEmpty())
    }

    @Test
    fun `similar name is enough even when durations differ`() {
        val songs = listOf(song(1L, "晴天", 269_000L))
        val pages = listOf(page(900L, 1, "周杰伦 - 晴天 live", 205))

        val matches = matchSongsToPages(songs, pages)

        assertEquals(900L, matches.single().page.cid)
    }

    @Test
    fun `tolerates simplified and traditional spellings in the title`() {
        val songs = listOf(song(1L, "可愛 Honey", 240_000L))
        val pages = listOf(page(901L, 1, "可爱Honey", 240))

        val matches = matchSongsToPages(songs, pages)

        assertEquals(901L, matches.single().page.cid)
    }

    @Test
    fun `matching durations alone no longer pair two different songs`() {
        val songs = listOf(song(1L, "晴天", 269_000L))
        val pages = listOf(page(902L, 1, "七里香", 269))

        assertTrue(matchSongsToPages(songs, pages).isEmpty())
    }

    private fun song(id: Long, name: String, durationMs: Long) = SongItem(
        id = id,
        name = name,
        artist = "周杰伦",
        album = "叶惠美",
        albumId = 1234L,
        durationMs = durationMs,
        coverUrl = null
    )

    private fun page(cid: Long, index: Int, part: String, durationSec: Int) = VideoPage(
        cid = cid,
        page = index,
        part = part,
        durationSec = durationSec,
        width = 1920,
        height = 1080
    )
}
