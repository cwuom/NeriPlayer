package moe.ouom.neriplayer.api.sync.github

import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRateLimitPolicyTest {
    private val now = 1_000_000L

    @Test
    fun `authorization failures are not mistaken for rate limits`() {
        assertNull(parse(403, Headers.headersOf(), "Resource not accessible by integration"))
        assertNull(parse(401, Headers.headersOf("Retry-After", "60"), "rate limit"))
        assertNull(parse(500, Headers.headersOf(), "rate limit"))
    }

    @Test
    fun `primary reset and secondary retry after preserve authoritative cooldown`() {
        val primary = parse(403, Headers.headersOf("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "2500"))!!
        assertEquals(2_500_000L, primary.retryAtMillis)
        val secondary = parse(403, Headers.headersOf("Retry-After", "120"))!!
        assertEquals(now + 120_000L, secondary.retryAtMillis)
        val combined = parse(429, Headers.headersOf("Retry-After", "120", "X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "2500"))!!
        assertEquals(2_500_000L, combined.retryAtMillis)
        assertFalse(combined.automaticRetryAllowed)
        assertTrue(combined.message!!.contains("请稍后重试"))
    }

    @Test
    fun `secondary body and abuse response use at least one minute without headers`() {
        for (body in listOf("secondary rate limit", "abuse detection")) {
            assertEquals(now + 60_000L, parse(403, Headers.headersOf(), body)!!.retryAtMillis)
        }
        assertEquals(now + 60_000L, parse(429)!!.retryAtMillis)
    }

    @Test
    fun `HTTP date retry after requires complete valid parsing`() {
        assertEquals(1_800_000L, parse(429, Headers.headersOf("Retry-After", "Thu, 01 Jan 1970 00:30:00 GMT"))!!.retryAtMillis)
        for (value in listOf("Thu, 01 Jan 1970 00:30:00 GMT junk", "Thu, 99 Jan 1970 00:30:00 GMT", "not a date")) {
            assertEquals(now + 60_000L, parse(429, Headers.headersOf("Retry-After", value))!!.retryAtMillis)
        }
    }

    @Test
    fun `negative stale and overflowing header numbers cannot cause immediate retries`() {
        for (value in listOf("-1", "0", Long.MAX_VALUE.toString(), "9223372036854775808")) {
            val error = parse(429, Headers.headersOf("Retry-After", value, "X-RateLimit-Remaining", "0", "X-RateLimit-Reset", value))!!
            assertEquals(now + 60_000L, error.retryAtMillis)
        }
        assertEquals(Long.MAX_VALUE, GitHubRateLimitPolicy.addDelay(Long.MAX_VALUE - 500L, 1_000L))
        assertEquals(60_000L, GitHubRateLimitPolicy.addDelay(Long.MIN_VALUE, 60_000L))
        assertEquals(now, GitHubRateLimitPolicy.addDelay(now, Long.MIN_VALUE))
        val edge = response(429, Headers.headersOf("Retry-After", "1")).use {
            GitHubRateLimitPolicy.fromResponse(it, "", Long.MAX_VALUE - 500L)!!
        }
        assertEquals(Long.MAX_VALUE, edge.retryAtMillis)
        assertTrue(edge.message!!.contains("秒"))
    }

    private fun parse(code: Int, headers: Headers = Headers.headersOf(), body: String = ""): GitHubRateLimitException? =
        response(code, headers).use { GitHubRateLimitPolicy.fromResponse(it, body, now) }

    private fun response(code: Int, headers: Headers): Response = Response.Builder()
        .request(Request.Builder().url("https://sync.test/repos/owner/repo").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("test").headers(headers).body("".toResponseBody()).build()
}
