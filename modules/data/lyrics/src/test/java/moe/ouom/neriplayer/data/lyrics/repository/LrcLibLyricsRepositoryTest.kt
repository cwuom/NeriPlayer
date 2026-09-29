package moe.ouom.neriplayer.data.lyrics.repository

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.lyrics.client.LrcLibClient
import moe.ouom.neriplayer.data.model.lyrics.lrclib.LrcLibResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcLibLyricsRepositoryTest {

    @Test
    fun `searchLyrics skips an unrelated first result and keeps a nearby match`() = runTest {
        val client = clientResponding(
            """
            [
              {
                "trackName": "Signal",
                "artistName": "Artist One",
                "duration": 240,
                "syncedLyrics": "[00:00.00]wrong"
              },
              {
                "trackName": "Signal",
                "artistName": "Artist One",
                "duration": 188,
                "syncedLyrics": "[00:00.00]matched"
              }
            ]
            """.trimIndent()
        )

        val result = client.searchLyrics(
            trackName = "Signal",
            artistName = "Artist One",
            durationSeconds = 180L
        )

        assertEquals("[00:00.00]matched", result?.syncedLyrics)
    }

    @Test
    fun `getLyrics rejects metadata from another artist`() = runTest {
        val client = clientResponding(
            """
            {
              "trackName": "Signal",
              "artistName": "Another Artist",
              "duration": 180,
              "syncedLyrics": "[00:00.00]wrong"
            }
            """.trimIndent()
        )

        val result = client.getLyrics(
            trackName = "Signal",
            artistName = "Artist One",
            durationSeconds = 180L
        )

        assertNull(result)
    }

    @Test
    fun `getLyrics accepts cleaned display suffix and primary artist metadata`() = runTest {
        val client = clientResponding(
            """
            {
              "trackName": "Signal",
              "artistName": "Artist One",
              "duration": 180,
              "syncedLyrics": "[00:00.00]matched"
            }
            """.trimIndent()
        )

        val result = client.getLyrics(
            trackName = "Signal (Official Video)",
            artistName = "Artist One feat. Guest",
            durationSeconds = 180L
        )

        assertEquals("[00:00.00]matched", result?.syncedLyrics)
    }

    @Test
    fun `getLyrics keeps plain lyrics when synced lyrics has no timeline progress`() = runTest {
        val client = clientResponding(
            """
            {
              "trackName": "Hello",
              "artistName": "Artist One",
              "duration": 180,
              "syncedLyrics": "[00:00.00]First line\n[00:00.00]Second line\n[00:00.00]Third line",
              "plainLyrics": "First line\nSecond line\nThird line"
            }
            """.trimIndent()
        )

        val result = client.getLyrics(
            trackName = "Hello",
            artistName = "Artist One",
            durationSeconds = 180L
        )

        assertNull(result?.syncedLyrics)
        assertEquals("First line\nSecond line\nThird line", result?.plainLyrics)
        assertEquals(false, result?.plainLyricsRecoveredFromCollapsedTimeline)
    }

    @Test
    fun `getLyrics converts zero timestamp synced lyrics to a plain fallback`() = runTest {
        val client = clientResponding(
            """
            {
              "trackName": "Hello",
              "artistName": "Artist One",
              "duration": 180,
              "syncedLyrics": "[00:00.00]First line\n[00:00.00]Second line\n[00:00.00]Third line"
            }
            """.trimIndent()
        )

        val result = client.getLyrics(
            trackName = "Hello",
            artistName = "Artist One",
            durationSeconds = 180L
        )

        assertNull(result?.syncedLyrics)
        assertEquals("First line\nSecond line\nThird line", result?.plainLyrics)
        assertTrue(result?.plainLyricsRecoveredFromCollapsedTimeline == true)
    }

    @Test
    fun `getLyrics keeps synced lyrics with timeline progress`() = runTest {
        val syncedLyrics = "[00:00.00]First line\n[00:12.50]Second line\n[00:25.00]Third line"
        val client = clientResponding(
            """
            {
              "trackName": "Hello",
              "artistName": "Artist One",
              "duration": 180,
              "syncedLyrics": "[00:00.00]First line\n[00:12.50]Second line\n[00:25.00]Third line"
            }
            """.trimIndent()
        )

        val result = client.getLyrics(
            trackName = "Hello",
            artistName = "Artist One",
            durationSeconds = 180L
        )

        assertEquals(syncedLyrics, result?.syncedLyrics)
    }

    @Test
    fun `searchLyrics caps lookup query variants`() = runTest {
        val requestedQueries = mutableListOf<String>()
        val client = clientResponding(
            body = "[]",
            requestedQueries = requestedQueries
        )

        val result = client.searchLyrics(
            trackName = "Signal (Official Video)",
            artistName = "Artist One feat. Guest",
            durationSeconds = 180L
        )

        assertNull(result)
        assertEquals(
            listOf(
                "Signal Artist One",
                "Signal (Official Video) Artist One feat. Guest"
            ),
            requestedQueries
        )
    }

    @Test
    fun `automatic lookup skips nonpositive durations without requesting lyrics`() = runTest {
        val requests = mutableListOf<Request>()
        val repository = repositoryResponding { request ->
            requests.add(request)
            "[]"
        }

        for (duration in listOf(0L, -1L)) {
            assertNull(repository.getLyrics("Signal", "Artist One", duration))
            assertNull(repository.searchLyrics("Signal", "Artist One", duration))
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `getLyrics skips incomplete identities without requesting lyrics`() = runTest {
        val requests = mutableListOf<Request>()
        val repository = repositoryResponding { request ->
            requests.add(request)
            "{}"
        }

        assertNull(repository.getLyrics("  ", "Artist One", 180L))
        assertNull(repository.getLyrics("Signal", "  ", 180L))

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `getLyrics only requests first variant when no usable result is returned`() = runTest {
        val responses = listOf(
            "{}",
            """{"trackName":"Signal","artistName":"Artist One","duration":180}""",
            """
            {
              "trackName": "Signal",
              "artistName": "Artist One",
              "duration": 180,
              "syncedLyrics": "Lyrics without timestamps"
            }
            """.trimIndent()
        )

        for (body in responses) {
            val requests = mutableListOf<Request>()
            val repository = repositoryResponding { request ->
                requests.add(request)
                body
            }

            val result = repository.getLyrics(
                "Signal (Official Video)",
                "Artist One feat. Guest",
                180L
            )

            assertNull(result)
            assertEquals(1, requests.size)
            assertEquals("Signal", requests.single().url.queryParameter("track_name"))
            assertEquals("Artist One", requests.single().url.queryParameter("artist_name"))
            assertEquals("180", requests.single().url.queryParameter("duration"))
        }
    }

    @Test
    fun `getLyrics retains original title when cleaning removes all title text`() = runTest {
        val requests = mutableListOf<Request>()
        val repository = repositoryResponding { request ->
            requests.add(request)
            "{}"
        }

        assertNull(repository.getLyrics(" (Official Video) ", " Artist One ", 180L))

        assertEquals(1, requests.size)
        assertEquals("(Official Video)", requests.single().url.queryParameter("track_name"))
        assertEquals("Artist One", requests.single().url.queryParameter("artist_name"))
    }

    @Test
    fun `searchLyricsCandidates keeps plain lyrics and discards results without usable lyrics`() = runTest {
        val repository = clientResponding(
            """
            [
              {"trackName":"Missing","artistName":"Artist One","duration":180},
              {
                "trackName": "Invalid timeline",
                "artistName": "Artist One",
                "duration": 180,
                "syncedLyrics": "Lyrics without timestamps"
              },
              {
                "trackName": "Plain only",
                "artistName": "Artist Two",
                "duration": 210,
                "plainLyrics": "Original plain lyrics"
              }
            ]
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LrcLibResult(
                    syncedLyrics = null,
                    plainLyrics = "Original plain lyrics",
                    trackName = "Plain only",
                    artistName = "Artist Two",
                    durationSeconds = 210L,
                    plainLyricsRecoveredFromCollapsedTimeline = false
                )
            ),
            repository.searchLyricsCandidates("Signal")
        )
    }

    @Test
    fun `searchLyrics deduplicates unchanged variants before falling back to title`() = runTest {
        val queries = mutableListOf<String>()
        val repository = clientResponding("[]", queries)

        assertNull(repository.searchLyrics(" Signal ", " Artist One ", 180L))
        assertEquals(listOf("Signal Artist One", "Signal"), queries)

        queries.clear()
        assertNull(repository.searchLyrics(" Signal ", "  ", 180L))
        assertEquals(listOf("Signal"), queries)
    }

    @Test
    fun `searchLyrics prefers synced lyrics then closest duration and keeps equal matches stable`() = runTest {
        val queries = mutableListOf<String>()
        val repository = clientResponding(
            body = """
            [
              {
                "trackName": "Signal",
                "artistName": "Artist One",
                "duration": 180,
                "plainLyrics": "Exact duration plain lyrics"
              },
              {
                "trackName": "Signal",
                "artistName": "Artist One",
                "duration": 184,
                "syncedLyrics": "[00:01.00]Farther synced lyrics"
              },
              {
                "trackName": "Signal",
                "artistName": "Artist One",
                "duration": 182,
                "syncedLyrics": "[00:01.00]First nearest synced lyrics"
              },
              {
                "trackName": "Signal",
                "artistName": "Artist One",
                "duration": 178,
                "syncedLyrics": "[00:01.00]Second nearest synced lyrics"
              }
            ]
            """.trimIndent(),
            requestedQueries = queries
        )

        val result = repository.searchLyrics(
            "Signal (Official Video)",
            "Artist One feat. Guest",
            180L
        )

        assertEquals("[00:01.00]First nearest synced lyrics", result?.syncedLyrics)
        assertEquals(182L, result?.durationSeconds)
        assertEquals(listOf("Signal Artist One"), queries)
    }

    @Test
    fun `lookup transport failures return empty results without requesting later variants`() = runTest {
        val requests = mutableListOf<Request>()
        val repository = repositoryResponding { request ->
            requests.add(request)
            throw IOException("Unavailable")
        }

        assertNull(repository.getLyrics("Signal (Official Video)", "Artist One feat. Guest", 180L))
        assertEquals(1, requests.size)
        assertNull(repository.searchLyrics("Signal (Official Video)", "Artist One feat. Guest", 180L))
        assertEquals(2, requests.size)
        assertEquals(emptyList<LrcLibResult>(), repository.searchLyricsCandidates("Signal"))
        assertEquals(3, requests.size)
    }

    @Test
    fun `all lookup entrypoints propagate cancellation without requesting later variants`() = runTest {
        val requests = mutableListOf<Request>()
        val repository = repositoryResponding { request ->
            requests.add(request)
            throw CancellationException("Lookup cancelled")
        }
        val lookups = listOf<suspend () -> Any?>(
            { repository.getLyrics("Signal (Official Video)", "Artist One feat. Guest", 180L) },
            { repository.searchLyrics("Signal (Official Video)", "Artist One feat. Guest", 180L) },
            { repository.searchLyricsCandidates("Signal") }
        )

        lookups.forEachIndexed { index, lookup ->
            val error = runCatching { lookup() }.exceptionOrNull()

            assertTrue(error is CancellationException)
            assertEquals("Lookup cancelled", error?.message)
            assertEquals(index + 1, requests.size)
        }
    }

    private fun clientResponding(
        body: String,
        requestedQueries: MutableList<String>? = null
    ): LrcLibLyricsRepository {
        return repositoryResponding { request ->
            request.url.queryParameter("q")?.let { query -> requestedQueries?.add(query) }
            body
        }
    }

    private fun repositoryResponding(responseBody: (Request) -> String): LrcLibLyricsRepository {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = responseBody(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return LrcLibLyricsRepository(LrcLibClient(okHttpClient))
    }
}
