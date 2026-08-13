package moe.ouom.neriplayer.core.api.lyrics

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcLibClientTest {

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

    private fun clientResponding(body: String): LrcLibClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return LrcLibClient(okHttpClient)
    }
}
