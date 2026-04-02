package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerManagerPlaybackSoundConfigTest {

    @Test
    fun `listen together sync rate is applied on top of persisted playback config`() {
        val baseConfig = PlaybackSoundConfig(
            speed = 1.25f,
            pitch = 1.1f,
            loudnessGainMb = 600,
            equalizerEnabled = true,
            presetId = PlaybackEqualizerPresetId.CUSTOM,
            customBandLevelsMb = listOf(-200, 100, 300)
        )

        val effectiveConfig = resolvePlaybackSoundConfigForEngine(
            baseConfig = baseConfig,
            listenTogetherSyncPlaybackRate = 1.03f
        )

        assertEquals(normalizePlaybackSpeed(1.25f * 1.03f), effectiveConfig.speed, 0.0001f)
        assertEquals(baseConfig.pitch, effectiveConfig.pitch, 0.0001f)
        assertEquals(baseConfig.loudnessGainMb, effectiveConfig.loudnessGainMb)
        assertEquals(baseConfig.equalizerEnabled, effectiveConfig.equalizerEnabled)
        assertEquals(baseConfig.presetId, effectiveConfig.presetId)
        assertEquals(baseConfig.customBandLevelsMb, effectiveConfig.customBandLevelsMb)
    }

    @Test
    fun `listen together sync rate is clamped before applying playback speed`() {
        val effectiveConfig = resolvePlaybackSoundConfigForEngine(
            baseConfig = PlaybackSoundConfig(speed = 1.0f),
            listenTogetherSyncPlaybackRate = 1.5f
        )

        assertEquals(1.05f, effectiveConfig.speed, 0.0001f)
    }
}
