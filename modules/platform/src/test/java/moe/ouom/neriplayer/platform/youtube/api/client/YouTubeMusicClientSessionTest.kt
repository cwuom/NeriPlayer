package moe.ouom.neriplayer.platform.youtube.api.client

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeMusicClientSessionTest {
    private val originalLocale = Locale.getDefault()
    private val noLyricsNext = json("""{"contents":{}}""")
    private val creator = YouTubeMusicCreatorSummary("UCcreator", "Demo Creator", "", "")

    @Before
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `bootstrap is reused until the auth fingerprint changes`() = runTest {
        val auth = FakeYouTubeAuth(GUEST_AUTH)
        val http = YouTubeMusicTestHttp { call -> if (call.isBootstrap) bootstrapPage() else noLyricsNext }
        val client = YouTubeMusicClient(auth, http.client)

        assertNull(client.getLyrics("first"))
        assertNull(client.getLyrics("second"))
        assertEquals(1, http.bootstrapCalls().size)

        auth.auth = LOGIN_AUTH
        assertNull(client.getLyrics("third"))
        client.clearBootstrapCache()
        assertNull(client.getLyrics("fourth"))

        assertEquals(3, http.bootstrapCalls().size)
        assertEquals(
            listOf("first", "second", "third", "fourth"),
            http.innertubeCalls("next").map { it.payload!!.getString("videoId") }
        )
        val loginBootstrap = http.bootstrapCalls()[1].request
        assertEquals("music.youtube.com", loginBootstrap.url.host)
        assertTrue(loginBootstrap.header("Cookie").orEmpty().contains("SAPISID=test-sapisid"))
        assertEquals("1", loginBootstrap.header("X-Goog-AuthUser"))
    }

    @Test
    fun `bootstrap refreshes auth once both pages reject the saved cookies`() = runTest {
        val auth = FakeYouTubeAuth(LOGIN_AUTH)
        val refresher = RecordingYouTubeRefresher { reason ->
            if (reason == "music_bootstrap_http_recoverable") {
                auth.auth = LOGIN_AUTH.copy(cookieHeader = "SAPISID=fresh-sapisid; SID=fresh-sid")
            }
            true
        }
        val http = YouTubeMusicTestHttp { call ->
            when {
                !call.isBootstrap -> noLyricsNext
                call.request.header("Cookie").orEmpty().contains("SAPISID=test-sapisid") ->
                    YouTubeMusicReply("expired session", code = 401)
                else -> bootstrapPage(loggedIn = true)
            }
        }
        val client = YouTubeMusicClient(auth, http.client, refresher)

        assertNull(client.getLyrics("song"))

        assertEquals(listOf("lyrics" to false, "music_bootstrap_http_recoverable" to true), refresher.calls)
        assertEquals(
            listOf("music.youtube.com", "www.youtube.com", "music.youtube.com"),
            http.bootstrapCalls().map { it.request.url.host }
        )
        assertTrue(
            http.innertubeCalls("next").single().request.header("Cookie").orEmpty().contains("SAPISID=fresh-sapisid")
        )
    }

    @Test
    fun `bootstrap failures without saved cookies surface the last page error`() = runTest {
        val refresher = RecordingYouTubeRefresher()
        val http = YouTubeMusicTestHttp { call ->
            if (call.isBootstrap) YouTubeMusicReply("down on ${call.request.url.host}", code = 503) else noLyricsNext
        }
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client, refresher)

        val error = runCatching { client.getLyrics("song") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("YouTube Music request failed: 503 down on www.youtube.com", error?.message)
        assertEquals(listOf("lyrics" to false), refresher.calls)
        assertEquals(
            listOf("music.youtube.com", "www.youtube.com"),
            http.bootstrapCalls().map { it.request.url.host }
        )
        assertTrue(http.innertubeCalls("next").isEmpty())
    }

    @Test
    fun `library lookup trusts a bootstrap that already proves or cannot prove the login`() = runTest {
        listOf(GUEST_AUTH to false, LOGIN_AUTH to true, LOGIN_AUTH to false).forEach { (auth, loggedIn) ->
            val http = libraryHttp(loggedInBootstrap = { loggedIn })
            val client = YouTubeMusicClient(FakeYouTubeAuth(auth), http.client)

            val playlists = client.getLibraryPlaylists(resolveMissingTrackCounts = false)

            assertEquals(listOf("VLPLmix"), playlists.map { it.browseId })
            assertEquals(12, playlists.single().trackCount)
            assertEquals(1, http.bootstrapCalls().size)
            val authorization = http.innertubeCalls("browse").single().request.header("Authorization")
            assertEquals(auth == LOGIN_AUTH, authorization.orEmpty().startsWith("SAPISIDHASH "))
        }
    }

    @Test
    fun `cookie context without a login forces one bootstrap refresh`() = runTest {
        val http = libraryHttp(loggedInBootstrap = { false })
        val client = YouTubeMusicClient(FakeYouTubeAuth(PREFERENCE_ONLY_AUTH), http.client)

        val playlists = client.getLibraryPlaylists(resolveMissingTrackCounts = false)

        assertEquals(listOf("VLPLmix"), playlists.map { it.browseId })
        assertEquals(2, http.bootstrapCalls().size)
        assertNull(http.innertubeCalls("browse").single().request.header("Authorization"))
    }

    @Test
    fun `bootstrap refresh can recover the login from cookies or the page`() = runTest {
        val auth = FakeYouTubeAuth(PREFERENCE_ONLY_AUTH)
        val refresher = RecordingYouTubeRefresher { reason ->
            if (reason == "library_playlists_bootstrap_not_logged_in") auth.auth = LOGIN_AUTH
            true
        }
        val cookieLogin = libraryHttp(loggedInBootstrap = { false })

        YouTubeMusicClient(auth, cookieLogin.client, refresher).getLibraryPlaylists(resolveMissingTrackCounts = false)

        assertEquals(listOf("library_playlists_bootstrap_not_logged_in" to true), refresher.calls)
        assertFalse(cookieLogin.bootstrapCalls()[0].request.header("Cookie").orEmpty().contains("SAPISID"))
        assertTrue(cookieLogin.bootstrapCalls()[1].request.header("Cookie").orEmpty().contains("SAPISID=test-sapisid"))
        assertTrue(cookieLogin.innertubeCalls("browse").single().request.header("Authorization")!!.startsWith("SAPISIDHASH "))

        val pageRefresher = RecordingYouTubeRefresher { true }
        var bootstraps = 0
        val pageLogin = libraryHttp(loggedInBootstrap = { ++bootstraps > 1 })

        val playlists = YouTubeMusicClient(FakeYouTubeAuth(PREFERENCE_ONLY_AUTH), pageLogin.client, pageRefresher)
            .getLibraryPlaylists(resolveMissingTrackCounts = false)

        assertEquals(listOf("VLPLmix"), playlists.map { it.browseId })
        assertEquals(listOf("library_playlists_bootstrap_not_logged_in" to true), pageRefresher.calls)
        assertEquals(2, pageLogin.bootstrapCalls().size)
    }

    @Test
    fun `personalized content needs saved auth and an effective login`() = runTest {
        val guest = libraryHttp(loggedInBootstrap = { false })
        assertFalse(YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), guest.client).hasPersonalizedContent())
        assertTrue(guest.calls.isEmpty())

        val preferenceOnly = libraryHttp(loggedInBootstrap = { false })
        assertFalse(YouTubeMusicClient(FakeYouTubeAuth(PREFERENCE_ONLY_AUTH), preferenceOnly.client).hasPersonalizedContent())
        assertEquals(2, preferenceOnly.bootstrapCalls().size)
        assertTrue(preferenceOnly.innertubeCalls("browse").isEmpty())
    }

    @Test
    fun `personalized content checks the library before the home feed`() = runTest {
        val library = libraryHttp(loggedInBootstrap = { true })
        assertTrue(YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), library.client).hasPersonalizedContent())
        assertEquals(listOf("FEmusic_liked_playlists"), library.browseIds())

        val feed = libraryHttp(loggedInBootstrap = { false }, library = libraryPage(), home = homePage("vid-a" to "Song A"))
        assertTrue(YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), feed.client).hasPersonalizedContent())
        assertEquals(listOf("FEmusic_liked_playlists", "FEmusic_home"), feed.browseIds())

        val empty = libraryHttp(loggedInBootstrap = { true }, library = libraryPage(), home = homePage())
        assertFalse(YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), empty.client).hasPersonalizedContent())
        assertEquals(listOf("FEmusic_liked_playlists", "FEmusic_home"), empty.browseIds())
    }

    @Test
    fun `innertube authorization binds the bootstrap user session only when one is exposed`() = runTest {
        var userSessionId = "1234567890"
        val http = YouTubeMusicTestHttp { call ->
            if (call.isBootstrap) {
                bootstrapPage(loggedIn = true, userSessionId = userSessionId)
            } else {
                json(creatorPage("TOP SONGS", "top-song" to "Top Song"))
            }
        }
        val client = YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), http.client)

        client.getCreatorDetail(creator)
        userSessionId = ""
        client.clearBootstrapCache()
        client.getCreatorDetail(creator)

        val browses = http.innertubeCalls("browse")
        val bound = browses[0].request.header("Authorization")!!.removePrefix("SAPISIDHASH ").split('_')
        val unbound = browses[1].request.header("Authorization")!!.removePrefix("SAPISIDHASH ").split('_')
        assertEquals(sha1Hex("1234567890 ${bound[0]} test-sapisid https://music.youtube.com"), bound[1])
        assertEquals("u", bound[2])
        assertEquals(listOf(sha1Hex("${unbound[0]} test-sapisid https://music.youtube.com")), unbound.drop(1))
        browses.forEach { browse ->
            assertEquals("1", browse.request.header("X-Goog-AuthUser"))
            assertNull(browse.request.header("X-Goog-Visitor-Id"))
        }
    }

    private fun libraryHttp(
        loggedInBootstrap: () -> Boolean,
        library: String = libraryPage("VLPLmix" to "Daily Mix"),
        home: String = homePage()
    ) = YouTubeMusicTestHttp { call ->
        when {
            call.isBootstrap -> bootstrapPage(loggedIn = loggedInBootstrap())
            call.payload?.optString("browseId") == "FEmusic_liked_playlists" -> json(library)
            call.payload?.optString("browseId") == "FEmusic_home" -> json(home)
            else -> error("Unexpected request: ${call.request.url}")
        }
    }

    private fun YouTubeMusicTestHttp.browseIds(): List<String> =
        innertubeCalls("browse").map { it.payload!!.getString("browseId") }
}
