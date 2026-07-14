package moe.ouom.neriplayer.data.auth.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeAuthImportParserTest {

    @Test
    fun parseYouTubeAuthBundleFromRaw_parsesFullBrowserHeaders() {
        val bundle = parseYouTubeAuthBundleFromRaw(
            raw = """
                Cookie: SID=sid-value; SAPISID=sap-value
                Authorization: SAPISIDHASH signature
                X-Goog-AuthUser: 2
                X-Origin: https://music.youtube.com
                User-Agent: Browser/1.0
            """.trimIndent(),
            savedAt = 123L
        )

        assertNotNull(bundle)
        assertEquals("sid-value", bundle?.cookies?.get("SID"))
        assertEquals("sap-value", bundle?.cookies?.get("SAPISID"))
        assertEquals("SAPISIDHASH signature", bundle?.authorization)
        assertEquals("2", bundle?.xGoogAuthUser)
        assertEquals("https://music.youtube.com", bundle?.origin)
        assertEquals("Browser/1.0", bundle?.userAgent)
        assertEquals(123L, bundle?.savedAt)
    }

    @Test
    fun parseYouTubeAuthBundleFromRaw_keepsPlainCookieImportCompatible() {
        val bundle = parseYouTubeAuthBundleFromRaw(
            raw = "SID=sid-value; SAPISID=sap-value",
            savedAt = 456L
        )

        assertNotNull(bundle)
        assertEquals("sid-value", bundle?.cookies?.get("SID"))
        assertEquals("sap-value", bundle?.cookies?.get("SAPISID"))
        assertEquals(456L, bundle?.savedAt)
    }

    @Test
    fun parseYouTubeAuthBundleFromRaw_rejectsHeadersWithoutCookie() {
        val bundle = parseYouTubeAuthBundleFromRaw(
            raw = """
                Authorization: SAPISIDHASH signature
                X-Goog-AuthUser: 2
            """.trimIndent()
        )

        assertNull(bundle)
    }
}
