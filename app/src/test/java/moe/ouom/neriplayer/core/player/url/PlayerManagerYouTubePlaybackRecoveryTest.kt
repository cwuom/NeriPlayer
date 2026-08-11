@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerYouTubePlaybackRecoveryTest {

    @Test
    fun `youtube playback keeps opus primary cache key separate from m4a recovery`() {
        assertEquals(
            "ytmusic-fbvvS8e1KgI-very_high",
            PlayerManager.computeYouTubeCacheKey(
                videoId = "fbvvS8e1KgI",
                preferredQuality = "very_high",
                preferM4a = false
            )
        )
    }

    @Test
    fun `remote decoder failure uses high quality m4a recovery`() {
        val strategy = resolveYouTubePlaybackRecoveryStrategy(
            error = playbackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
            isOfflineCache = false
        )

        assertEquals("high", strategy?.preferredQualityOverride)
        assertTrue(strategy?.requireDirect == true)
        assertTrue(strategy?.preferM4a == true)
    }

    @Test
    fun `offline youtube cache error always attempts recovery`() {
        val shouldRecover = shouldAttemptYouTubePlaybackRecovery(
            error = playbackError(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED),
            isOfflineCache = true
        )

        assertTrue(shouldRecover)
    }

    @Test
    fun `remote audio track error does not force youtube stream recovery`() {
        val strategy = resolveYouTubePlaybackRecoveryStrategy(
            error = playbackError(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED),
            isOfflineCache = false
        )

        assertNull(strategy)
    }

    @Test
    fun `non youtube song does not receive youtube recovery strategy`() {
        val strategy = PlayerManager.youtubePlaybackRecoveryStrategyForError(
            error = playbackError(PlaybackException.ERROR_CODE_DECODING_FAILED),
            song = song(mediaUri = null),
            isOfflineCache = false
        )

        assertNull(strategy)
    }

    @Test
    fun `offline cache key is extracted from synthetic cache url`() {
        assertEquals(
            "ytmusic-fbvvS8e1KgI-very_high-m4a",
            offlineCacheKeyFromUrl("http://offline.cache/ytmusic-fbvvS8e1KgI-very_high-m4a")
        )
        assertNull(offlineCacheKeyFromUrl("https://example.com/audio.m4a"))
    }

    @Test
    fun `youtube recovery cache key uses stable m4a namespace`() {
        assertEquals(
            "ytmusic-fbvvS8e1KgI-very_high-stable-m4a",
            PlayerManager.computeYouTubeCacheKey(
                videoId = "fbvvS8e1KgI",
                preferredQuality = "very_high",
                preferM4a = true
            )
        )
    }

    private fun playbackError(errorCode: Int): PlaybackException {
        return PlaybackException("test", null, errorCode)
    }

    private fun song(mediaUri: String?): SongItem {
        return SongItem(
            id = 1L,
            name = "song",
            artist = "artist",
            album = "album",
            albumId = 1L,
            durationMs = 223_041L,
            coverUrl = null,
            mediaUri = mediaUri
        )
    }

    @Test
    fun `bad http status recovery stops forcing a direct stream`() {
        // 机房出口上 googlevideo 常年拒直链，继续强制直链只会拿回同一条 403
        val strategy = resolveYouTubePlaybackRecoveryStrategy(
            error = playbackError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
            isOfflineCache = false
        )
        assertFalse(strategy!!.requireDirect)
    }

    @Test
    fun `container failure still recovers through a direct stream`() {
        val strategy = resolveYouTubePlaybackRecoveryStrategy(
            error = playbackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
            isOfflineCache = false
        )
        assertTrue(strategy!!.requireDirect)
    }

    @Test
    fun `offline audio failure refreshes without deleting the cache`() {
        val error = playbackError(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
        assertTrue(
            shouldAttemptCachedPlaybackRepair(
                error = error,
                isOfflineCache = true,
                isYouTubeTrack = false,
                isLocalSong = false
            )
        )
        assertFalse(
            shouldInvalidateCachedResourceForPlaybackRecovery(
                error = error
            )
        )
    }

    @Test
    fun `remote timeout refreshes the resource so a bad cache entry is discarded`() {
        val error = playbackError(PlaybackException.ERROR_CODE_TIMEOUT)
        assertTrue(
            shouldAttemptCachedPlaybackRepair(
                error = error,
                isOfflineCache = false,
                isYouTubeTrack = false,
                isLocalSong = false
            )
        )
        assertTrue(
            shouldInvalidateCachedResourceForPlaybackRecovery(
                error = error
            )
        )
    }

    @Test
    fun `local media does not trigger remote cache repair`() {
        assertFalse(
            shouldAttemptCachedPlaybackRepair(
                error = playbackError(PlaybackException.ERROR_CODE_TIMEOUT),
                isOfflineCache = false,
                isYouTubeTrack = false,
                isLocalSong = true
            )
        )
    }

    @Test
    fun `remote decoder failure does not trigger generic cache recovery`() {
        val error = playbackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        assertFalse(
            shouldAttemptCachedPlaybackRepair(
                error = error,
                isOfflineCache = false,
                isYouTubeTrack = false,
                isLocalSong = false
            )
        )
        assertFalse(
            shouldInvalidateCachedResourceForPlaybackRecovery(
                error = error
            )
        )
    }

    @Test
    fun `remote parsing failure does not trigger generic cache recovery`() {
        val error = playbackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)

        assertFalse(
            shouldAttemptCachedPlaybackRepair(
                error = error,
                isOfflineCache = false,
                isYouTubeTrack = false,
                isLocalSong = false
            )
        )
        assertFalse(
            shouldInvalidateCachedResourceForPlaybackRecovery(
                error = error
            )
        )
    }

    @Test
    fun `youtube format recovery retains a remote cache entry`() {
        val error = playbackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        assertTrue(
            shouldAttemptCachedPlaybackRepair(
                error = error,
                isOfflineCache = false,
                isYouTubeTrack = true,
                isLocalSong = false
            )
        )
        assertFalse(
            shouldInvalidateCachedResourceForPlaybackRecovery(
                error = error
            )
        )
        assertFalse(
            shouldInvalidateCacheAfterPlaybackFailure(
                shouldInvalidateCache = shouldInvalidateCachedResourceForPlaybackRecovery(error),
                isOfflineCache = false
            )
        )
    }

    @Test
    fun `unrecoverable remote network failure invalidates its cache`() {
        val error = playbackError(PlaybackException.ERROR_CODE_TIMEOUT)

        assertTrue(
            shouldInvalidateCacheAfterPlaybackFailure(
                shouldInvalidateCache = shouldInvalidateCachedResourceForPlaybackRecovery(error),
                isOfflineCache = false
            )
        )
    }

    @Test
    fun `offline cache failure never invalidates cache in final failure path`() {
        assertFalse(
            shouldInvalidateCacheAfterPlaybackFailure(
                shouldInvalidateCache = true,
                isOfflineCache = true
            )
        )
    }

    @Test
    fun `offline decoder error discards cache so recovery does not loop on damaged media`() {
        val error = playbackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        assertTrue(
            shouldInvalidateCacheForPlaybackRecovery(
                error = error,
                isOfflineCache = true
            )
        )
    }

    @Test
    fun `remote decoder error retains cache because decoder support is not cache corruption`() {
        val error = playbackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        assertFalse(
            shouldInvalidateCacheForPlaybackRecovery(
                error = error,
                isOfflineCache = false
            )
        )
    }

    @Test
    fun `media3 stuck timeout does not discard cache`() {
        val error = PlaybackException(
            "test",
            StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NOT_ENDING,
                60_000
            ),
            PlaybackException.ERROR_CODE_TIMEOUT
        )

        assertFalse(shouldInvalidateCachedResourceForPlaybackRecovery(error))
        assertFalse(shouldAttemptYouTubePlaybackRecovery(error, isOfflineCache = false))
        assertNull(resolveYouTubePlaybackRecoveryStrategy(error, isOfflineCache = false))
        assertTrue(shouldTreatPlaybackFailureAsTrackEnd(error))
        assertTrue(shouldAdvanceAfterStuckTrackEnd(error, playbackRequested = true))
        assertFalse(shouldAdvanceAfterStuckTrackEnd(error, playbackRequested = false))
    }

    @Test
    fun `other media3 stuck timeouts retain normal recovery behavior`() {
        val error = PlaybackException(
            "test",
            StuckPlayerException(
                StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS,
                60_000
            ),
            PlaybackException.ERROR_CODE_TIMEOUT
        )

        assertTrue(shouldInvalidateCachedResourceForPlaybackRecovery(error))
        assertTrue(shouldAttemptYouTubePlaybackRecovery(error, isOfflineCache = false))
        assertFalse(shouldTreatPlaybackFailureAsTrackEnd(error))
    }
}
