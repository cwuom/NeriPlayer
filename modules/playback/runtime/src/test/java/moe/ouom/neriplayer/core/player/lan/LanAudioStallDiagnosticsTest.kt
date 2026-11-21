package moe.ouom.neriplayer.core.player.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAudioStallDiagnosticsTest {
    private val diagnostics = LanAudioStallDiagnostics()
    private fun playing() = diagnostics.reportRebuffer(playing = true, playWhenReady = true, buffering = false)
    private fun buffering() = diagnostics.reportRebuffer(playing = false, playWhenReady = true, buffering = true)

    @Test fun onlyBufferingAfterActualPlaybackCountsOnceUntilPlaybackResumes() {
        assertFalse(buffering())
        assertFalse(playing())
        assertTrue(buffering())
        assertFalse(buffering())
        assertFalse(playing())
        assertTrue(buffering())
        assertEquals(2, diagnostics.rebuffers)
    }

    @Test fun explicitSeekOrPauseDoesNotCountAsADrain() {
        playing()
        diagnostics.resetPlaybackTransition()
        assertFalse(buffering())
        playing()
        assertFalse(diagnostics.reportRebuffer(playing = false, playWhenReady = false, buffering = true))
        assertFalse(buffering())
        assertEquals(0, diagnostics.rebuffers)
    }

    @Test fun shortUnderrunsAndRebuffersHaveIndependentTotalsAndBoundedLogging() {
        val reportedUnderruns = (1..17).filter { diagnostics.reportUnderrun() }
        assertEquals(listOf(1, 2, 4, 8, 16), reportedUnderruns)
        assertEquals(17, diagnostics.underruns)
        assertEquals(0, diagnostics.rebuffers)
        val reportedRebuffers = (1..9).filter { playing(); buffering() }
        assertEquals(listOf(1, 2, 4, 8), reportedRebuffers)
        assertEquals(9, diagnostics.rebuffers)
        assertEquals(17, diagnostics.underruns)
    }
}
