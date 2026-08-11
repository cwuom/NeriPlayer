package moe.ouom.neriplayer.core.api.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun requestCookies_includePersonalizationContextBeforeFirstResponse() {
        val client = NeteaseClient()
        client.setPersistedCookies(mapOf("MUSIC_U" to "login-session"))

        val cookies = client.getNeteaseRequestCookies()

        assertEquals("login-session", cookies["MUSIC_U"])
        assertEquals("true", cookies["__remember_me"])
        assertTrueHexSessionCookie(cookies["_ntes_nuid"])
        assertTrueHexSessionCookie(cookies["NMTID"])
    }

    private fun assertTrueHexSessionCookie(value: String?) {
        assertEquals(32, value?.length)
        assertTrue(value?.all { it in '0'..'9' || it in 'a'..'f' } == true)
    }
}
