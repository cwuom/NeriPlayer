package moe.ouom.neriplayer.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherBaseUrlTest {

    @Test
    fun `configured base url keeps valid custom server`() {
        assertEquals(
            "https://example.com",
            configuredListenTogetherBaseUrlOrNull(" https://example.com/ ")
        )
    }

    @Test
    fun `configured base url rejects invalid custom server`() {
        assertNull(configuredListenTogetherBaseUrlOrNull("example.com"))
    }

    @Test
    fun `resolve base url falls back to default for blank input`() {
        assertEquals(
            "https://neriplayer.hancat.work",
            resolveListenTogetherBaseUrl(" ")
        )
    }

    @Test
    fun `invite parser normalizes valid custom server`() {
        val invite = parseListenTogetherInvite(
            "neriplayer://listen-together/join?roomId=P8BAEV&baseUrl=https%3A%2F%2Fexample.com%2F"
        )

        assertEquals("https://example.com", invite?.baseUrl)
        assertFalse(invite?.hasInvalidBaseUrl ?: true)
    }

    @Test
    fun `invite parser flags invalid custom server`() {
        val invite = parseListenTogetherInvite(
            "neriplayer://listen-together/join?roomId=P8BAEV&baseUrl=example.com"
        )

        assertNull(invite?.baseUrl)
        assertTrue(invite?.hasInvalidBaseUrl == true)
    }
}
