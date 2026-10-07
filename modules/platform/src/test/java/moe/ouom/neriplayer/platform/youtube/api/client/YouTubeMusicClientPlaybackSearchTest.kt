package moe.ouom.neriplayer.platform.youtube.api.client

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicPlayableAudio
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchFilter
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResultType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeMusicClientPlaybackSearchTest {
    private val originalLocale = Locale.getDefault()
    private val playableResponse = json(
        """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[""" +
            """{"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","url":"https://audio.example/song.m4a",""" +
            """"approxDurationMs":"123000","bitrate":128000}]}}"""
    )

    @Before
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `playable audio retries each locale twice before reporting the last failure`() = runTest {
        val http = YouTubeMusicTestHttp { call ->
            if (call.isBootstrap) {
                bootstrapPage()
            } else {
                json("""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[]}}""")
            }
        }
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client)

        val error = runCatching { client.getPlayableAudio("video-id") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("YouTube Music player missing playable audio formats", error?.message)
        assertEquals(
            listOf("en-US" to "US", "en-US" to "US", "zh-CN" to "JP", "zh-CN" to "JP"),
            http.innertubeCalls("player").map { it.hl to it.gl }
        )
        assertEquals(3, http.bootstrapCalls().size)
    }

    @Test
    fun `playable audio recovers on the forced bootstrap retry`() = runTest {
        val refresher = RecordingYouTubeRefresher()
        var playerAttempts = 0
        val http = YouTubeMusicTestHttp { call ->
            when {
                call.isBootstrap -> bootstrapPage(loggedIn = true)
                ++playerAttempts == 1 -> json(
                    """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"}}"""
                )
                else -> playableResponse
            }
        }
        val client = YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), http.client, refresher)

        val audio = client.getPlayableAudio("video-id")

        assertEquals(
            YouTubeMusicPlayableAudio("https://audio.example/song.m4a", 123_000L, "audio/mp4", null, 128_000),
            audio
        )
        assertEquals(listOf("playable_audio" to false), refresher.calls)
        assertEquals(2, http.bootstrapCalls().size)
        http.innertubeCalls("player").forEach { call ->
            assertEquals("video-id", call.payload!!.getString("videoId"))
            assertTrue(call.payload.getBoolean("contentCheckOk"))
            assertTrue(call.payload.getBoolean("racyCheckOk"))
            assertEquals("test-visitor", call.request.header("X-Goog-Visitor-Id"))
            assertEquals("test-key", call.request.url.queryParameter("key"))
        }
    }

    @Test
    fun `song and video searches send their filter params`() = runTest {
        val http = YouTubeMusicTestHttp { call ->
            if (call.isBootstrap) {
                bootstrapPage()
            } else {
                json(searchPage(songRow("song-1", "First", "Artist A"), songRow("song-2", "Second", "Artist B")))
            }
        }
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client)

        val songs = client.search("lofi beats")
        val videos = client.search("lofi beats", limit = 1, filter = YouTubeMusicSearchFilter.Video)

        assertEquals(listOf("song-1", "song-2"), songs.map { it.videoId })
        assertEquals(listOf("Artist A", "Artist B"), songs.map { it.artist })
        assertTrue(songs.all { it.type == YouTubeMusicSearchResultType.Song })
        assertEquals(listOf("song-1"), videos.map { it.videoId })
        assertEquals(YouTubeMusicSearchResultType.Video, videos.single().type)
        val (songRequest, videoRequest) = http.innertubeCalls("search")
        assertEquals("lofi beats", songRequest.payload!!.getString("query"))
        assertEquals("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D", songRequest.payload.getString("params"))
        assertEquals("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D", videoRequest.payload!!.getString("params"))
        listOf(songRequest, videoRequest).forEach { call ->
            assertNull(call.request.url.queryParameter("ctoken"))
            assertEquals("test-visitor", call.request.header("X-Goog-Visitor-Id"))
        }
    }

    @Test
    fun `search follows continuation tokens until the limit is reached`() = runTest {
        val firstPage = searchPage(songRow("song-1", "First")).replace(
            """"contents":[${songRow("song-1", "First")}]""",
            """"contents":[${songRow("song-1", "First")}],""" +
                """"continuations":[{"nextContinuationData":{"continuation":"page 2"}}]"""
        )
        val secondPage = """{"continuationContents":{"musicShelfContinuation":{"contents":[""" +
            "${songRow("song-1", "First")},${songRow("song-2", "Second")}]}}}"
        val http = YouTubeMusicTestHttp { call ->
            when {
                call.isBootstrap -> bootstrapPage()
                call.payload!!.has("continuation") -> json(secondPage)
                else -> json(firstPage)
            }
        }

        val results = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client).search("lofi", limit = 5)

        assertEquals(listOf("song-1", "song-2"), results.map { it.videoId })
        val continuation = http.innertubeCalls("search").last()
        assertEquals("page 2", continuation.request.url.queryParameter("ctoken"))
        assertEquals("page 2", continuation.request.url.queryParameter("continuation"))
        assertEquals("page 2", continuation.payload!!.getString("continuation"))
    }

    @Test
    fun `creator filter and blank queries never reach the network`() = runTest {
        val http = YouTubeMusicTestHttp { error("No request expected") }
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client)

        val creatorSearch = runCatching {
            client.search("artist", filter = YouTubeMusicSearchFilter.Creator)
        }.exceptionOrNull()

        assertTrue(creatorSearch is IllegalArgumentException)
        assertEquals("Use searchCreators for YouTube Music creator results", creatorSearch?.message)
        assertTrue(client.search("   ").isEmpty())
        assertFalse(http.calls.any())
    }
}
