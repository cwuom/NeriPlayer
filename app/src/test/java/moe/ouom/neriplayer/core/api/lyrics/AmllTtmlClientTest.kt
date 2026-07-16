package moe.ouom.neriplayer.core.api.lyrics

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmllTtmlClientTest {

    @Test
    fun `scoreAmllSearchResult prefers matching title and artist`() {
        val result = AmllTtmlSearchResult(
            file = "me.ttml",
            title = "ME!",
            titles = listOf("ME!"),
            artist = "Taylor Swift",
            artists = listOf("Taylor Swift", "Brendon Urie"),
            albums = listOf("Lover"),
            ncmIds = emptyList(),
            qqIds = emptyList(),
            score = 100
        )

        val score = scoreAmllSearchResult(
            trackName = "ME!",
            artistName = "Taylor Swift",
            result = result
        )

        assertTrue(score >= 140)
    }

    @Test
    fun `scoreAmllSearchResult rejects exact title with mismatched artist`() {
        val result = AmllTtmlSearchResult(
            file = "same-title.ttml",
            title = "Hello",
            titles = listOf("Hello"),
            artist = "Other Artist",
            artists = listOf("Other Artist"),
            albums = emptyList(),
            ncmIds = emptyList(),
            qqIds = emptyList(),
            score = 100
        )

        val score = scoreAmllSearchResult(
            trackName = "Hello",
            artistName = "Expected Artist",
            result = result
        )

        assertTrue(score < 70)
    }

    @Test
    fun `searchLyrics returns empty list when AMLL response is malformed`() = runTest {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("not-json".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val client = AmllTtmlClient(okHttpClient, baseUrl = "https://amll.test")

        val results = client.searchLyrics("Hello", "Expected Artist")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `isAmllDurationCompatible accepts bounded drift and rejects distant match`() {
        assertTrue(isAmllDurationCompatible(180_000L, 188_000L))
        assertTrue(isAmllDurationCompatible(253_000L, 231_670L))
        assertFalse(isAmllDurationCompatible(180_000L, 240_000L))
    }

    @Test
    fun `normalizeAmllSearchText folds punctuation and compatibility forms`() {
        assertEquals(
            "hello world",
            normalizeAmllSearchText("Ｈｅｌｌｏ（World）")
        )
    }
}
