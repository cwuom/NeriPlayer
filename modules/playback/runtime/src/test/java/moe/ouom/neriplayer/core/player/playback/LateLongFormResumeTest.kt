package moe.ouom.neriplayer.core.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LateLongFormResumeTest {

    @Test
    fun `history that arrives right after the start moves playback to the remembered position`() {
        assertTrue(
            shouldApplyLateLongFormResume(
                sameRequest = true,
                sameSong = true,
                currentPositionMs = 800L,
                rememberedPositionMs = 1_250_000L
            )
        )
    }

    @Test
    fun `a newer request, another song or progress the user already made are left alone`() {
        assertFalse(shouldApplyLateLongFormResume(false, true, 0L, 1_250_000L))
        assertFalse(shouldApplyLateLongFormResume(true, false, 0L, 1_250_000L))
        assertFalse(
            shouldApplyLateLongFormResume(true, true, LATE_LONG_FORM_RESUME_MAX_ELAPSED_MS + 1L, 1_250_000L)
        )
    }

    @Test
    fun `no remembered position or one behind the playhead does not seek`() {
        assertFalse(shouldApplyLateLongFormResume(true, true, 1_000L, 0L))
        assertFalse(shouldApplyLateLongFormResume(true, true, 2_000L, 1_500L))
    }
}
