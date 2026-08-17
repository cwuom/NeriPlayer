package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.model.PlaybackUrlCandidate
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherStreamCacheKeyTest {

    @Test
    fun `shared stream cache key isolates track and direct URL without retaining the URL`() {
        val firstUrl = "https://m701.music.126.net/audio.mp3?token=one"
        val secondUrl = "https://m702.music.126.net/audio.mp3?token=two"
        val firstKey = listenTogetherStreamCacheKey("netease:1", firstUrl)
        val differentUrlKey = listenTogetherStreamCacheKey("netease:1", secondUrl)
        val differentTrackKey = listenTogetherStreamCacheKey("netease:2", firstUrl)

        assertTrue(firstKey.startsWith("$LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX-"))
        assertFalse(firstKey.contains(firstUrl))
        assertNotEquals(firstKey, differentUrlKey)
        assertNotEquals(firstKey, differentTrackKey)
    }

    @Test
    fun `local resolution keeps priority and appends isolated room candidates`() {
        val localPrimary = "https://rr1.googlevideo.com/local-primary.m4a"
        val localBackup = "https://rr2.googlevideo.com/local-backup.m4a"
        val sharedPrimary = "https://rr3.googlevideo.com/shared-primary.m4a"
        val sharedBackup = "https://rr4.googlevideo.com/shared-backup.m4a"
        val localResult = SongUrlResult.Success(
            url = localPrimary,
            candidateUrls = listOf(localBackup)
        )
        val sharedResult = SongUrlResult.Success(
            url = sharedPrimary,
            cacheKeyOverride = listenTogetherStreamCacheKey("youtube:video", sharedPrimary),
            fallbackCandidates = listOf(
                PlaybackUrlCandidate(
                    url = localBackup,
                    cacheKeyOverride = listenTogetherStreamCacheKey("youtube:video", localBackup)
                ),
                PlaybackUrlCandidate(
                    url = sharedBackup,
                    cacheKeyOverride = listenTogetherStreamCacheKey("youtube:video", sharedBackup)
                )
            )
        )

        val merged = mergeListenTogetherFallbackResult(localResult, sharedResult)
            as SongUrlResult.Success
        val candidates = merged.playbackCandidates()

        assertEquals(
            listOf(localPrimary, localBackup, sharedPrimary, sharedBackup),
            candidates.map { it.url }
        )
        assertEquals(listOf(null, null), candidates.take(2).map { it.cacheKeyOverride })
        assertTrue(
            candidates.drop(2).all { candidate ->
                candidate.cacheKeyOverride
                    ?.startsWith("$LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX-") == true
            }
        )
    }

    @Test
    fun `session fallback disables the direct stream shortcut`() {
        assertFalse(
            shouldUseDirectStreamShortcut(
                forceRefresh = false,
                hasListenTogetherFallback = true
            )
        )
        assertTrue(
            shouldUseDirectStreamShortcut(
                forceRefresh = false,
                hasListenTogetherFallback = false
            )
        )
        assertFalse(
            shouldUseDirectStreamShortcut(
                forceRefresh = true,
                hasListenTogetherFallback = false
            )
        )
    }
}
