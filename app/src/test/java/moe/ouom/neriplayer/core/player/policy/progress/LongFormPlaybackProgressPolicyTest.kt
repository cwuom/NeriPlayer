package moe.ouom.neriplayer.core.player.policy.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LongFormPlaybackProgressPolicyTest {
    @Test
    fun `minimum duration content resumes from a remembered position`() {
        val resumePositionMs = resolveLongFormPlaybackResumePosition(
            enabled = true,
            durationMs = LONG_FORM_PLAYBACK_MIN_DURATION_MS,
            requestedPositionMs = 0L,
            rememberedPositionMs = 75_000L
        )

        assertEquals(75_000L, resumePositionMs)
    }

    @Test
    fun `short content does not use remembered position`() {
        val resumePositionMs = resolveLongFormPlaybackResumePosition(
            enabled = true,
            durationMs = LONG_FORM_PLAYBACK_MIN_DURATION_MS - 1L,
            requestedPositionMs = 0L,
            rememberedPositionMs = 75_000L
        )

        assertEquals(0L, resumePositionMs)
    }

    @Test
    fun `explicit position takes precedence over remembered position`() {
        val resumePositionMs = resolveLongFormPlaybackResumePosition(
            enabled = false,
            durationMs = LONG_FORM_PLAYBACK_MIN_DURATION_MS,
            requestedPositionMs = 42_000L,
            rememberedPositionMs = 75_000L
        )

        assertEquals(42_000L, resumePositionMs)
    }

    @Test
    fun `automatic replay does not restore remembered position`() {
        val resumePositionMs = resolveLongFormPlaybackResumePosition(
            enabled = true,
            durationMs = LONG_FORM_PLAYBACK_MIN_DURATION_MS,
            requestedPositionMs = 0L,
            rememberedPositionMs = 75_000L,
            allowRememberedPosition = false
        )

        assertEquals(0L, resumePositionMs)
    }

    @Test
    fun `positions below five seconds are not resumed or persisted`() {
        val resumePositionMs = resolveLongFormPlaybackResumePosition(
            enabled = true,
            durationMs = LONG_FORM_PLAYBACK_MIN_DURATION_MS,
            requestedPositionMs = 0L,
            rememberedPositionMs = 4_999L
        )
        val persistedPositionMs = resolveLongFormPlaybackPositionForPersistence(
            enabled = true,
            durationMs = LONG_FORM_PLAYBACK_MIN_DURATION_MS,
            positionMs = 4_999L
        )

        assertEquals(0L, resumePositionMs)
        assertNull(persistedPositionMs)
    }

    @Test
    fun `positions within thirty seconds of the end are cleared`() {
        val durationMs = LONG_FORM_PLAYBACK_MIN_DURATION_MS
        val endingPositionMs = durationMs - 30_000L

        assertEquals(
            0L,
            resolveLongFormPlaybackResumePosition(
                enabled = true,
                durationMs = durationMs,
                requestedPositionMs = 0L,
                rememberedPositionMs = endingPositionMs
            )
        )
        assertEquals(
            0L,
            resolveLongFormPlaybackPositionForPersistence(
                enabled = true,
                durationMs = durationMs,
                positionMs = endingPositionMs
            )
        )
    }

    @Test
    fun `disabled setting does not persist long form progress`() {
        val persistedPositionMs = resolveLongFormPlaybackPositionForPersistence(
            enabled = false,
            durationMs = LONG_FORM_PLAYBACK_MIN_DURATION_MS,
            positionMs = 75_000L
        )

        assertNull(persistedPositionMs)
    }
}
