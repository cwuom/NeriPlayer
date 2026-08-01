package moe.ouom.neriplayer.ui.viewmodel.tab

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreLinkRecognizerTest {

    @Test
    fun `share text extracts Bilibili short link`() {
        val input = """
            【示例视频标题-哔哩哔哩】
            https://b23.tv/example
        """.trimIndent()

        assertEquals(
            ExploreLinkTarget.BiliShortLink("https://b23.tv/example"),
            recognizeExploreLink(input)
        )
    }

    @Test
    fun `Bilibili video link recognizes bvid after short link redirect`() {
        assertEquals(
            ExploreLinkTarget.BiliVideo(bvid = "BV1rXNY6CE2u"),
            recognizeExploreLink(
                "https://www.bilibili.com/video/BV1rXNY6CE2u/?share_source=COPY"
            )
        )
    }

    @Test
    fun `Bilibili playlist and artist links are classified`() {
        assertEquals(
            ExploreLinkTarget.BiliCollection(ownerMid = 123L, seasonId = 456L),
            recognizeExploreLink("https://space.bilibili.com/123/lists/456?type=season")
        )
        assertEquals(
            ExploreLinkTarget.BiliFavoriteFolder(mediaId = 789L),
            recognizeExploreLink("https://www.bilibili.com/medialist/detail/ml789")
        )
        assertEquals(
            ExploreLinkTarget.BiliFavoriteFolderByOwner(ownerMid = 123L, folderId = 789L),
            recognizeExploreLink("https://space.bilibili.com/123/favlist?fid=789")
        )
        assertEquals(
            ExploreLinkTarget.Unsupported(
                platform = "Bilibili",
                type = "artist/UP 123"
            ),
            recognizeExploreLink("https://space.bilibili.com/123")
        )
    }

    @Test
    fun `Netease song playlist and artist links are classified`() {
        assertEquals(
            ExploreLinkTarget.NeteaseSong(11L),
            recognizeExploreLink("https://music.163.com/#/song?id=11")
        )
        assertEquals(
            ExploreLinkTarget.NeteasePlaylist(22L),
            recognizeExploreLink("https://music.163.com/playlist?id=22")
        )
        assertEquals(
            ExploreLinkTarget.NeteaseArtist(33L),
            recognizeExploreLink("https://y.music.163.com/m/artist?id=33")
        )
    }

    @Test
    fun `Netease short link is deferred until redirect expansion`() {
        assertEquals(
            ExploreLinkTarget.NeteaseShortLink("https://163cn.tv/example"),
            recognizeExploreLink(
                "分享示例歌曲《Example Song》: https://163cn.tv/example (音乐平台分享)"
            )
        )
    }

    @Test
    fun `YouTube video playlist and artist links are classified`() {
        assertEquals(
            ExploreLinkTarget.YouTubeVideo(videoId = "abcdefghijk", playlistId = "PL123"),
            recognizeExploreLink("https://youtu.be/abcdefghijk?list=PL123")
        )
        assertEquals(
            ExploreLinkTarget.YouTubeVideo(videoId = "live-video-id"),
            recognizeExploreLink("https://www.youtube.com/live/live-video-id")
        )
        assertEquals(
            ExploreLinkTarget.YouTubePlaylist("PL456"),
            recognizeExploreLink("https://music.youtube.com/playlist?list=PL456")
        )
        assertEquals(
            ExploreLinkTarget.Unsupported(platform = "YouTube", type = "artist"),
            recognizeExploreLink("https://www.youtube.com/@demo-artist")
        )
    }

    @Test
    fun `link extraction removes trailing share punctuation`() {
        assertEquals(
            ExploreLinkTarget.NeteaseSong(11L),
            recognizeExploreLink("歌曲链接：https://music.163.com/song?id=11。")
        )
    }

    @Test
    fun `unknown domains are rejected`() {
        assertNull(recognizeExploreLink("https://example.com/song?id=11"))
    }

    @Test
    fun `redirect expansion follows final URL`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/short") { exchange ->
            exchange.responseHeaders.add("Location", "/video/BV1rXNY6CE2u")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/video/BV1rXNY6CE2u") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            assertEquals(
                "$baseUrl/video/BV1rXNY6CE2u",
                expandExploreRedirectUrl("$baseUrl/short", OkHttpClient())
            )
        } finally {
            server.stop(0)
        }
    }
}
