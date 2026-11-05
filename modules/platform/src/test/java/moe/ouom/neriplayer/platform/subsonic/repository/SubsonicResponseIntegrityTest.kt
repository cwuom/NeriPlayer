package moe.ouom.neriplayer.platform.subsonic.repository

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class SubsonicResponseIntegrityTest {
    private val profile = SubsonicProfile(
        "9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Server", "https://example.test/music/", "user"
    )
    private val albumId = "album / 中文"
    private val ref = ServerSongRef(profile.id, "track / 中文")
    private val song = SongItem(ref.numericId, "Track", "Artist", "Album", 0, 123_000, null,
        mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)

    private fun repository(cache: SubsonicBrowseCache, response: () -> JSONObject): SubsonicRepository {
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.profiles).thenReturn(MutableStateFlow(listOf(profile)))
        `when`(accounts.profile(profile.id)).thenReturn(profile)
        `when`(accounts.credentials(profile.id)).thenReturn(SubsonicCredentials(profile, "test-password"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = JSONObject().put("subsonic-response", response().put("status", "ok"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        return SubsonicRepository(accounts, SubsonicClient(client), cache)
    }

    private fun album() = JSONObject().put("id", albumId).put("song", JSONArray()
        .put(JSONObject().put("id", ref.songId).put("title", "Track")))

    private suspend fun assertInvalid(load: suspend () -> Unit) {
        try {
            load()
            fail("Malformed or mismatched response must fail")
        } catch (error: SubsonicException) {
            assertEquals(SubsonicFailureKind.INVALID_RESPONSE, error.kind)
        }
    }

    @Test fun `wrong album identity is rejected without replacing the cached songs`() = runTest {
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        var current = album()
        val repo = repository(cache) { JSONObject().put("album", current) }
        val key = repo.browseKey(profile.id, albumId)
        val previous = repo.browse(key)
        current = album().put("id", "another album")
        assertInvalid { repo.browse(key, force = true) }
        assertEquals(previous, cache.snapshot(key))
    }

    @Test fun `malformed album rows never replace the last complete snapshot`() = runTest {
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        var current = album()
        val repo = repository(cache) { JSONObject().put("album", current) }
        val key = repo.browseKey(profile.id, albumId)
        val previous = repo.browse(key)
        for (rows in listOf<Any>(JSONObject(), "truncated", JSONArray().put(JSONObject().put("id", ref.songId)).put(7),
            JSONArray().put(JSONObject().put("id", 7)), JSONArray().put(JSONObject()))) {
            current = album().put("song", rows)
            assertInvalid { repo.browse(key, force = true) }
            assertEquals(previous, cache.snapshot(key))
        }
    }

    @Test fun `an explicitly empty album remains a valid cacheable result`() = runTest {
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        var calls = 0
        val repo = repository(cache) { calls++; JSONObject().put("album", album().put("song", JSONArray())) }
        assertTrue(repo.albumSongs(profile.id, albumId).isEmpty())
        assertTrue(repo.albumSongs(profile.id, albumId).isEmpty())
        assertEquals(1, calls)
    }

    @Test fun `malformed album search is not cached as a successful empty result`() = runTest {
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        var current = JSONObject()
        val repo = repository(cache) { current }
        val key = repo.browseKey(profile.id, query = "Artist")
        for (response in listOf(JSONObject(), JSONObject().put("searchResult3", "truncated"),
            JSONObject().put("searchResult3", JSONObject().put("album", JSONObject())),
            JSONObject().put("searchResult3", JSONObject().put("album", JSONArray().put(7))))) {
            current = response
            assertInvalid { repo.browse(key, force = true) }
        }
        current = JSONObject().put("searchResult3", JSONObject().put("album", JSONArray().put(album())))
        assertEquals(albumId, repo.browse(key).albums.single().id)
    }

    @Test fun `song lookup cannot return another song identity`() = runTest {
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) {
            JSONObject().put("song", JSONObject().put("id", "different song"))
        }
        assertInvalid { repo.song(ref) }
    }

    @Test fun `wrong playback metadata is rejected and not remembered for a later request`() = runTest {
        var id = "different song"
        var calls = 0
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) {
            calls++
            JSONObject().put("song", JSONObject().put("id", id).put("suffix", "flac").put("size", 1234))
        }
        assertInvalid { repo.playback(song) }
        id = ref.songId
        val result = repo.playback(song)
        assertTrue(result is moe.ouom.neriplayer.data.model.playback.SongUrlResult.Success)
        assertEquals(2, calls)
    }

    @Test fun `missing song object is a safe invalid response in lookup playback and download`() = runTest {
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) { JSONObject() }
        assertInvalid { repo.song(ref) }
        assertInvalid { repo.playback(song) }
        assertInvalid { repo.download(song) }
    }

    @Test fun `numeric identifiers are not coerced to matching string identifiers`() = runTest {
        val numericRef = ServerSongRef(profile.id, "7")
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) {
            JSONObject().put("album", JSONObject().put("id", 7)).put("song", JSONObject().put("id", 7))
        }
        assertInvalid { repo.albumSongs(profile.id, "7") }
        assertInvalid { repo.song(numericRef) }
    }
}
