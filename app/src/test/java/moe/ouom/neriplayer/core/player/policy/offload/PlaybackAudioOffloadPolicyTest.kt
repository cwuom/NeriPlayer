package moe.ouom.neriplayer.core.player.policy.offload

import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioOffloadPolicyTest {
    @Test
    fun `default playback does not require pcm processing`() {
        assertFalse(resolveRequiresPcmAudioProcessing())
    }

    @Test
    fun `every pcm feature disables offload eligibility`() {
        assertTrue(resolveRequiresPcmAudioProcessing(usbExclusivePlaybackEnabled = true))
        assertTrue(resolveRequiresPcmAudioProcessing(playbackSpeed = 1.25f))
        assertTrue(resolveRequiresPcmAudioProcessing(playbackPitch = 0.9f))
        assertTrue(resolveRequiresPcmAudioProcessing(equalizerEnabled = true))
        assertTrue(resolveRequiresPcmAudioProcessing(loudnessGainMb = 100))
        assertTrue(resolveRequiresPcmAudioProcessing(volumeBalance = 0.25f))
        assertTrue(resolveRequiresPcmAudioProcessing(volumeNormalizationEnabled = true))
        assertTrue(resolveRequiresPcmAudioProcessing(highResolutionOutputEnabled = true))
        assertTrue(resolveRequiresPcmAudioProcessing(audioReactiveActive = true))
        assertTrue(resolveRequiresPcmAudioProcessing(listenTogetherPlaybackRate = 1.02f))
    }

    @Test
    fun `now playing without audio reactive remains offload eligible`() {
        assertFalse(resolveRequiresPcmAudioProcessing(audioReactiveActive = false))
    }

    @Test
    fun `netease streams require pcm even when audio reactive is disabled`() {
        assertTrue(
            resolveRequiresPcmAudioProcessing(
                audioReactiveActive = false,
                audioSource = PlaybackAudioSource.NETEASE
            )
        )
    }

    @Test
    fun `bili fallback streams require pcm so task removal cannot leave queued offload audio`() {
        assertTrue(
            resolveRequiresPcmAudioProcessing(
                audioSource = PlaybackAudioSource.BILIBILI
            )
        )
    }

    @Test
    fun `disabling reactive output during playback does not request a pipeline rebuild`() {
        assertFalse(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = false,
                playbackActive = true
            )
        )
        assertTrue(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = true,
                playbackActive = true
            )
        )
        assertTrue(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = false,
                playbackActive = false
            )
        )
    }

    private fun resolveRequiresPcmAudioProcessing(
        usbExclusivePlaybackEnabled: Boolean = false,
        playbackSpeed: Float = 1f,
        playbackPitch: Float = 1f,
        equalizerEnabled: Boolean = false,
        loudnessGainMb: Int = 0,
        volumeBalance: Float = 0f,
        volumeNormalizationEnabled: Boolean = false,
        highResolutionOutputEnabled: Boolean = false,
        audioReactiveActive: Boolean = false,
        audioSource: PlaybackAudioSource? = null,
        listenTogetherPlaybackRate: Float = 1f,
    ): Boolean {
        return requiresPcmAudioProcessing(
            usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled,
            playbackSpeed = playbackSpeed,
            playbackPitch = playbackPitch,
            equalizerEnabled = equalizerEnabled,
            loudnessGainMb = loudnessGainMb,
            volumeBalance = volumeBalance,
            volumeNormalizationEnabled = volumeNormalizationEnabled,
            highResolutionOutputEnabled = highResolutionOutputEnabled,
            audioReactiveActive = audioReactiveActive,
            audioSource = audioSource,
            listenTogetherPlaybackRate = listenTogetherPlaybackRate,
        )
    }
}
