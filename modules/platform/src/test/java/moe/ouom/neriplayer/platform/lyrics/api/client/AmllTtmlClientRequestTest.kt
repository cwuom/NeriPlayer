package moe.ouom.neriplayer.platform.lyrics.api.client

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlLyrics
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlSearchResult
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmllTtmlClientRequestTest {
    private val requests = CopyOnWriteArrayList<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val (code, body) = replies.removeFirstOrNull() ?: throw IOException("offline")
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()
    private val amll = AmllTtmlClient(client, baseUrl = "https://amll.test/")

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `search posts a title query and keeps only ttml entries`() = runTest {
        replies += 200 to """
            ["not-an-object",{"file":"song.lrc","title":"Wrong format"},
            {"file":"song.ttml","title":"Signal","titles":["Signal"," ","信号"],"artist":"Artist",
            "artists":["Artist"],"albums":["Album"],"ncmIds":["1"],"score":5}]
        """.trimIndent()

        val results = amll.searchLyrics("  Signal  ")

        assertEquals(
            listOf(
                AmllTtmlSearchResult(
                    file = "song.ttml",
                    title = "Signal",
                    titles = listOf("Signal", "信号"),
                    artist = "Artist",
                    artists = listOf("Artist"),
                    albums = listOf("Album"),
                    ncmIds = listOf("1"),
                    qqIds = emptyList(),
                    score = 5
                )
            ),
            results
        )
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://amll.test/api/search-lyrics", request.url.toString())
        val payload = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(2, payload.length())
        assertEquals("Signal", payload.getString("query"))
        assertEquals("title", payload.getString("type"))
    }

    @Test
    fun `search gives up on blank queries, failures and unreadable bodies`() = runTest {
        assertTrue(amll.searchLyrics("   ").isEmpty())
        assertTrue(requests.isEmpty())

        replies += 500 to "[]"
        replies += 200 to "  "
        replies += 200 to "{not json"
        repeat(4) { assertTrue(amll.searchLyrics("Signal").isEmpty()) }
        assertEquals(4, requests.size)
    }

    @Test
    fun `raw lyrics are fetched only for plain ttml file names`() = runTest {
        replies += 200 to "<tt/>"
        val result = searchResult(file = " song.ttml ", albums = emptyList())

        assertEquals(
            AmllTtmlLyrics(lyrics = "<tt/>", file = " song.ttml ", title = "Signal", artists = listOf("Artist"), album = ""),
            amll.getLyrics(result)
        )
        assertEquals("https://amll.test/raw-lyrics/song.ttml", requests.single().url.toString())

        assertNull(amll.getLyrics(searchResult(file = "../secret.ttml")))
        assertNull(amll.getLyrics(searchResult(file = "song.lrc")))
        assertEquals(1, requests.size)
    }

    private fun searchResult(file: String, albums: List<String> = listOf("Album")) = AmllTtmlSearchResult(
        file = file,
        title = "Signal",
        titles = listOf("Signal"),
        artist = "Artist",
        artists = listOf("Artist"),
        albums = albums,
        ncmIds = emptyList(),
        qqIds = emptyList(),
        score = 1
    )
}
