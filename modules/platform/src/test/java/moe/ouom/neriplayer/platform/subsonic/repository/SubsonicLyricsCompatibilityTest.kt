package moe.ouom.neriplayer.platform.subsonic.repository

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicFailureKind
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicCredentials
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class SubsonicLyricsCompatibilityTest {
    private val profile = SubsonicProfile("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Server",
        "https://example.test/music/", "fixture-user")
    private val ref = ServerSongRef(profile.id, "track / 中文")
    private val song = SongItem(ref.numericId, "Title / 中文", "Artist & 歌手", "Album", 0L,
        10_000L, null, mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)

    private data class Reply(val status: Int = 200, val data: JSONObject = JSONObject())
    private data class Fixture(val repository: SubsonicRepository, val methods: List<String>)

    private fun fixture(scope: CoroutineScope, response: (String, HttpUrl) -> Reply): Fixture {
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.profiles).thenReturn(MutableStateFlow(listOf(profile)))
        `when`(accounts.profile(profile.id)).thenReturn(profile)
        `when`(accounts.credentials(profile.id)).thenReturn(SubsonicCredentials(profile, "fixture-password"))
        val methods = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val method = url.pathSegments.last().removeSuffix(".view")
            methods += method
            val reply = response(method, url)
            val body = JSONObject().put("subsonic-response", reply.data).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(reply.status).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Fixture(SubsonicRepository(accounts, SubsonicClient(client),
            SubsonicBrowseCache(scope = scope)), methods)
    }

    private fun ok(data: JSONObject = JSONObject()) = Reply(data = data.put("status", "ok"))
    private fun extensions(versions: JSONArray? = JSONArray().put(1)) = ok(JSONObject()
        .put("openSubsonicExtensions", JSONArray().apply {
            if (versions != null) put(JSONObject().put("name", "songLyrics").put("versions", versions))
        }))
    private fun legacy(value: String) = ok(JSONObject().put("lyrics", JSONObject().put("value", value)))
    private fun structured(value: String? = "structured line") = ok(JSONObject().put("lyricsList", JSONObject()
        .put("structuredLyrics", JSONArray().apply {
            if (value != null) put(JSONObject().put("synced", true).put("line", JSONArray()
                .put(JSONObject().put("start", 1200L).put("value", value))))
        })))
    private fun protocolError(code: Int) = Reply(data = JSONObject().put("status", "failed")
        .put("error", JSONObject().put("code", code)))

    @Test fun standardLyricsFallbackPreservesLrcAndCachesSuccessfulResults() = runTest {
        val fixture = fixture(backgroundScope) { method, url ->
            when (method) {
                "getOpenSubsonicExtensions" -> extensions(null)
                "getLyrics" -> {
                    assertEquals(song.artist, url.queryParameter("artist"))
                    assertEquals(song.name, url.queryParameter("title"))
                    legacy("[00:01.00]first line\n[00:03.50]second line")
                }
                else -> Reply(404)
            }
        }
        val result = fixture.repository.lyrics(song)
        assertEquals(listOf("first line", "second line"), result.map { it.text })
        assertEquals(listOf(1000L, 3500L), result.map { it.startTimeMs })
        assertEquals(result, fixture.repository.lyrics(song))
        assertEquals(listOf("getOpenSubsonicExtensions", "getLyrics"), fixture.methods)
    }

    @Test fun standardLyricsFallbackConvertsPlainText() = runTest {
        val fixture = fixture(backgroundScope) { method, _ ->
            if (method == "getOpenSubsonicExtensions") extensions(null) else legacy(" first \n\n second ")
        }
        val result = fixture.repository.lyrics(song)
        assertEquals(listOf("first", "second"), result.map { it.text })
        assertEquals(listOf(0L, 5000L), result.map { it.startTimeMs })
    }

    @Test fun standardLyricsFallbackCanSearchByTitleWithoutArtist() = runTest {
        val fixture = fixture(backgroundScope) { method, url ->
            if (method == "getOpenSubsonicExtensions") extensions(null) else {
                assertEquals("getLyrics", method)
                assertEquals(song.name, url.queryParameter("title"))
                assertFalse("artist" in url.queryParameterNames)
                legacy("title matched line")
            }
        }
        assertEquals("title matched line", fixture.repository.lyrics(song.copy(artist = " ")).single().text)
        assertEquals(listOf("getOpenSubsonicExtensions", "getLyrics"), fixture.methods)
    }

    @Test fun standardLyricsFallbackCanSearchByArtistWithoutTitle() = runTest {
        val fixture = fixture(backgroundScope) { method, url ->
            if (method == "getOpenSubsonicExtensions") extensions(null) else {
                assertEquals("getLyrics", method)
                assertEquals(song.artist, url.queryParameter("artist"))
                assertFalse("title" in url.queryParameterNames)
                legacy("artist matched line")
            }
        }
        assertEquals("artist matched line", fixture.repository.lyrics(song.copy(name = " ")).single().text)
        assertEquals(listOf("getOpenSubsonicExtensions", "getLyrics"), fixture.methods)
    }

    @Test fun standardLyricsDoesNotQueryWithoutEitherSearchFieldAfterCapabilityDiscovery() = runTest {
        val fixture = fixture(backgroundScope) { method, _ ->
            if (method == "getOpenSubsonicExtensions") extensions(null) else legacy("available line")
        }
        assertEquals("available line", fixture.repository.lyrics(song).single().text)
        val blankRef = ref.copy(songId = "track without display tags")
        val blank = song.copy(id = blankRef.numericId, name = " ", artist = " ",
            audioId = blankRef.audioId, mediaUri = blankRef.mediaUri)
        assertTrue(fixture.repository.lyrics(blank).isEmpty())
        assertTrue(fixture.repository.lyrics(blank).isEmpty())
        assertEquals(listOf("getOpenSubsonicExtensions", "getLyrics"), fixture.methods)
    }

    @Test fun absentMalformedAndUnknownExtensionVersionsUseStandardLyrics() = runTest {
        for (versions in listOf(JSONArray(), JSONArray().put(999), JSONArray().put("invalid"),
            JSONArray().put(0).put(-1))) {
            val fixture = fixture(backgroundScope) { method, _ ->
                when (method) {
                    "getOpenSubsonicExtensions" -> extensions(versions)
                    "getLyrics" -> legacy("legacy line")
                    else -> Reply(404)
                }
            }
            assertEquals(listOf("legacy line"), fixture.repository.lyrics(song).map { it.text })
            assertEquals(listOf("getOpenSubsonicExtensions", "getLyrics"), fixture.methods)
        }
    }

    @Test fun missingOptionalExtensionEndpointFallsBackToStandardLyrics() = runTest {
        for (failure in listOf(Reply(404), Reply(501), protocolError(70), protocolError(0))) {
            val fixture = fixture(backgroundScope) { method, _ ->
                if (method == "getOpenSubsonicExtensions") failure else legacy("legacy line")
            }
            assertEquals(listOf("legacy line"), fixture.repository.lyrics(song).map { it.text })
            assertEquals(listOf("getOpenSubsonicExtensions", "getLyrics"), fixture.methods)
        }
    }

    @Test fun missingOptionalStructuredEndpointFallsBackWithoutDisablingOtherSongs() = runTest {
        for (failure in listOf(Reply(404), Reply(501), protocolError(70), protocolError(0))) {
            val fixture = fixture(backgroundScope) { method, url ->
                when (method) {
                    "getOpenSubsonicExtensions" -> extensions()
                    "getLyricsBySongId" -> if (url.queryParameter("id") == ref.songId) failure else structured()
                    "getLyrics" -> legacy("legacy line")
                    else -> Reply(404)
                }
            }
            assertEquals(listOf("legacy line"), fixture.repository.lyrics(song).map { it.text })
            val otherRef = ref.copy(songId = "another track")
            val other = song.copy(id = otherRef.numericId, audioId = otherRef.audioId, mediaUri = otherRef.mediaUri)
            assertEquals(listOf("structured line"), fixture.repository.lyrics(other).map { it.text })
            assertEquals(listOf("getOpenSubsonicExtensions", "getLyricsBySongId", "getLyrics",
                "getLyricsBySongId"), fixture.methods)
        }
    }

    @Test fun knownVersionsKeepStructuredLyricsWithoutOptingIntoEnhancedPayloads() = runTest {
        for (versions in listOf(JSONArray().put(1), JSONArray().put(1).put(2), JSONArray().put(2))) {
            val fixture = fixture(backgroundScope) { method, url ->
                if (method == "getOpenSubsonicExtensions") extensions(versions) else {
                    assertEquals("getLyricsBySongId", method)
                    assertEquals(ref.songId, url.queryParameter("id"))
                    assertEquals(null, url.queryParameter("enhanced"))
                    structured()
                }
            }
            val result = fixture.repository.lyrics(song)
            assertEquals("structured line", result.single().text)
            assertEquals(1200L, result.single().startTimeMs)
            assertEquals(result, fixture.repository.lyrics(song))
            assertEquals(listOf("getOpenSubsonicExtensions", "getLyricsBySongId"), fixture.methods)
        }
    }

    @Test fun authenticationRateLimitAndServerFailuresNeverBecomeLegacyOrEmptyLyrics() = runTest {
        for (failedMethod in listOf("getOpenSubsonicExtensions", "getLyricsBySongId", "getLyrics")) {
            for ((status, kind) in listOf(401 to SubsonicFailureKind.AUTHENTICATION,
                403 to SubsonicFailureKind.FORBIDDEN, 429 to SubsonicFailureKind.RATE_LIMITED,
                503 to SubsonicFailureKind.SERVER)) {
                val fixture = fixture(backgroundScope) { method, _ ->
                    if (method == failedMethod) Reply(status)
                    else if (method == "getOpenSubsonicExtensions") {
                        if (failedMethod == "getLyrics") extensions(null) else extensions()
                    } else legacy("unexpected fallback")
                }
                val failure = runCatching { fixture.repository.lyrics(song) }.exceptionOrNull()
                assertTrue(failure is SubsonicException)
                assertEquals(kind, (failure as SubsonicException).kind)
                assertEquals(status, failure.httpStatus)
                assertEquals(failedMethod, fixture.methods.last())
            }
        }
    }

    @Test fun transportFailureIsNotCachedAsNoLyricsAndCanRetry() = runTest {
        var fail = true
        val fixture = fixture(backgroundScope) { method, _ ->
            if (method == "getOpenSubsonicExtensions") extensions() else {
                if (fail) throw IOException("fixture transport failure")
                structured()
            }
        }
        val failure = runCatching { fixture.repository.lyrics(song) }.exceptionOrNull()
        assertTrue(failure is SubsonicException)
        assertEquals(SubsonicFailureKind.NETWORK, (failure as SubsonicException).kind)
        fail = false
        assertEquals("structured line", fixture.repository.lyrics(song).single().text)
        assertEquals(listOf("getOpenSubsonicExtensions", "getLyricsBySongId", "getLyricsBySongId"), fixture.methods)
    }

    @Test fun successfulEmptyStructuredLyricsDoNotFetchLegacyAndAreBrieflyCached() = runTest {
        val fixture = fixture(backgroundScope) { method, _ ->
            when (method) {
                "getOpenSubsonicExtensions" -> extensions()
                "getLyricsBySongId" -> structured(null)
                else -> legacy("unexpected fallback")
            }
        }
        assertTrue(fixture.repository.lyrics(song).isEmpty())
        assertTrue(fixture.repository.lyrics(song).isEmpty())
        assertEquals(listOf("getOpenSubsonicExtensions", "getLyricsBySongId"), fixture.methods)
    }

    @Test fun successfulEmptyStandardLyricsAreBrieflyCached() = runTest {
        val fixture = fixture(backgroundScope) { method, _ ->
            if (method == "getOpenSubsonicExtensions") extensions(null) else legacy(" \n\t ")
        }
        assertTrue(fixture.repository.lyrics(song).isEmpty())
        assertTrue(fixture.repository.lyrics(song).isEmpty())
        assertEquals(listOf("getOpenSubsonicExtensions", "getLyrics"), fixture.methods)
    }

    @Test fun explicitRefreshBypassesPositiveAndNegativeResultsWithoutRepeatingCapabilityProbe() = runTest {
        for (original in listOf(null, "original line")) {
            var value = original
            val fixture = fixture(backgroundScope) { method, _ ->
                if (method == "getOpenSubsonicExtensions") extensions() else structured(value)
            }
            val first = fixture.repository.lyrics(song)
            value = "updated line"
            assertEquals(first, fixture.repository.lyrics(song))
            val refreshed = fixture.repository.lyrics(song, forceRefresh = true)
            assertEquals("updated line", refreshed.single().text)
            assertEquals(refreshed, fixture.repository.lyrics(song))
            assertEquals(listOf("getOpenSubsonicExtensions", "getLyricsBySongId", "getLyricsBySongId"), fixture.methods)
        }
    }

    @Test fun legacyMissingBlankNullAndNonStringValuesAreEmpty() {
        for (json in listOf(JSONObject(), JSONObject().put("lyrics", JSONObject()),
            legacy(" \n\t").data, JSONObject().put("lyrics", JSONObject().put("value", JSONObject.NULL)),
            JSONObject().put("lyrics", JSONObject().put("value", 42)))) {
            assertTrue(parseLegacyServerLyrics(json, song.durationMs).isEmpty())
        }
    }
}
