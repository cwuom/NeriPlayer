package moe.ouom.neriplayer.core.player.policy.offload

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioOffloadPolicyTest {
    @Test
    fun `pcm requirements identify each active condition`() {
        assertEquals(emptySet<PcmAudioRequirement>(), resolvePcmRequirements())
        assertEquals(
            setOf(PcmAudioRequirement.NETEASE_STREAM),
            resolvePcmRequirements(audioSource = PlaybackAudioSource.NETEASE)
        )
        assertEquals(
            setOf(PcmAudioRequirement.BILIBILI_STREAM),
            resolvePcmRequirements(audioSource = PlaybackAudioSource.BILIBILI)
        )
        assertEquals(
            setOf(PcmAudioRequirement.USB_EXCLUSIVE),
            resolvePcmRequirements(usbExclusivePlaybackEnabled = true)
        )
        assertEquals(
            setOf(PcmAudioRequirement.PLAYBACK_SPEED),
            resolvePcmRequirements(playbackSpeed = 1.25f)
        )
        assertEquals(
            setOf(PcmAudioRequirement.PLAYBACK_PITCH),
            resolvePcmRequirements(playbackPitch = 0.9f)
        )
        assertEquals(
            setOf(PcmAudioRequirement.AUDIO_EFFECTS),
            resolvePcmRequirements(audioEffectsActive = true)
        )
        assertEquals(
            setOf(PcmAudioRequirement.BALANCE),
            resolvePcmRequirements(volumeBalance = 0.25f)
        )
        assertEquals(
            setOf(PcmAudioRequirement.VOLUME_NORMALIZATION),
            resolvePcmRequirements(volumeNormalizationEnabled = true)
        )
        assertEquals(
            setOf(PcmAudioRequirement.HIGH_RESOLUTION),
            resolvePcmRequirements(highResolutionOutputEnabled = true)
        )
        assertEquals(
            setOf(PcmAudioRequirement.AUDIO_REACTIVE),
            resolvePcmRequirements(audioReactiveActive = true)
        )
        assertEquals(
            setOf(PcmAudioRequirement.LISTEN_TOGETHER_RATE),
            resolvePcmRequirements(listenTogetherPlaybackRate = 1.02f)
        )
    }

    @Test
    fun `pcm requirements retain multiple reasons and speed tolerance`() {
        assertEquals(
            setOf(PcmAudioRequirement.NETEASE_STREAM, PcmAudioRequirement.AUDIO_EFFECTS),
            resolvePcmRequirements(audioSource = PlaybackAudioSource.NETEASE, audioEffectsActive = true)
        )
        assertEquals(emptySet<PcmAudioRequirement>(), resolvePcmRequirements(playbackSpeed = 1.0005f))
    }

    @Test
    fun `offload stall fallback keeps downloaded local audio on pcm while reactive is off`() {
        assertEquals(
            emptySet<PcmAudioRequirement>(),
            resolvePcmRequirements(audioSource = PlaybackAudioSource.LOCAL, audioReactiveActive = false)
        )
        assertEquals(
            setOf(PcmAudioRequirement.OFFLOAD_STALL_FALLBACK),
            resolvePcmRequirements(
                audioSource = PlaybackAudioSource.LOCAL,
                audioReactiveActive = false,
                offloadStallFallbackActive = true
            )
        )
    }

    @Test
    fun `default playback does not require pcm processing`() {
        assertTrue(resolvePcmRequirements().isEmpty())
    }

    @Test
    fun `now playing without audio reactive remains offload eligible`() {
        assertTrue(resolvePcmRequirements(audioReactiveActive = false).isEmpty())
    }

    @Test
    fun `netease streams require pcm even when audio reactive is disabled`() {
        assertTrue(
            resolvePcmRequirements(
                audioReactiveActive = false,
                audioSource = PlaybackAudioSource.NETEASE
            ).isNotEmpty()
        )
    }

    @Test
    fun `bili fallback streams require pcm so task removal cannot leave queued offload audio`() {
        assertTrue(
            resolvePcmRequirements(
                audioSource = PlaybackAudioSource.BILIBILI
            ).isNotEmpty()
        )
    }

    @Test
    fun `disabling reactive output during playback does not request a pipeline rebuild`() {
        assertFalse(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = false,
                playbackActive = true,
                currentAudioReactiveEnabled = false
            )
        )
        assertTrue(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = true,
                playbackActive = true,
                currentAudioReactiveEnabled = true
            )
        )
        assertTrue(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = false,
                playbackActive = false,
                currentAudioReactiveEnabled = false
            )
        )
    }

    @Test
    fun `stale enabling reactive callback cannot update offload during playback`() {
        assertFalse(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = true,
                playbackActive = true,
                currentAudioReactiveEnabled = false
            )
        )
    }

    @Test
    fun `stale reactive callbacks cannot update offload while idle`() {
        assertFalse(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = true,
                playbackActive = false,
                currentAudioReactiveEnabled = false
            )
        )
        assertFalse(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = false,
                playbackActive = false,
                currentAudioReactiveEnabled = true
            )
        )
    }

    @Test
    fun `current enabling reactive callback updates offload while idle`() {
        assertTrue(
            shouldUpdateAudioOffloadForReactiveChange(
                audioReactiveEnabled = true,
                playbackActive = false,
                currentAudioReactiveEnabled = true
            )
        )
    }

    private fun resolvePcmRequirements(
        usbExclusivePlaybackEnabled: Boolean = false,
        playbackSpeed: Float = 1f,
        playbackPitch: Float = 1f,
        audioEffectsActive: Boolean = false,
        volumeBalance: Float = 0f,
        volumeNormalizationEnabled: Boolean = false,
        highResolutionOutputEnabled: Boolean = false,
        audioReactiveActive: Boolean = false,
        audioSource: PlaybackAudioSource? = null,
        listenTogetherPlaybackRate: Float = 1f,
        offloadStallFallbackActive: Boolean = false,
    ): Set<PcmAudioRequirement> {
        return pcmAudioRequirements(
            offloadStallFallbackActive = offloadStallFallbackActive,
            usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled,
            playbackSpeed = playbackSpeed,
            playbackPitch = playbackPitch,
            audioEffectsActive = audioEffectsActive,
            volumeBalance = volumeBalance,
            volumeNormalizationEnabled = volumeNormalizationEnabled,
            highResolutionOutputEnabled = highResolutionOutputEnabled,
            audioReactiveActive = audioReactiveActive,
            audioSource = audioSource,
            listenTogetherPlaybackRate = listenTogetherPlaybackRate,
        )
    }
}
