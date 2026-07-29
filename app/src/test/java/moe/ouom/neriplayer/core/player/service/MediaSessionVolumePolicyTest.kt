package moe.ouom.neriplayer.core.player.service

import android.media.AudioManager
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionVolumePolicyTest {

    @Test
    fun `only the active native USB path uses remote MediaSession volume`() {
        assertTrue(
            shouldUseUsbExclusiveRemoteVolumeRouting(
                effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
                bitPerfect = false
            )
        )
        assertFalse(
            shouldUseUsbExclusiveRemoteVolumeRouting(
                effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM,
                bitPerfect = false
            )
        )
        assertFalse(
            shouldUseUsbExclusiveRemoteVolumeRouting(
                effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
                bitPerfect = true
            )
        )
    }

    @Test
    fun `provider indexes preserve the system media volume range`() {
        val providerMax = usbExclusiveVolumeProviderMaxIndex(minVolume = 5, maxVolume = 15)

        assertEquals(10, providerMax)
        assertEquals(
            7,
            usbExclusiveVolumeProviderCurrentIndex(
                currentVolume = 12,
                minVolume = 5,
                maxVolume = 15
            )
        )
        assertEquals(0.7f, usbExclusiveVolumeFractionFromProviderIndex(7, providerMax), 0.0001f)
    }

    @Test
    fun `provider absolute volume maps both bounds exactly`() {
        assertEquals(0, usbExclusiveVolumeProviderIndexFromFraction(0f, providerMaxIndex = 100))
        assertEquals(100, usbExclusiveVolumeProviderIndexFromFraction(1f, providerMaxIndex = 100))
        assertEquals(0, usbExclusiveVolumeProviderIndexFromFraction(-1f, providerMaxIndex = 100))
        assertEquals(100, usbExclusiveVolumeProviderIndexFromFraction(2f, providerMaxIndex = 100))
    }

    @Test
    fun `provider relative volume handles raise lower and mute commands`() {
        assertEquals(
            6,
            adjustedUsbExclusiveVolumeProviderIndex(
                currentIndex = 5,
                providerMaxIndex = 10,
                direction = AudioManager.ADJUST_RAISE
            )
        )
        assertEquals(
            4,
            adjustedUsbExclusiveVolumeProviderIndex(
                currentIndex = 5,
                providerMaxIndex = 10,
                direction = AudioManager.ADJUST_LOWER
            )
        )
        assertEquals(
            0,
            adjustedUsbExclusiveVolumeProviderIndex(
                currentIndex = 5,
                providerMaxIndex = 10,
                direction = AudioManager.ADJUST_MUTE
            )
        )
        assertEquals(
            1,
            adjustedUsbExclusiveVolumeProviderIndex(
                currentIndex = 0,
                providerMaxIndex = 10,
                direction = AudioManager.ADJUST_UNMUTE
            )
        )
    }
}
