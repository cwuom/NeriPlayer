package moe.ouom.neriplayer.core.player.audio.effects

import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsInactiveReason
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsResolution
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.neutralDspParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AudioEffectsRuntimeStateTest {
    @After
    fun restoreInactiveState() {
        AudioEffectsRuntimeState.publishEngineStats(AudioEffectsEngineStats(0L, 0L, 0f, 0f, 0))
        AudioEffectsRuntimeState.publish(inactive(), usbNativeAllowed = false, route = AudioOutputRoute.SPEAKER)
        AudioEffectsRuntimeState.publishPathState(sinkReason = null, nativeAvailable = true)
    }

    @Test
    fun `engine stats racing a settings publish keep the settings fields`() {
        val active = AudioEffectsResolution(active = true, params = neutralDspParams(), inactiveReason = null)
        AudioEffectsRuntimeState.publish(active, usbNativeAllowed = false, route = AudioOutputRoute.WIRED)
        var interleaved = false
        val engineStats = mock(AudioEffectsEngineStats::class.java)
        `when`(engineStats.sampleRate).thenReturn(48_000)
        `when`(engineStats.cpuLoadPercent).thenAnswer {
            // 引擎统计读到旧统计之后、写回之前，设置侧关闭了音效
            if (!interleaved) {
                interleaved = true
                AudioEffectsRuntimeState.publish(inactive(), usbNativeAllowed = false, route = AudioOutputRoute.SPEAKER)
            }
            12f
        }

        AudioEffectsRuntimeState.publishEngineStats(engineStats)

        val stats = AudioEffectsRuntimeState.stats.value
        assertTrue(interleaved)
        assertFalse(stats.active)
        assertEquals(AudioEffectsInactiveReason.DISABLED, stats.inactiveReason)
        assertEquals(AudioOutputRoute.SPEAKER, stats.route)
        assertEquals(48_000, stats.sampleRate)
    }

    private fun inactive() = AudioEffectsResolution(
        active = false,
        params = neutralDspParams(),
        inactiveReason = AudioEffectsInactiveReason.DISABLED
    )
}
