package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackServiceIdleShutdownPreferenceTest {
    @Test
    fun `supported values stay unchanged`() {
        PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTE_OPTIONS.forEach { minutes ->
            assertEquals(minutes, PlaybackServiceIdleShutdownPreference.normalize(minutes))
        }
    }

    @Test
    fun `unsupported values fall back to sixty minutes`() {
        assertEquals(
            DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES,
            PlaybackServiceIdleShutdownPreference.normalize(-1)
        )
        assertEquals(
            DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES,
            PlaybackServiceIdleShutdownPreference.normalize(10)
        )
        assertEquals(
            DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES,
            PlaybackServiceIdleShutdownPreference.normalize(Int.MAX_VALUE)
        )
    }

    @Test
    fun `disabled value maps to zero delay`() {
        assertEquals(0L, PlaybackServiceIdleShutdownPreference.delayMs(0))
    }
}
