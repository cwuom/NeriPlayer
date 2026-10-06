package moe.ouom.neriplayer.platform.netease.api.client

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseRequestSessionTest {
    private val mainUrl = "https://music.163.com/".toHttpUrl()
    private val apiUrl = "https://music.163.com/api/song".toHttpUrl()
    private val interfaceUrl = "https://interface.music.163.com/eapi/song".toHttpUrl()

    @Test
    fun `session cookie merge only adopts non-blank runtime NMTID and csrf`() {
        val merged = mergeNeteaseSessionCookies(
            persistedCookies = mapOf("MUSIC_U" to "persisted", "NMTID" to "old-nmtid", "__csrf" to "old-csrf"),
            runtimeCookies = mapOf("NMTID" to "fresh-nmtid", "__csrf" to "", "MUSIC_U" to "runtime")
        )

        assertEquals(mapOf("MUSIC_U" to "persisted", "NMTID" to "fresh-nmtid", "__csrf" to "old-csrf"), merged)
        assertEquals(
            mapOf("MUSIC_U" to "persisted"),
            mergeNeteaseSessionCookies(mapOf("MUSIC_U" to "persisted"), emptyMap())
        )
    }

    @Test
    fun `weapi preheat is needed only for persisted logins without csrf`() {
        val login = mapOf("MUSIC_U" to "session")

        assertTrue(shouldPreheatNeteaseWeapiSession(login, emptyMap(), usePersistedCookies = true))
        assertTrue(shouldPreheatNeteaseWeapiSession(login, mapOf("__csrf" to " "), usePersistedCookies = true))
        assertFalse(shouldPreheatNeteaseWeapiSession(login, mapOf("__csrf" to "token"), usePersistedCookies = true))
        assertFalse(shouldPreheatNeteaseWeapiSession(login, emptyMap(), usePersistedCookies = false))
        assertFalse(shouldPreheatNeteaseWeapiSession(emptyMap(), emptyMap(), usePersistedCookies = true))
        assertFalse(shouldPreheatNeteaseWeapiSession(mapOf("MUSIC_U" to " "), emptyMap(), usePersistedCookies = true))
    }

    @Test
    fun `preheat marker requires the current login fingerprint and a csrf cookie`() {
        val session = NeteaseRequestSession(mapOf("MUSIC_U" to "account-a"))
        val fingerprint = session.authCookieFingerprint()

        session.markPersonalizedSessionPreheated(fingerprint, mainUrl)
        assertFalse(session.hasPreheatedPersonalizedSession(fingerprint, mainUrl))
        assertTrue(session.needsWeapiSessionPreheat(mainUrl, usePersistedCookies = true))

        session.cookieJar.saveFromResponse(mainUrl, listOf(cookie("__csrf", "csrf-a", "music.163.com")))
        session.markPersonalizedSessionPreheated("stale-fingerprint", mainUrl)
        assertFalse(session.hasPreheatedPersonalizedSession("stale-fingerprint", mainUrl))

        session.markPersonalizedSessionPreheated(fingerprint, mainUrl)
        assertTrue(session.hasPreheatedPersonalizedSession(fingerprint, mainUrl))
        assertFalse(session.hasPreheatedPersonalizedSession("other-account", mainUrl))
        assertFalse(session.needsWeapiSessionPreheat(mainUrl, usePersistedCookies = true))

        session.replacePersistedCookies(mapOf("MUSIC_U" to "account-a"))
        assertFalse(session.hasPreheatedPersonalizedSession(fingerprint, mainUrl))
    }

    @Test
    fun `expired csrf response cookie revokes an earlier preheat`() {
        val session = NeteaseRequestSession(mapOf("MUSIC_U" to "account-a"))
        val fingerprint = session.authCookieFingerprint()
        session.cookieJar.saveFromResponse(mainUrl, listOf(cookie("__csrf", "csrf-a", "music.163.com")))
        session.markPersonalizedSessionPreheated(fingerprint, mainUrl)

        session.cookieJar.saveFromResponse(
            mainUrl,
            listOf(cookie("__csrf", "csrf-a", "music.163.com", expiresAt = System.currentTimeMillis() - 60_000L))
        )

        assertFalse(session.hasPreheatedPersonalizedSession(fingerprint, mainUrl))
        assertNull(session.requestCookiesForUrl(mainUrl)["__csrf"])
    }

    @Test
    fun `response cookies replace by name domain and path while blank values delete`() {
        val session = NeteaseRequestSession(emptyMap())

        session.cookieJar.saveFromResponse(mainUrl, listOf(cookie("__csrf", "one", "music.163.com")))
        session.cookieJar.saveFromResponse(mainUrl, listOf(cookie("__csrf", "two", "music.163.com")))
        assertEquals("two", session.requestCookiesForUrl(mainUrl)["__csrf"])

        session.cookieJar.saveFromResponse(mainUrl, listOf(cookie("__csrf", "pathed", "music.163.com", path = "/api")))
        session.cookieJar.saveFromResponse(mainUrl, listOf(cookie("__csrf", "iface", "interface.music.163.com")))
        assertEquals("two", session.requestCookiesForUrl(mainUrl)["__csrf"])
        assertEquals("pathed", session.requestCookiesForUrl(apiUrl)["__csrf"])
        assertEquals("iface", session.requestCookiesForUrl(interfaceUrl)["__csrf"])

        session.cookieJar.saveFromResponse(mainUrl, listOf(cookie("__csrf", "", "music.163.com")))
        assertNull(session.requestCookiesForUrl(mainUrl)["__csrf"])
        assertEquals("pathed", session.requestCookiesForUrl(apiUrl)["__csrf"])
        assertEquals("iface", session.requestCookiesForUrl(interfaceUrl)["__csrf"])
    }

    private fun cookie(
        name: String,
        value: String,
        domain: String,
        path: String = "/",
        expiresAt: Long = System.currentTimeMillis() + 3_600_000L
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path(path)
        .expiresAt(expiresAt)
        .build()
}
