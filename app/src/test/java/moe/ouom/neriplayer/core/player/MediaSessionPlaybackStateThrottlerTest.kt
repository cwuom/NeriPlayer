package moe.ouom.neriplayer.core.player

import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionPlaybackStateThrottlerTest {

    private val throttler = MediaSessionPlaybackStateThrottler(
        minUpdateIntervalMs = 1_000L,
        positionDriftThresholdMs = 1_500L,
    )
    private val defaultControlFingerprint = 1

    @Test
    fun `playing progress updates are throttled inside interval`() {
        assertTrue(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 0L,
                speed = 1.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 0L,
            )
        )
        throttler.recordDispatch(
            playbackState = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = defaultControlFingerprint,
            nowElapsedRealtimeMs = 0L,
        )

        assertFalse(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 40L,
                speed = 1.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 40L,
            )
        )
        assertFalse(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 900L,
                speed = 1.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 900L,
            )
        )
        assertTrue(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 1_000L,
                speed = 1.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 1_000L,
            )
        )
    }

    @Test
    fun `playing seek drift bypasses interval throttle`() {
        throttler.recordDispatch(
            playbackState = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = defaultControlFingerprint,
            nowElapsedRealtimeMs = 0L,
        )

        assertTrue(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 8_000L,
                speed = 1.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 300L,
            )
        )
    }

    @Test
    fun `paused position change dispatches immediately`() {
        throttler.recordDispatch(
            playbackState = PlaybackStateCompat.STATE_PAUSED,
            positionMs = 5_000L,
            speed = 0.0f,
            controlFingerprint = defaultControlFingerprint,
            nowElapsedRealtimeMs = 0L,
        )

        assertFalse(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PAUSED,
                positionMs = 5_000L,
                speed = 0.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 500L,
            )
        )
        assertTrue(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PAUSED,
                positionMs = 5_250L,
                speed = 0.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 500L,
            )
        )
    }

    @Test
    fun `state changes and forced updates always dispatch`() {
        throttler.recordDispatch(
            playbackState = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = defaultControlFingerprint,
            nowElapsedRealtimeMs = 0L,
        )

        assertTrue(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PAUSED,
                positionMs = 200L,
                speed = 0.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 200L,
            )
        )
        assertTrue(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 40L,
                speed = 1.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 40L,
                force = true,
            )
        )
    }

    @Test
    fun `forced duplicate snapshot is still suppressed`() {
        throttler.recordDispatch(
            playbackState = PlaybackStateCompat.STATE_BUFFERING,
            positionMs = 4_235L,
            speed = 0.0f,
            controlFingerprint = defaultControlFingerprint,
            nowElapsedRealtimeMs = 0L,
        )

        assertFalse(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_BUFFERING,
                positionMs = 4_235L,
                speed = 0.0f,
                controlFingerprint = defaultControlFingerprint,
                nowElapsedRealtimeMs = 7L,
                force = true,
            )
        )
    }

    @Test
    fun `control changes bypass duplicate suppression`() {
        throttler.recordDispatch(
            playbackState = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 12_000L,
            speed = 1.0f,
            controlFingerprint = defaultControlFingerprint,
            nowElapsedRealtimeMs = 0L,
        )

        assertTrue(
            throttler.shouldDispatch(
                playbackState = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 12_000L,
                speed = 1.0f,
                controlFingerprint = defaultControlFingerprint + 1,
                nowElapsedRealtimeMs = 5L,
                force = true,
            )
        )
    }
}
