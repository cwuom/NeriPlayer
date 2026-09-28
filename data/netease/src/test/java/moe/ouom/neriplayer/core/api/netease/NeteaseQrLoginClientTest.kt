package moe.ouom.neriplayer.core.api.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NeteaseQrLoginClientTest {

    @Test
    fun `buildNeteaseQrAccountParams follows web login account request`() {
        val params = buildNeteaseQrAccountParams("csrf-token")

        assertEquals(true, params["noCheckToken"])
        assertEquals("csrf-token", params["csrf_token"])
    }

    @Test
    fun `buildNeteaseQrAccountParams keeps noCheckToken without csrf`() {
        val params = buildNeteaseQrAccountParams("")

        assertEquals(true, params["noCheckToken"])
        assertFalse(params.containsKey("csrf_token"))
    }

    @Test
    fun `mergeNeteaseQrCredentialCookies uses refresh token as login credential`() {
        val cookies = mergeNeteaseQrCredentialCookies(
            cookies = mapOf("__csrf" to "csrf-token"),
            refreshToken = "refresh-token"
        )

        assertEquals("refresh-token", cookies["MUSIC_U"])
        assertEquals("csrf-token", cookies["__csrf"])
    }

    @Test
    fun `mergeNeteaseQrCredentialCookies preserves existing music cookie`() {
        val cookies = mergeNeteaseQrCredentialCookies(
            cookies = mapOf("MUSIC_U" to "cookie-token"),
            refreshToken = "refresh-token"
        )

        assertEquals("cookie-token", cookies["MUSIC_U"])
    }
}
