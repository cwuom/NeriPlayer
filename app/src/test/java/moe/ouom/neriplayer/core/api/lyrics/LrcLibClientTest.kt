package moe.ouom.neriplayer.core.api.lyrics

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
