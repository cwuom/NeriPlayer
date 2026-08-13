package moe.ouom.neriplayer.core.api.netease

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseClientTest {

    @Test
    fun mergeRequestCookies_keepsRuntimeSessionCookies() {
        val cookies = mergeNeteaseRequestCookies(
            persistedCookies = linkedMapOf(
                "MUSIC_U" to "persisted-session",
                "__csrf" to "persisted-csrf",
                "NMTID" to "persisted-context"
            ),
            runtimeCookies = linkedMapOf(
                "MUSIC_U" to "runtime-session",
                "NMTID" to "runtime-context"
            ),
            requestContextCookies = linkedMapOf(
                "__remember_me" to "true",
                "NMTID" to "generated-context"
            )
        )

        assertEquals("runtime-session", cookies["MUSIC_U"])
        assertEquals("persisted-csrf", cookies["__csrf"])
        assertEquals("runtime-context", cookies["NMTID"])
        assertEquals("true", cookies["__remember_me"])
        assertEquals("pc", cookies["os"])
        assertEquals("8.10.35", cookies["appver"])
    }

    @Test
    fun setPersistedCookies_dropsThePreviousLoginSession() {
        val client = NeteaseClient()

        client.setPersistedCookies(mapOf("MUSIC_U" to "previous-session"))
        client.setPersistedCookies(emptyMap())

        assertFalse(client.getCookies().containsKey("MUSIC_U"))
    }

    @Test
    fun sessionStore_keepsLateCookiesInTheOriginalAccountSession() {
        val store = NeteaseRequestSessionStore()
        val accountA = store.setPersistedCookies(mapOf("MUSIC_U" to "account-a"))
        val accountB = store.setPersistedCookies(mapOf("MUSIC_U" to "account-b"))
        val requestUrl = "https://music.163.com/".toHttpUrl()

        accountA.cookieJar.saveFromResponse(
            requestUrl,
            listOf(newCookie("__csrf", "late-account-a"))
        )

        assertNotSame(accountA, accountB)
        assertEquals("late-account-a", accountA.requestCookiesForUrl(requestUrl)["__csrf"])
        assertFalse(accountB.requestCookiesForUrl(requestUrl).containsKey("__csrf"))
        assertFalse(store.currentSession().requestCookiesForUrl(requestUrl).containsKey("__csrf"))
    }

    @Test
    fun sessionStore_preservesSessionWhenOnlyRuntimeCookiesChange() {
        val store = NeteaseRequestSessionStore()
        val original = store.setPersistedCookies(
            mapOf("MUSIC_U" to "account-a", "__csrf" to "old-session")
        )
        val replacement = store.setPersistedCookies(
            mapOf("MUSIC_U" to "account-a", "__csrf" to "new-session")
        )

        assertEquals(original, replacement)
        assertEquals("new-session", replacement.requestCookiesForUrl(
            "https://music.163.com/".toHttpUrl()
        )["__csrf"])
    }

    @Test
    fun sessionStore_doesNotRestoreAnOlderAccountSnapshotAfterSwitching() {
        val store = NeteaseRequestSessionStore()
        val accountA = mapOf("MUSIC_U" to "account-a", "__csrf" to "a-session")
        val accountB = mapOf("MUSIC_U" to "account-b", "__csrf" to "b-session")

        val firstAccountSession = store.setPersistedCookies(accountA)
        val secondAccountSession = store.setPersistedCookies(accountB)
        val restoredAccountSession = store.setPersistedCookies(accountA)

        assertNotSame(firstAccountSession, secondAccountSession)
        assertNotSame(secondAccountSession, restoredAccountSession)
        assertEquals("account-a", restoredAccountSession.requestCookiesForUrl(
            "https://music.163.com/".toHttpUrl()
        )["MUSIC_U"])
    }

    @Test
    fun requestCookies_includePersonalizationContextBeforeFirstResponse() {
        val client = NeteaseClient()
        client.setPersistedCookies(mapOf("MUSIC_U" to "login-session"))

        val cookies = client.getNeteaseRequestCookies()

        assertEquals("login-session", cookies["MUSIC_U"])
        assertEquals("true", cookies["__remember_me"])
        assertTrueHexSessionCookie(cookies["_ntes_nuid"])
        assertTrueHexSessionCookie(cookies["NMTID"])
    }

    @Test
    fun musicUOnlyLogin_requiresWeapiSessionPreheat() {
        assertTrue(
            shouldPreheatNeteaseWeapiSession(
                persistedCookies = mapOf("MUSIC_U" to "login-session"),
                requestCookies = emptyMap(),
                usePersistedCookies = true
            )
        )
        assertFalse(
            shouldPreheatNeteaseWeapiSession(
                persistedCookies = mapOf("MUSIC_U" to "login-session"),
                requestCookies = mapOf("__csrf" to "csrf-token"),
                usePersistedCookies = true
            )
        )
        assertFalse(
            shouldPreheatNeteaseWeapiSession(
                persistedCookies = mapOf("MUSIC_U" to "login-session"),
                requestCookies = emptyMap(),
                usePersistedCookies = false
            )
        )
    }

    @Test
    fun radarPlaylistMetadataParams_includeMgcContextAndMetadataLimit() {
        val params = buildNeteaseRadarPlaylistMetadataParams(5_327_906_368L)

        assertEquals("5327906368", params["id"])
        assertEquals("1", params["n"])
        assertEquals("0", params["s"])
        assertEquals("MGC", params["uiPlaylistType"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun radarPlaylistMetadataParams_rejectNonPositivePlaylistId() {
        buildNeteaseRadarPlaylistMetadataParams(0L)
    }

    private fun assertTrueHexSessionCookie(value: String?) {
        assertEquals(32, value?.length)
        assertTrue(value?.all { it in '0'..'9' || it in 'a'..'f' } == true)
    }

    private fun newCookie(name: String, value: String): Cookie {
        return Cookie.Builder()
            .name(name)
            .value(value)
            .domain("music.163.com")
            .path("/")
            .build()
    }
}
