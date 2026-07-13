@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import androidx.media3.common.PlaybackException
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
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
}
