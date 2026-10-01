package moe.ouom.neriplayer.api.ltw.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ListenTogetherBaseUrlTest {
    @Test
    fun `normalizes scheme whitespace ports and dot segments without decoding paths`() {
        val examples = mapOf(
            " HTTPS://example.com/ " to "https://example.com",
            "http://127.0.0.1:8787" to "http://127.0.0.1:8787",
            "https://example.com/a/../worker///" to "https://example.com/worker",
            "https://example.com/a%20b/" to "https://example.com/a%20b",
            "https://[::1]:8787/" to "https://[::1]:8787",
            "https://example.com/./" to "https://example.com",
            "https://example.com/a/../../b/" to "https://example.com/../b",
            "https://example.com/%2Fworker/" to "https://example.com/%2Fworker"
        )
        examples.forEach { (input, expected) ->
            assertEquals(input, expected, input.normalizedHttpBaseUrlOrNull())
            assertEquals(input, expected, input.normalizeBaseUrl())
        }
    }

    @Test
    fun `rejects missing authority unsupported schemes malformed URLs queries and fragments`() {
        listOf("", " ", "example.com", "/worker", "ftp://example.com", "http:/worker",
            "https://", "https://example.com/%", "https://example.com/?token=x",
            "https://example.com/#fragment").forEach { input ->
            assertNull(input, input.normalizedHttpBaseUrlOrNull())
            assertThrows(input, IllegalArgumentException::class.java) { input.normalizeBaseUrl() }
        }
    }
}
