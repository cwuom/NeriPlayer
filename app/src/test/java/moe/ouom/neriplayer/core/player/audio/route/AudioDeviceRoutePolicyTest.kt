package moe.ouom.neriplayer.core.player.audio.route

import android.media.AudioDeviceInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SpeakerGroup
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AudioDeviceRoutePolicyTest {
    private val playing = AudioDeviceRouteSnapshot(
        initialized = true,
        isPlaying = true,
        playWhenReady = true,
        resumeRequested = true,
        usbExclusiveEnabled = false,
        allowMixedPlayback = false,
        stopOnBluetoothDisconnect = true,
        listenTogetherActive = false,
        listenTogetherController = false,
        interruptedUsbPlayback = false
    )

    @Test
    fun `immediate wired loss requires active system playback and a speaker destination`() {
        val wired = AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        val usb = AudioDeviceInfo.TYPE_USB_DEVICE
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertTrue(pausesForImmediateOutputDisconnect(playing, wired, speaker))
        assertTrue(pausesForImmediateOutputDisconnect(playing, wired, null))
        assertFalse(pausesForImmediateOutputDisconnect(playing, null, speaker))
        assertFalse(pausesForImmediateOutputDisconnect(playing, speaker, null))
        assertFalse(pausesForImmediateOutputDisconnect(playing.copy(isPlaying = false), wired, speaker))
        assertFalse(pausesForImmediateOutputDisconnect(playing.copy(usbExclusiveEnabled = true), usb, speaker))
        assertFalse(pausesForImmediateOutputDisconnect(playing, wired, usb))
    }

    @Test
    fun `USB route jitter distinguishes topology and physical identity`() {
        val usb = AudioDevice("DAC", AudioDeviceInfo.TYPE_USB_DEVICE, Icons.Default.SpeakerGroup)
        val otherUsb = usb.copy(name = "replacement")
        val speaker = AudioDevice("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, Icons.Default.SpeakerGroup)
        val state = playing.copy(usbExclusiveEnabled = true)
        assertFalse(treatsAsUsbRouteJitter(playing, usb, otherUsb))
        assertFalse(treatsAsUsbRouteJitter(state, speaker, speaker))
        assertFalse(treatsAsUsbRouteJitter(state, usb, usb))
        assertTrue(treatsAsUsbRouteJitter(state, usb, otherUsb))
        assertTrue(treatsAsUsbRouteJitter(state, usb, speaker))
        assertTrue(treatsAsUsbRouteJitter(state, speaker, usb))
        assertFalse(treatsAsUsbRouteJitter(state, null, speaker))
    }

    @Test
    fun `Bluetooth confirmation only applies to an active disconnect`() {
        val bluetooth = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertTrue(pausesForBluetoothDisconnect(playing, bluetooth, speaker))
        assertTrue(pausesForBluetoothDisconnect(playing, bluetooth, null))
        assertFalse(pausesForBluetoothDisconnect(playing.copy(stopOnBluetoothDisconnect = false), bluetooth, speaker))
        assertFalse(pausesForBluetoothDisconnect(playing.copy(isPlaying = false), bluetooth, speaker))
        assertFalse(pausesForBluetoothDisconnect(playing, null, speaker))
        assertFalse(pausesForBluetoothDisconnect(playing, speaker, null))
        assertFalse(pausesForBluetoothDisconnect(playing, bluetooth, bluetooth))
    }

    @Test
    fun `output selection prefers exclusive USB then Bluetooth then wired`() {
        val usb = output(AudioDeviceInfo.TYPE_USB_DEVICE)
        val bluetooth = output(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val wired = output(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        val speaker = output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val devices = arrayOf(speaker, wired, bluetooth, usb)
        assertEquals(usb, preferredOutputDevice(devices, true))
        assertEquals(bluetooth, preferredOutputDevice(devices, false))
        assertEquals(wired, preferredOutputDevice(arrayOf(speaker, wired, usb), false))
        assertNull(preferredOutputDevice(arrayOf(speaker), true))
        assertNull(preferredOutputDevice(emptyArray(), false))
    }

    @Test
    fun `USB interruption resumes only on an exclusive USB route with playback intent`() {
        val state = playing.copy(usbExclusiveEnabled = true, interruptedUsbPlayback = true)
        val usb = AudioDeviceInfo.TYPE_USB_DEVICE
        assertTrue(state.canResumeInterruptedUsb(usb))
        assertFalse(state.copy(usbExclusiveEnabled = false).canResumeInterruptedUsb(usb))
        assertFalse(state.copy(allowMixedPlayback = true).canResumeInterruptedUsb(usb))
        assertFalse(state.copy(interruptedUsbPlayback = false).canResumeInterruptedUsb(usb))
        assertFalse(state.copy(resumeRequested = false).canResumeInterruptedUsb(usb))
        assertFalse(state.canResumeInterruptedUsb(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(state.playbackActiveForNoisyRoute())
        assertTrue(state.copy(isPlaying = false).playbackActiveForNoisyRoute())
        assertTrue(state.copy(isPlaying = false, playWhenReady = false).playbackActiveForNoisyRoute())
        assertFalse(state.copy(isPlaying = false, playWhenReady = false, resumeRequested = false)
            .playbackActiveForNoisyRoute())
        assertTrue(shouldIgnoreUsbSystemNoisyRoute(state, usb))
        assertFalse(shouldIgnoreUsbSystemNoisyRoute(state, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(shouldIgnoreUsbSystemNoisyRoute(state, null))
        assertFalse(shouldIgnoreUsbSystemNoisyRoute(playing, usb))
    }

    @Test
    fun `native ownership requires exclusive USB and an active player PCM session`() {
        val state = playing.copy(usbExclusiveEnabled = true)
        val native = UsbExclusiveNativeState(opened = true, source = "player_pcm")
        assertTrue(state.nativeOwnsUsbRoute(native))
        assertTrue(state.nativeOwnsUsbRoute(native.copy(opened = false, transitioning = true)))
        assertFalse(state.nativeOwnsUsbRoute(native.copy(opened = false)))
        assertFalse(state.nativeOwnsUsbRoute(native.copy(source = "tone")))
        assertFalse(playing.nativeOwnsUsbRoute(native))
        assertTrue(shouldReconfigureUsbPolicy(state, true))
        assertFalse(shouldReconfigureUsbPolicy(state, false))
        assertFalse(shouldReconfigureUsbPolicy(playing, true))
    }

    private fun output(type: Int): AudioDeviceInfo = mock(AudioDeviceInfo::class.java).also {
        `when`(it.type).thenReturn(type)
    }
}
