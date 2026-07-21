package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveLoudPlaybackWarningPolicyTest {

    @Test
    fun `new playback estimates the restored full player gain rather than a paused fade`() {
        assertEquals(
            1f,
            predictedUsbExclusivePlaybackGain(
                currentPlayerVolume = 0f,
                playbackAlreadyAudible = false
            )
        )
        assertEquals(
            0.4f,
            predictedUsbExclusivePlaybackGain(
                currentPlayerVolume = 0.4f,
                playbackAlreadyAudible = true
            )
        )
    }

    @Test
    fun `UAC2 high resolution DAC warns at a conservative digital peak`() {
        val estimate = estimateUsbExclusiveLoudness(
            systemVolumePercent = 65,
            playerVolume = 1f,
            uacVersion = "2.0",
            outputSampleRate = 192_000,
            outputBitDepth = 32,
            observedOutputPeak = null
        )

        assertEquals(UsbExclusiveOutputDeviceClass.Uac2, estimate.deviceClass)
        assertEquals(-7.49, estimate.estimatedPeakDbfs, 0.01)
        assertEquals(UsbExclusiveLoudPlaybackRisk.Elevated, estimate.risk)
    }

    @Test
    fun `UAC1 and UAC2 retain distinct conservative thresholds`() {
        val uac1 = estimateUsbExclusiveLoudness(
            systemVolumePercent = 65,
            playerVolume = 1f,
            uacVersion = "1.0",
            outputSampleRate = 48_000,
            outputBitDepth = 16,
            observedOutputPeak = null
        )
        val uac2 = estimateUsbExclusiveLoudness(
            systemVolumePercent = 65,
            playerVolume = 1f,
            uacVersion = "2.0",
            outputSampleRate = 192_000,
            outputBitDepth = 32,
            observedOutputPeak = null
        )

        assertEquals(UsbExclusiveLoudPlaybackRisk.None, uac1.risk)
        assertEquals(UsbExclusiveLoudPlaybackRisk.Elevated, uac2.risk)
    }

    @Test
    fun `observed output peak can raise the conservative estimate`() {
        val estimate = estimateUsbExclusiveLoudness(
            systemVolumePercent = 10,
            playerVolume = 0.5f,
            uacVersion = null,
            outputSampleRate = 48_000,
            outputBitDepth = 16,
            observedOutputPeak = 0.51f
        )

        assertEquals(-5.85, estimate.estimatedPeakDbfs, 0.01)
        assertEquals(-5.85, estimate.observedPeakDbfs ?: Double.NaN, 0.01)
        assertEquals(UsbExclusiveLoudnessPeakSource.RecentSample, estimate.peakSource)
        assertEquals(UsbExclusiveLoudPlaybackRisk.High, estimate.risk)
    }

    @Test
    fun `recent PCM peak cannot lower the current volume ceiling`() {
        val estimate = estimateUsbExclusiveLoudness(
            systemVolumePercent = 100,
            playerVolume = 1f,
            uacVersion = "1.0",
            outputSampleRate = 48_000,
            outputBitDepth = 16,
            observedOutputPeak = 0.25f
        )

        assertEquals(0.0, estimate.estimatedPeakDbfs, 0.01)
        assertEquals(UsbExclusiveLoudnessPeakSource.VolumeCeiling, estimate.peakSource)
        assertEquals(UsbExclusiveLoudPlaybackRisk.Critical, estimate.risk)
    }

    @Test
    fun `paused stream peak cannot suppress a warning after system volume increases`() {
        val estimate = estimateUsbExclusiveLoudness(
            systemVolumePercent = 81,
            playerVolume = 1f,
            uacVersion = "2.0",
            outputSampleRate = 96_000,
            outputBitDepth = 32,
            observedOutputPeak = 0.173418f,
            riskThresholdDbfs = -6
        )

        assertEquals(-3.66, estimate.estimatedPeakDbfs, 0.01)
        assertEquals(UsbExclusiveLoudnessPeakSource.VolumeCeiling, estimate.peakSource)
        assertEquals(UsbExclusiveLoudPlaybackRisk.High, estimate.risk)
        assertTrue(estimate.requiresConfirmation)
    }

    @Test
    fun `caution does not interrupt playback but configured high risk does`() {
        val caution = estimateUsbExclusiveLoudness(
            systemVolumePercent = 65,
            playerVolume = 1f,
            uacVersion = "2.0",
            outputSampleRate = 192_000,
            outputBitDepth = 32,
            observedOutputPeak = null
        )
        val configuredHighRisk = estimateUsbExclusiveLoudness(
            systemVolumePercent = 75,
            playerVolume = 1f,
            uacVersion = "2.0",
            outputSampleRate = 192_000,
            outputBitDepth = 32,
            observedOutputPeak = null,
            riskThresholdDbfs = -6
        )

        assertEquals(UsbExclusiveLoudPlaybackRisk.Elevated, caution.risk)
        assertFalse(caution.requiresConfirmation)
        assertFalse(
            shouldRequestUsbExclusiveLoudPlaybackWarning(
                usbExclusiveEnabled = true,
                appInForeground = true,
                commandSource = PlaybackCommandSource.LOCAL,
                playbackAlreadyAudible = false,
                loudnessEstimate = caution
            )
        )
        assertEquals(-6, configuredHighRisk.riskThresholdDbfs)
        assertEquals(UsbExclusiveLoudPlaybackRisk.High, configuredHighRisk.risk)
        assertTrue(configuredHighRisk.requiresConfirmation)
    }

    @Test
    fun `warns before a foreground local USB start when estimate is risky`() {
        assertTrue(
            shouldRequestUsbExclusiveLoudPlaybackWarning(
                usbExclusiveEnabled = true,
                appInForeground = true,
                commandSource = PlaybackCommandSource.LOCAL,
                playbackAlreadyAudible = false,
                loudnessEstimate = estimateUsbExclusiveLoudness(
                    systemVolumePercent = 85,
                    playerVolume = 1f,
                    uacVersion = "2.0",
                    outputSampleRate = 192_000,
                    outputBitDepth = 32,
                    observedOutputPeak = null
                )
            )
        )
    }

    @Test
    fun `does not interrupt background remote or ongoing playback`() {
        val estimate = estimateUsbExclusiveLoudness(
            systemVolumePercent = 100,
            playerVolume = 1f,
            uacVersion = "2.0",
            outputSampleRate = 192_000,
            outputBitDepth = 32,
            observedOutputPeak = null
        )

        assertFalse(
            shouldRequestUsbExclusiveLoudPlaybackWarning(
                usbExclusiveEnabled = true,
                appInForeground = false,
                commandSource = PlaybackCommandSource.LOCAL,
                playbackAlreadyAudible = false,
                loudnessEstimate = estimate
            )
        )
        assertFalse(
            shouldRequestUsbExclusiveLoudPlaybackWarning(
                usbExclusiveEnabled = true,
                appInForeground = true,
                commandSource = PlaybackCommandSource.REMOTE_SYNC,
                playbackAlreadyAudible = false,
                loudnessEstimate = estimate
            )
        )
        assertFalse(
            shouldRequestUsbExclusiveLoudPlaybackWarning(
                usbExclusiveEnabled = true,
                appInForeground = true,
                commandSource = PlaybackCommandSource.LOCAL,
                playbackAlreadyAudible = true,
                loudnessEstimate = estimate
            )
        )
    }

    @Test
    fun `configured threshold applies to every new local start`() {
        val estimate = estimateUsbExclusiveLoudness(
            systemVolumePercent = 55,
            playerVolume = 1f,
            uacVersion = "2.0",
            outputSampleRate = 192_000,
            outputBitDepth = 32,
            observedOutputPeak = null,
            riskThresholdDbfs = -12
        )

        repeat(2) {
            assertTrue(
                shouldRequestUsbExclusiveLoudPlaybackWarning(
                    usbExclusiveEnabled = true,
                    appInForeground = true,
                    commandSource = PlaybackCommandSource.LOCAL,
                    playbackAlreadyAudible = false,
                    loudnessEstimate = estimate
                )
            )
        }
    }
}
