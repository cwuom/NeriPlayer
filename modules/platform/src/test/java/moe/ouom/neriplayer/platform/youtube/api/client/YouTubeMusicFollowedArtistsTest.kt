package moe.ouom.neriplayer.platform.youtube.api.client

import java.io.IOException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.youtube.api.auth.YouTubeAuthProvider
import moe.ouom.neriplayer.platform.youtube.api.auth.evaluateYouTubeAuthHealth
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicFollowedArtistsTest {
    @Test
    fun requestsSubscriptionsWithSavedAccountAndCollectsAllPages() = runBlocking {
        val requests = mutableListOf<Request>()
        val payloads = mutableListOf<JSONObject>()
        val client = client(requests = requests) { payload ->
            payloads += payload
            when (payload.optString("continuation")) {
                "" -> initialPage("UCfirst", "First Artist", "next-artists")
                "next-artists" -> continuationPage(
                    """${artist("UCfirst", "Duplicate")},${artist("UCsecond", "Second Artist")}"""
                )
                else -> error("Unexpected continuation")
            }
        }

        val artists = client.getFollowedArtists()

        assertEquals(listOf("UCfirst", "UCsecond"), artists.map { it.browseId })
        assertEquals(listOf("First Artist", "Second Artist"), artists.map { it.title })
        assertEquals("FEmusic_library_corpus_artists", payloads[0].getString("browseId"))
        assertFalse(payloads[0].has("params"))
        assertEquals("next-artists", payloads[1].getString("continuation"))
        assertFalse(payloads[1].has("browseId"))
        payloads.forEach { payload ->
            assertEquals("WEB_REMIX", payload.getJSONObject("context").getJSONObject("client").getString("clientName"))
        }
        requests.filter { it.url.encodedPath == "/youtubei/v1/browse" }.forEach { request ->
            assertEquals("music.youtube.com", request.url.host)
            assertEquals("2", request.header("X-Goog-AuthUser"))
            assertTrue(request.header("Cookie").orEmpty().contains("SAPISID=test-session"))
            assertTrue(request.header("Authorization").orEmpty().startsWith("SAPISIDHASH "))
        }
    }

    @Test
    fun refusesGuestSessionBeforeAnyRequest() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(auth = Session(YouTubeAuthBundle()), requests = requests) {
            error("Guest must not request followed artists")
        }

        expectIoFailure { client.getFollowedArtists() }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun rejectsSignInResponseInsteadOfReturningEmptyLibrary() = runBlocking {
        val client = client {
            """{"contents":{"sectionListRenderer":{"contents":[{"messageRenderer":{
              "text":{"simpleText":"Sign in"}
            }}]}}}"""
        }

        expectIoFailure { client.getFollowedArtists() }
    }

    @Test
    fun returnsEmptyListForRecognizedEmptyShelf() = runBlocking {
        val client = client { initialPage(null) }

        assertTrue(client.getFollowedArtists().isEmpty())
    }

    @Test
    fun rejectsRepeatedContinuationInsteadOfReturningPartialArtists() = runBlocking {
        var browseRequests = 0
        val client = client { payload ->
            browseRequests++
            if (payload.has("browseId")) {
                initialPage("UCfirst", continuation = "repeated-token")
            } else {
                continuationPage(artist("UCsecond"), "repeated-token")
            }
        }

        expectIoFailure { client.getFollowedArtists() }

        assertEquals(2, browseRequests)
    }

    @Test
    fun propagatesContinuationFailureInsteadOfReturningPartialArtists() = runBlocking {
        val client = client { payload ->
            if (payload.has("browseId")) {
                initialPage("UCfirst", continuation = "next-artists")
            } else {
                throw IOException("Continuation transport failed")
            }
        }

        expectIoFailure { client.getFollowedArtists() }
    }

    @Test
    fun rejectsLogoutDuringRequest() = runBlocking {
        val session = Session()
        val client = client(auth = session) {
            session.auth = YouTubeAuthBundle()
            initialPage("UCfirst")
        }

        expectIoFailure { client.getFollowedArtists() }
    }

    @Test
    fun continuesThroughEmptyPageWithContinuation() = runBlocking {
        val client = client { payload ->
            if (payload.has("browseId")) {
                initialPage(null, continuation = "next-artists")
            } else {
                continuationPage(artist("UCsecond"))
            }
        }

        assertEquals(listOf("UCsecond"), client.getFollowedArtists().map { it.browseId })
    }

    @Test
    fun failsWhenPaginationDoesNotReachAnEnd() = runBlocking {
        var browseRequests = 0
        val client = client { payload ->
            browseRequests++
            check(browseRequests <= 80) { "Pagination must remain bounded" }
            if (payload.has("browseId")) {
                initialPage("UCfirst", continuation = "page-1")
            } else {
                continuationPage(artist("UCartist$browseRequests"), "page-$browseRequests")
            }
        }

        expectIoFailure { client.getFollowedArtists() }

        assertTrue(browseRequests > 1)
    }

    private suspend fun expectIoFailure(block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (error: IOException) {
            error
        }
        assertTrue("Expected IOException without a partial result", error != null)
    }

    private fun client(
        auth: Session = Session(),
        requests: MutableList<Request> = mutableListOf(),
        browseResponse: (JSONObject) -> String
    ): YouTubeMusicClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = if (request.url.encodedPath == "/") {
                """{"INNERTUBE_API_KEY":"test-key","INNERTUBE_CLIENT_VERSION":"test-version",
                  "VISITOR_DATA":"test-visitor","LOGGED_IN":true,"SESSION_INDEX":0}"""
            } else {
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                browseResponse(JSONObject(buffer.readUtf8()))
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        return YouTubeMusicClient(auth, http)
    }

    private class Session(
        var auth: YouTubeAuthBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=test-session; SID=test-session",
            xGoogAuthUser = "2"
        )
    ) : YouTubeAuthProvider {
        override fun getAuthOnce() = auth
        override fun getAuthHealthOnce() = evaluateYouTubeAuthHealth(auth)
    }

    private fun initialPage(
        browseId: String?,
        title: String = "Demo Artist",
        continuation: String? = null
    ): String = """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{
          "content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{
            "contents":[${browseId?.let { artist(it, title) }.orEmpty()}]${next(continuation)}
          }}]}}
        }}]}}}
    """.trimIndent()

    private fun continuationPage(items: String, continuation: String? = null): String = """
        {"continuationContents":{"musicShelfContinuation":{
          "contents":[$items]${next(continuation)}
        }}}
    """.trimIndent()

    private fun next(continuation: String?): String = continuation?.let {
        ",\"continuations\":[{\"nextContinuationData\":{\"continuation\":\"$it\"}}]"
    }.orEmpty()

    private fun artist(browseId: String, title: String = "Demo Artist"): String = """
        {"musicResponsiveListItemRenderer":{
          "navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId"}},
          "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{
            "text":{"simpleText":"$title"}
          }}]
        }}
    """.trimIndent()
}
