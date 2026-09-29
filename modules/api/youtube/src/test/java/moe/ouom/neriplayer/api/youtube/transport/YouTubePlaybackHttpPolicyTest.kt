package moe.ouom.neriplayer.api.youtube.transport

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaybackHttpPolicyTest {
    @Test
    fun rateLimitBackoffMs_returnsNullForNonRetryableErrors() {
        // #Y5: 仅 429/503 且携带状态码的异常才退避
        assertNull(rateLimitBackoffMs(IOException("boom"), 0))
        assertNull(rateLimitBackoffMs(null, 0))
        assertNull(rateLimitBackoffMs(YouTubeHttpStatusException(403, null, "x"), 0))
        assertNull(rateLimitBackoffMs(YouTubeHttpStatusException(500, null, "x"), 0))
    }

    @Test
    fun rateLimitBackoffMs_usesExponentialBackoffWhenNoRetryAfter() {
        assertEquals(500L, rateLimitBackoffMs(YouTubeHttpStatusException(429, null, "x"), 0))
        assertEquals(1000L, rateLimitBackoffMs(YouTubeHttpStatusException(429, null, "x"), 1))
        assertEquals(2000L, rateLimitBackoffMs(YouTubeHttpStatusException(503, null, "x"), 2))
        assertEquals(4000L, rateLimitBackoffMs(YouTubeHttpStatusException(503, null, "x"), 3))
        // priorHits 超过上限仍封顶在指数最高档 (4000)
        assertEquals(4000L, rateLimitBackoffMs(YouTubeHttpStatusException(429, null, "x"), 9))
    }

    @Test
    fun rateLimitBackoffMs_respectsRetryAfterWithinCap() {
        assertEquals(3000L, rateLimitBackoffMs(YouTubeHttpStatusException(429, 3000L, "x"), 2))
        // Retry-After 超过上限 -> 封顶 5000
        assertEquals(5000L, rateLimitBackoffMs(YouTubeHttpStatusException(503, 60_000L, "x"), 0))
    }

    @Test
    fun parseRetryAfterMs_parsesIntegerSecondsOnly() {
        assertEquals(2000L, parseRetryAfterMs("2"))
        assertEquals(0L, parseRetryAfterMs("0"))
        assertNull(parseRetryAfterMs(null))
        assertNull(parseRetryAfterMs(""))
        assertNull(parseRetryAfterMs("   "))
        assertNull(parseRetryAfterMs("-5"))
        // HTTP-date 形式不支持, 交给指数退避
        assertNull(parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
    }
}
