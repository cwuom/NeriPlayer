package moe.ouom.neriplayer.core.player.audio.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackVolumeNormalizationStateTest {
    @Test
    fun `generation changes only when enabling changes or a new track starts`() {
        val original = PlaybackVolumeNormalizationState.current()
        try {
            PlaybackVolumeNormalizationState.updateEnabled(original.enabled)
            assertEquals(original, PlaybackVolumeNormalizationState.current())

            PlaybackVolumeNormalizationState.updateEnabled(!original.enabled)
            val changed = PlaybackVolumeNormalizationState.current()
            assertEquals(!original.enabled, changed.enabled)
            assertEquals(original.generation + 1L, changed.generation)

            PlaybackVolumeNormalizationState.resetForNewTrack()
            assertEquals(changed.copy(generation = changed.generation + 1L), PlaybackVolumeNormalizationState.current())
        } finally {
            PlaybackVolumeNormalizationState.updateEnabled(original.enabled)
        }
    }
}
