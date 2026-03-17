package moe.ouom.neriplayer.data

import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicSupportTest {

    @Test
    fun resolveAuthorizationHeader_shouldPreferSecure3papisid() {
        val bundle = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "__Secure-3PAPISID" to "secure-cookie",
                "SAPISID" to "plain-cookie"
            )
        )

        val actual = bundle.resolveAuthorizationHeader(
            origin = "https://music.youtube.com",
            nowEpochSeconds = 1_700_000_000L
        )

        val expected = buildExpectedAuthorization(
            timestamp = 1_700_000_000L,
            sapisid = "secure-cookie",
            origin = "https://music.youtube.com"
        )
        assertEquals(expected, actual)
    }

    @Test
    fun effectiveCookieHeader_shouldAppendSocsWhenMissing() {
        val bundle = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SAPISID" to "abc",
                "__Secure-3PAPISID" to "def"
            )
        )

        val cookieHeader = bundle.effectiveCookieHeader()

        assertTrue(cookieHeader.contains("SAPISID=abc"))
        assertTrue(cookieHeader.contains("__Secure-3PAPISID=def"))
        assertTrue(cookieHeader.contains("SOCS=CAI"))
    }

    @Test
    fun stableYouTubeMusicId_shouldBeStableAndNonZero() {
        val first = stableYouTubeMusicId("dQw4w9WgXcQ")
        val second = stableYouTubeMusicId("dQw4w9WgXcQ")
        val different = stableYouTubeMusicId("7wtfhZwyrcc")

        assertEquals(first, second)
        assertNotEquals(0L, first)
        assertNotEquals(first, different)
    }

    @Test
    fun buildAndExtractMediaUri_shouldRoundTripVideoId() {
        val mediaUri = buildYouTubeMusicMediaUri(
            videoId = "dQw4w9WgXcQ",
            playlistId = "LM"
        )

        assertEquals("dQw4w9WgXcQ", extractYouTubeMusicVideoId(mediaUri))
    }

    @Test
    fun appendYouTubeConsentCookie_shouldAppendOnlyOnce() {
        val base = "SAPISID=abc"
        val appended = appendYouTubeConsentCookie(base)
        val appendedAgain = appendYouTubeConsentCookie(appended)

        assertEquals("SAPISID=abc; SOCS=CAI", appended)
        assertEquals(appended, appendedAgain)
    }

    private fun buildExpectedAuthorization(
        timestamp: Long,
        sapisid: String,
        origin: String
    ): String {
        val input = "$timestamp $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
        return "SAPISIDHASH ${timestamp}_$digest"
    }
}
