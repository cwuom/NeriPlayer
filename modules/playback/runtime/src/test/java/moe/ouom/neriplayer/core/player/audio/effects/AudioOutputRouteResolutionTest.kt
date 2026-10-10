package moe.ouom.neriplayer.core.player.audio.effects

import android.media.AudioDeviceInfo
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputRouteResolutionTest {
    @Test
    fun `system routing result wins over connected devices`() {
        assertEquals(
            AudioOutputRoute.SPEAKER,
            resolveAudioOutputRoute(
                routedTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                connectedTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            )
        )
        assertEquals(
            AudioOutputRoute.USB,
            resolveAudioOutputRoute(listOf(AudioDeviceInfo.TYPE_USB_HEADSET), emptyList())
        )
    }

    @Test
    fun `connected devices follow media routing priority`() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertEquals(AudioOutputRoute.SPEAKER, resolveAudioOutputRoute(emptyList(), listOf(speaker)))
        assertEquals(
            AudioOutputRoute.WIRED,
            resolveAudioOutputRoute(emptyList(), listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        )
        assertEquals(
            AudioOutputRoute.BLUETOOTH,
            resolveAudioOutputRoute(
                emptyList(),
                listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            )
        )
        assertEquals(
            AudioOutputRoute.USB,
            resolveAudioOutputRoute(emptyList(), listOf(speaker, AudioDeviceInfo.TYPE_USB_DEVICE))
        )
        assertEquals(AudioOutputRoute.OTHER, resolveAudioOutputRoute(emptyList(), listOf(AudioDeviceInfo.TYPE_HDMI)))
    }

    @Test
    fun `unknown device types are ignored`() {
        assertEquals(
            AudioOutputRoute.SPEAKER,
            resolveAudioOutputRoute(listOf(AudioDeviceInfo.TYPE_TELEPHONY), listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        )
    }
}
