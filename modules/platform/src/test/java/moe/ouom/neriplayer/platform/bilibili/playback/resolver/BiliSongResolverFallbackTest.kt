package moe.ouom.neriplayer.platform.bilibili.playback.resolver

import java.io.IOException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.api.client.biliClientTest
import moe.ouom.neriplayer.platform.bilibili.api.client.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class BiliSongResolverFallbackTest {
    private val intro = VideoPage(cid = 11L, page = 1, part = "Intro", durationSec = 30, width = 0, height = 0)
    private val chorus = VideoPage(cid = 22L, page = 2, part = "song", durationSec = 200, width = 0, height = 0)
    private val outro = VideoPage(cid = 33L, page = 3, part = "Outro", durationSec = 40, width = 0, height = 0)

    @Test
    fun `part songs split a single title separator`() {
        val info = video(aid = 77L, bvid = "BV1part", ownerName = "Uploader")

        fun songFor(part: String) = buildBiliPartSong(chorus.copy(part = part), info, coverUrl = "https://cover")

        val split = songFor("01. Signal - Artist One")
        assertEquals("Signal" to "Artist One", split.name to split.artist)
        assertEquals("Bilibili|22|BV1part", split.album)
        assertEquals(200_000L, split.durationMs)
        assertEquals("https://cover", split.coverUrl)
        assertEquals("77" to "22", split.audioId to split.subAudioId)
        assertEquals("Signal – Remix — Live" to "Uploader", songFor("Signal – Remix — Live").let { it.name to it.artist })
        assertEquals("12." to "Uploader", songFor(" 12. ").let { it.name to it.artist })
        assertEquals("Signal-Artist" to "Uploader", songFor("Signal-Artist").let { it.name to it.artist })
    }

    @Test
    fun `bvid lookup without a stored part selects the page by name`() = runBlocking {
        val client = mock(BiliClient::class.java)
        val videoInfo = video(aid = 123L, bvid = "BV1abc", pages = listOf(intro, chorus))
        `when`(client.getVideoBasicInfoByBvid("BV1abc")).thenReturn(videoInfo)

        val resolved = resolveBiliSong(song(album = "Bilibili||BV1abc"), client)

        assertEquals(123L to 22L, resolved?.avid to resolved?.cid)
        assertEquals(chorus, resolved?.pageInfo)
        verify(client).getVideoBasicInfoByBvid("BV1abc")
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `desktop favorites with a BV source and no cid resolve the first part`() = biliClientTest(
        route = { request -> json(DESKTOP_VIDEO).takeIf { request.url.encodedPath == VIEW_PATH } }
    ) { http, client ->
        val favorite = desktopSong()

        val resolved = resolveBiliSong(favorite, client)

        assertEquals(116_516_876_124_856L, resolved?.avid)
        assertEquals(116_516_876_124_856L, favorite.biliAvidOrNull())
        assertEquals(42_903_669_033L, resolved?.cid)
        assertEquals(230, resolved?.pageInfo?.durationSec)
        val request = http.requestsTo(VIEW_PATH).single()
        assertEquals("BV1uVRiBXETe", request.url.queryParameter("bvid"))
        assertNull(request.url.queryParameter("aid"))
    }

    @Test
    fun `desktop BV source honors a stored cid without falling back to another part or video`() = biliClientTest(
        route = { request -> json(DESKTOP_VIDEO).takeIf { request.url.encodedPath == VIEW_PATH } }
    ) { http, client ->
        val favorite = desktopSong()

        val resolved = resolveBiliSong(favorite.copy(subAudioId = "42903667400"), client)
        assertEquals(42_903_667_400L, resolved?.cid)
        assertNull(resolveBiliSong(favorite.copy(album = "Bilibili", subAudioId = "999"), client))
        assertEquals(
            listOf("BV1uVRiBXETe", "BV1uVRiBXETe"),
            http.requestsTo(VIEW_PATH).map { it.url.queryParameter("bvid") }
        )
    }

    @Test
    fun `bvid lookup accepts videos without page metadata`() = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByBvid("BV1empty")).thenReturn(video(aid = 123L, pages = emptyList()))

        val resolved = resolveBiliSong(song(album = "Bilibili||BV1empty"), client)

        assertEquals(0L, resolved?.cid)
        assertNull(resolved?.pageInfo)
    }

    @Test
    fun `bvid lookup missing the stored part falls back to the avid candidates`() = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByBvid("BV1abc")).thenReturn(video(aid = 123L, pages = listOf(intro)))
        `when`(client.getVideoBasicInfoByAvid(123L)).thenReturn(video(aid = 123L, pages = listOf(intro, outro)))

        val resolved = resolveBiliSong(song(album = "Bilibili|33|BV1abc", subAudioId = "33"), client)

        assertEquals(123L to 33L, resolved?.avid to resolved?.cid)
        verify(client).getVideoBasicInfoByBvid("BV1abc")
        verify(client).getVideoBasicInfoByAvid(123L)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `direct avid lookups need a title or single page match`() = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(101L)).thenReturn(video(aid = 101L, title = "song", pages = listOf(intro, outro)))
        `when`(client.getVideoBasicInfoByAvid(102L)).thenReturn(video(aid = 102L, title = "Other", pages = listOf(outro)))
        `when`(client.getVideoBasicInfoByAvid(103L)).thenReturn(video(aid = 103L, title = "song", pages = emptyList()))
        `when`(client.getVideoBasicInfoByAvid(104L)).thenReturn(video(aid = 104L, title = "Other", pages = listOf(intro, outro)))

        assertEquals(11L, resolveBiliSong(song(id = 101L), client)?.cid)
        assertEquals(33L, resolveBiliSong(song(id = 102L), client)?.cid)
        assertEquals(103L to 0L, resolveBiliSong(song(id = 103L), client)?.let { it.avid to it.cid })
        assertNull(resolveBiliSong(song(id = 104L), client))
    }

    @Test
    fun `legacy identities resolve by page number or part name`() = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(1230003L)).thenAnswer { throw IOException("not found") }
        `when`(client.getVideoBasicInfoByAvid(123L)).thenReturn(video(aid = 123L, pages = listOf(intro, outro)))
        `when`(client.getVideoBasicInfoByAvid(4560009L)).thenAnswer { throw IOException("not found") }
        `when`(client.getVideoBasicInfoByAvid(456L)).thenReturn(video(aid = 456L, pages = listOf(intro, chorus, outro)))

        val byPage = resolveBiliSong(song(id = 1230003L, audioId = null), client)
        val byName = resolveBiliSong(song(id = 4560009L, audioId = null), client)

        assertEquals(123L to 33L, byPage?.avid to byPage?.cid)
        assertEquals(456L to 22L, byName?.avid to byName?.cid)
    }

    @Test
    fun `legacy misses keep the direct result or give up`() = runBlocking<Unit> {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(1230000L)).thenReturn(video(aid = 1230000L, title = "song", pages = listOf(intro, outro)))
        `when`(client.getVideoBasicInfoByAvid(4560002L)).thenReturn(video(aid = 4560002L, title = "song", pages = listOf(intro, outro)))
        `when`(client.getVideoBasicInfoByAvid(456L)).thenAnswer { throw IOException("legacy video removed") }
        `when`(client.getVideoBasicInfoByAvid(7890005L)).thenReturn(video(aid = 7890005L, title = "Other", pages = listOf(intro, outro)))
        `when`(client.getVideoBasicInfoByAvid(789L)).thenReturn(video(aid = 789L, pages = listOf(intro, outro)))

        val pageZero = resolveBiliSong(song(id = 1230000L, audioId = null), client)
        val legacyFailure = resolveBiliSong(song(id = 4560002L, audioId = null), client)

        assertEquals(1230000L to 11L, pageZero?.avid to pageZero?.cid)
        assertEquals(4560002L to 11L, legacyFailure?.avid to legacyFailure?.cid)
        assertNull(resolveBiliSong(song(id = 7890005L, audioId = null), client))
        verify(client).getVideoBasicInfoByAvid(456L)
        verify(client).getVideoBasicInfoByAvid(789L)
    }

    private fun song(
        id: Long = 123L,
        album: String = "Bilibili",
        audioId: String? = id.toString(),
        subAudioId: String? = null
    ) = SongItem(
        id = id,
        name = "song",
        artist = "artist",
        album = album,
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        channelId = "bilibili",
        audioId = audioId,
        subAudioId = subAudioId
    )

    private fun desktopSong() = song(
        id = 3_078_922_585_826_408_280L,
        album = "",
        audioId = "BV1uVRiBXETe"
    ).copy(name = "Whole video title", durationMs = 41_255_000L)

    private fun video(
        aid: Long,
        bvid: String = "BV1test",
        title: String = "Video title",
        ownerName: String = "artist",
        pages: List<VideoPage> = listOf(chorus)
    ) = VideoBasicInfo(
        aid = aid,
        bvid = bvid,
        title = title,
        coverUrl = "",
        desc = "",
        durationSec = 1,
        ownerMid = 0L,
        ownerName = ownerName,
        ownerFace = "",
        stats = VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
        pages = pages
    )

    private companion object {
        const val VIEW_PATH = "/x/web-interface/wbi/view"
        val DESKTOP_VIDEO = """
            {"code":0,"data":{
              "aid":116516876124856,"bvid":"BV1uVRiBXETe","title":"Whole video title","duration":41255,
              "owner":{"name":"Uploader"},
              "pages":[
                {"cid":42903669033,"page":1,"part":"First track","duration":230},
                {"cid":42903667400,"page":2,"part":"Second track","duration":292}
              ]
            }}
        """.trimIndent()
    }
}
