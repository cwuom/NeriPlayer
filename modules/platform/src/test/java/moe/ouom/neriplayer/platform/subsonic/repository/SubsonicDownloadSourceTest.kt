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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class SubsonicDownloadSourceTest {
    private val profile = SubsonicProfile(
        "9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Server", "https://example.test/music/", "user"
    )
    private val ref = ServerSongRef(profile.id, "track / 中文")
    private val song = SongItem(ref.numericId, "Track", "Artist", "Album", 0, 0, null,
        mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)
    private val accounts = mock(SubsonicAccounts::class.java).also {
        `when`(it.profiles).thenReturn(MutableStateFlow(listOf(profile)))
        `when`(it.profile(profile.id)).thenReturn(profile)
        `when`(it.credentials(profile.id)).thenReturn(SubsonicCredentials(profile, "test-password"))
    }

    private fun repository(response: () -> JSONObject): SubsonicRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/music/rest/getSong.view", chain.request().url.encodedPath)
            assertEquals("track / 中文", chain.request().url.queryParameter("id"))
            val json = JSONObject().put("subsonic-response", JSONObject()
                .put("status", "ok").put("song", response()))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        return SubsonicRepository(accounts, SubsonicClient(client))
    }

    private fun metadata(suffix: String = "flac") = JSONObject()
        .put("id", "track / 中文").put("suffix", suffix).put("size", 1234567L).put("duration", 123)

    @Test
    fun `download keeps the original container and only returns an opaque account scoped URL`() = runTest {
        for ((suffix, mime) in listOf("mp3" to "audio/mpeg", "flac" to "audio/flac",
            "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
            "opus" to "audio/ogg", "aiff" to "audio/aiff")) {
            val source = repository { metadata(suffix) }.download(song)
            assertEquals(suffix, source.fileExtensionHint)
            assertEquals(mime, source.mimeType)
            assertEquals(1234567L, source.contentLength)
            assertEquals(123000L, source.durationMs)
            assertEquals("https://neri-server.invalid/v1/${profile.id}/download/dHJhY2sgLyDkuK3mloc", source.url)
            assertFalse(source.toString().contains("test-password"))
            assertFalse(source.url.contains("example.test"))
            assertFalse(source.url.contains("?"))
        }
    }

    @Test
    fun `download refreshes metadata instead of using a stale playback representation`() = runTest {
        var current = metadata("mp3")
        val repo = repository { current }
        repo.playback(song)
        current = metadata("flac").put("size", 7654321L)
        val source = repo.download(song)
        assertEquals("flac", source.fileExtensionHint)
        assertEquals(7654321L, source.contentLength)
    }

    @Test
    fun `untrusted suffix and nonpositive size are not used as a local file extension or length`() = runTest {
        val source = repository { metadata("../escape").put("contentType", "application/octet-stream")
            .put("size", -1).put("duration", -1) }.download(song)
        assertNull(source.fileExtensionHint)
        assertNull(source.mimeType)
        assertNull(source.contentLength)
        assertNull(source.durationMs)
    }

    @Test
    fun `a response for another song cannot be published as the requested download`() = runTest {
        try {
            repository { metadata().put("id", "different-track") }.download(song)
            fail("Mismatched source identity must fail")
        } catch (error: SubsonicException) {
            assertEquals(SubsonicFailureKind.INVALID_RESPONSE, error.kind)
        }
    }

    @Test
    fun `removed account fails before any media source is returned`() = runTest {
        `when`(accounts.profile(profile.id)).thenReturn(null)
        var calls = 0
        try {
            repository { calls++; metadata() }.download(song)
            fail("Missing account must fail")
        } catch (error: SubsonicException) {
            assertEquals(SubsonicFailureKind.ACCOUNT_UNAVAILABLE, error.kind)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `an account revision change during resolution rejects the old response`() = runTest {
        try {
            repository {
                `when`(accounts.profile(profile.id)).thenReturn(profile.copy(revision = 1))
                metadata()
            }.download(song)
            fail("Stale account response must fail")
        } catch (error: SubsonicException) {
            assertTrue(error.kind == SubsonicFailureKind.ACCOUNT_UNAVAILABLE)
        }
    }
}
