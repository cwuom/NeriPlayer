package moe.ouom.neriplayer.platform.youtube.api.auth

import moe.ouom.neriplayer.data.model.youtube.auth.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthBundleJsonTest {

    @Test
    fun `json round trip keeps the persisted session fields`() {
        val bundle = YouTubeAuthBundle(
            cookies = linkedMapOf("SAPISID" to "sap", "ST-page" to "1"),
            authorization = "SAPISIDHASH 1_abc",
            xGoogAuthUser = "1",
            origin = "https://www.youtube.com",
            userAgent = "UnitTestAgent/1.0",
            savedAt = 42L
        ).normalized()

        val restored = YouTubeAuthBundle.fromJson(bundle.toJson())

        assertEquals(bundle, restored)
        assertEquals("SAPISID=sap", restored.cookieHeader)
    }

    @Test
    fun `json without a cookie object falls back to the header and default origin`() {
        val restored = YouTubeAuthBundle.fromJson(
            """{"cookieHeader":"SID=sid; ST-page=1","origin":"","savedAt":7}"""
        )

        assertEquals(
            YouTubeAuthBundle(
                cookieHeader = "SID=sid",
                cookies = linkedMapOf("SID" to "sid"),
                origin = YOUTUBE_MUSIC_ORIGIN,
                savedAt = 7L
            ),
            restored
        )
    }

    @Test
    fun `unreadable json restores an empty bundle`() {
        assertEquals(YouTubeAuthBundle(), YouTubeAuthBundle.fromJson("{not json"))
    }

    @Test
    fun `saved auth material ignores cookies that would not be persisted`() {
        assertFalse(YouTubeAuthBundle().hasSavedAuthMaterial())
        assertFalse(YouTubeAuthBundle(cookieHeader = "ST-page=1; YT_TEMP=2").hasSavedAuthMaterial())
        assertTrue(YouTubeAuthBundle(cookieHeader = "PREF=f6=8").hasSavedAuthMaterial())
        assertTrue(YouTubeAuthBundle(authorization = "SAPISIDHASH 1_abc").hasSavedAuthMaterial())
    }

    @Test
    fun `cookie header parsing skips empty, unnamed and valueless segments`() {
        assertEquals(
            linkedMapOf("SID" to "a=b", "HSID" to ""),
            parseCookieHeader(";  SID = a=b ; =orphan; flag; HSID= ;")
        )
    }
}
