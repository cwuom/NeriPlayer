package moe.ouom.neriplayer.platform.youtube.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubePlayableAudioExpiryTest {
    @Test
    fun resolvePlayableAudioCacheExpiresAtMs_respectsYouTubeExpireWithSafetyMargin() {
        val cachedAtMs = 1_700_000_000_000L
        val url = "https://rr1---sn.googlevideo.com/videoplayback?expire=1700000300&id=audio"

        val expiresAtMs = resolvePlayableAudioCacheExpiresAtMs(
            url = url,
            cachedAtMs = cachedAtMs,
            defaultTtlMs = 8L * 60L * 1000L,
            safetyMarginMs = 90L * 1000L
        )

        assertEquals(1_700_000_210_000L, expiresAtMs)
    }

    @Test
    fun resolvePlayableAudioCacheExpiresAtMs_fallsBackToDefaultTtlWithoutExpire() {
        val cachedAtMs = 1_700_000_000_000L

        val expiresAtMs = resolvePlayableAudioCacheExpiresAtMs(
            url = "https://example.com/audio.m4a",
            cachedAtMs = cachedAtMs,
            defaultTtlMs = 8L * 60L * 1000L,
            safetyMarginMs = 90L * 1000L
        )

        assertEquals(1_700_000_480_000L, expiresAtMs)
    }

    @Test
    fun resolvePlayableAudioCacheExpiresAtMs_expiresImmediatelyWhenStreamUrlIsNearExpiry() {
        val cachedAtMs = 1_700_000_000_000L
        val url = "https://rr1---sn.googlevideo.com/videoplayback?expire=1700000030&id=audio"

        val expiresAtMs = resolvePlayableAudioCacheExpiresAtMs(
            url = url,
            cachedAtMs = cachedAtMs,
            defaultTtlMs = 8L * 60L * 1000L,
            safetyMarginMs = 90L * 1000L
        )

        assertEquals(cachedAtMs, expiresAtMs)
    }
}
