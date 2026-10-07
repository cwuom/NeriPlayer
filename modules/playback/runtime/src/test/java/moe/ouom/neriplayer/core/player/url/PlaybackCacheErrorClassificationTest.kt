package moe.ouom.neriplayer.core.player.url

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCacheErrorClassificationTest {

    @Test
    fun `transport failures and plain timeouts invalidate the cached resource`() {
        TRANSPORT_FAILURES.forEach { code ->
            assertTrue("errorCode=$code", shouldInvalidateCachedResourceForPlaybackRecovery(error(code)))
        }
    }

    @Test
    fun `stuck track end timeouts and unrelated failures keep the cached resource`() {
        val stuckAtTrackEnd = PlaybackException(
            "stuck",
            StuckPlayerException(StuckPlayerException.STUCK_PLAYING_NOT_ENDING, 60_000),
            PlaybackException.ERROR_CODE_TIMEOUT
        )

        assertFalse(shouldInvalidateCachedResourceForPlaybackRecovery(stuckAtTrackEnd))
        (MEDIA_FORMAT_FAILURES + PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).forEach { code ->
            assertFalse("errorCode=$code", shouldInvalidateCachedResourceForPlaybackRecovery(error(code)))
        }
    }

    @Test
    fun `media format failures discard only offline cache entries`() {
        MEDIA_FORMAT_FAILURES.forEach { code ->
            assertTrue("errorCode=$code", shouldInvalidateCacheForPlaybackRecovery(error(code), isOfflineCache = true))
            assertFalse("errorCode=$code", shouldInvalidateCacheForPlaybackRecovery(error(code), isOfflineCache = false))
        }
        assertFalse(
            shouldInvalidateCacheForPlaybackRecovery(
                error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
                isOfflineCache = true
            )
        )
        assertTrue(
            shouldInvalidateCacheForPlaybackRecovery(
                error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
                isOfflineCache = false
            )
        )
    }

    private fun error(code: Int) = PlaybackException("failure", null, code)

    private companion object {
        val TRANSPORT_FAILURES = listOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT
        )
        val MEDIA_FORMAT_FAILURES = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
    }
}
