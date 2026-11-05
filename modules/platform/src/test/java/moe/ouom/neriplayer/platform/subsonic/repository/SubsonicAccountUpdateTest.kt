package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicCredentials
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SubsonicAccountUpdateTest {
    private val profile = SubsonicProfile(
        "9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Server", "https://example.test/music/", "user"
    )
    private val accounts = mock(SubsonicAccounts::class.java).also {
        `when`(it.profiles).thenReturn(MutableStateFlow(listOf(profile)))
        `when`(it.credentials(profile.id)).thenReturn(SubsonicCredentials(profile, "old-password"))
    }
    private var requests = 0
    private val client = SubsonicClient(OkHttpClient.Builder().addInterceptor { chain ->
        requests++
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"subsonic-response":{"status":"ok"}}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build())

    @Test
    fun `changed host port or subpath is rejected before ping or saving credentials`() = runTest {
        val repo = SubsonicRepository(accounts, client, SubsonicBrowseCache(scope = backgroundScope))
        listOf("https://other.test/music/", "https://example.test:4533/music/", "https://example.test/other/")
            .forEach { address ->
                try {
                    repo.updateAccount(profile, "Renamed", address, "new-password")
                    fail("A different endpoint must use a new profile")
                } catch (_: IllegalArgumentException) {
                    // No authentication or account mutation may precede the endpoint check.
                }
            }
        assertEquals(0, requests)
        assertTrue(mockingDetails(accounts).invocations.none { it.method.name == "save" })
        assertEquals(profile, accounts.profiles.value.single())
    }

    @Test
    fun `rename accepts the same normalized endpoint and retains password and source identity`() = runTest {
        val repo = SubsonicRepository(accounts, client, SubsonicBrowseCache(scope = backgroundScope))

        repo.updateAccount(profile, " Renamed ", " https://example.test/music ", "")

        verify(accounts).save(profile.copy(label = "Renamed", revision = 1L), "old-password", 0L)
        assertEquals(1, requests)
    }

    @Test
    fun `password change retains the profile ID and checks the revision`() = runTest {
        val repo = SubsonicRepository(accounts, client, SubsonicBrowseCache(scope = backgroundScope))

        repo.updateAccount(profile, profile.label, profile.baseUrl, "new-password")

        verify(accounts).save(profile.copy(revision = 1L), "new-password", 0L)
        assertEquals(1, requests)
    }

    @Test
    fun `a new endpoint profile isolates media prefetch artwork and restored references with matching IDs`() {
        val other = SubsonicAccounts.draft("Other", "https://other.test/music/", profile.username)
        val oldRef = ServerSongRef(profile.id, "same-song-id")
        val newRef = ServerSongRef(other.id, "same-song-id")

        assertNotEquals(oldRef.cacheKey, newRef.cacheKey)
        assertNotEquals(oldRef.resourceUrl("cover", "same-cover-id"), newRef.resourceUrl("cover", "same-cover-id"))
        assertEquals(oldRef, ServerSongRef.fromMediaUri(oldRef.mediaUri))
        assertNotEquals(oldRef, ServerSongRef.fromMediaUri(newRef.mediaUri))
    }
}
