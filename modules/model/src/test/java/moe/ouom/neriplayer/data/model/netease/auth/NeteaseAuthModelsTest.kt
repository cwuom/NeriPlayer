package moe.ouom.neriplayer.data.model.netease.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseAuthModelsTest {

    @Test
    fun `netease login needs a non blank MUSIC_U cookie`() {
        assertTrue(NeteaseAuthBundle(mapOf("MUSIC_U" to "token")).hasLoginCookies())
        assertFalse(NeteaseAuthBundle(mapOf("MUSIC_U" to "")).hasLoginCookies())
        assertFalse(NeteaseAuthBundle(mapOf("__csrf" to "csrf")).hasLoginCookies())
        assertEquals(
            mapOf("MUSIC_U" to "token"),
            NeteaseAuthBundle(mapOf("" to "x", "MUSIC_U" to "token")).normalized().cookies
        )
    }

    @Test
    fun `cookie validation is accepted only with a login cookie`() {
        val accepted = NeteaseCookieValidationResult(sanitizedCookies = mapOf("MUSIC_U" to "token", "__csrf" to "csrf"))
        val withoutLogin = NeteaseCookieValidationResult(
            sanitizedCookies = mapOf("__csrf" to "csrf"),
            rejectedKeys = listOf("MUSIC_U")
        )

        assertTrue(accepted.hasLoginCookie)
        assertTrue(accepted.isAccepted)
        assertFalse(withoutLogin.hasLoginCookie)
        assertFalse(withoutLogin.isAccepted)
        assertFalse(NeteaseCookieValidationResult(sanitizedCookies = mapOf("MUSIC_U" to " ")).isAccepted)
        assertFalse(NeteaseCookieValidationResult().isAccepted)
    }
}
