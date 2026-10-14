package moe.ouom.neriplayer.core.player.audio.route

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.AudioDevice
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class AudioDeviceRouteOwnerTest {
    @Test
    fun `physical detach gate forces fallback without changing active native route`() = runTest {
        val port = RecordingPort(usb)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker
        port.state = port.state.copy(usbExclusiveEnabled = true)
        port.gate = "usb_device_detached"

        owner.onDeviceChange(usbTopologyChanged = true, outputDeviceRemoved = true)

        assertEquals(listOf("forceFallback:usb_device_detached"), port.events.filter { it.startsWith("forceFallback") })
        assertFalse(port.events.any { it.startsWith("applyPolicy") })
    }

    @Test
    fun `USB route jitter defers native reopen and restores playback`() = runTest {
        val port = RecordingPort(usb)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = usb.copy(name = "new DAC")
        port.state = port.state.copy(usbExclusiveEnabled = true)

        owner.onDeviceChange(usbTopologyChanged = true)

        assertTrue(port.events.contains("deferOpen:route_jitter"))
        assertTrue(port.events.contains("restore:usb_exclusive_route_jitter"))
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `confirmed Bluetooth disconnect pauses only after samples`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker

        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)
        assertFalse(port.events.any { it.startsWith("pause:") })
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.contains("pause:bluetooth_disconnect_confirmed:device_changed_to_${speaker.type}"))
    }

    @Test
    fun `Bluetooth device removal mutes output immediately before confirming the disconnect`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker

        requireNotNull(port.registeredCallback).onAudioDevicesRemoved(
            arrayOf(audioInfo(bluetooth.type, true))
        )

        assertTrue(port.events.contains("suppress:bluetooth_disconnect_pending"))
        assertFalse(port.events.any { it.startsWith("pause:") })
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.contains("pause:bluetooth_disconnect_confirmed:device_changed_to_${speaker.type}"))
        assertFalse(port.events.any { it.startsWith("restore:") })
    }

    @Test
    fun `Bluetooth reattachment restores the callback mute and cancels pending confirmation`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        val callback = requireNotNull(port.registeredCallback)
        val headset = audioInfo(bluetooth.type, true)
        port.device = speaker
        callback.onAudioDevicesRemoved(arrayOf(headset))
        assertTrue(port.events.contains("suppress:bluetooth_disconnect_pending"))

        port.device = bluetooth
        callback.onAudioDevicesAdded(arrayOf(headset))

        assertTrue(port.events.contains("restore:device_changed_to_${bluetooth.type}"))
        advanceTimeBy(10_000L)
        runCurrent()
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `release cancels pending Bluetooth confirmation`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker
        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)

        owner.release()
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(1, port.unregisteredCallbacks)
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `native USB session owns noisy route broadcast`() = runTest {
        val port = RecordingPort(usb)
        port.state = port.state.copy(usbExclusiveEnabled = true, isPlaying = true)
        port.native = UsbExclusiveNativeState(opened = true, source = "player_pcm")
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()

        assertFalse(owner.onAudioBecomingNoisy())
        assertFalse(port.events.any { it.startsWith("stopUsb:") })
    }

    @Test
    fun `USB noisy event stops pending exclusive playback`() = runTest {
        val port = RecordingPort(usb)
        port.state = port.state.copy(usbExclusiveEnabled = true, playWhenReady = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()

        assertTrue(owner.onAudioBecomingNoisy())
        assertTrue(port.events.contains("stopUsb:usb_audio_route_noisy"))
    }

    @Test
    fun `speaker noisy event pauses active system playback immediately`() = runTest {
        val port = RecordingPort(speaker)
        port.state = port.state.copy(isPlaying = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()

        assertTrue(owner.onAudioBecomingNoisy())
        assertTrue(port.events.contains("pause:becoming_noisy_immediate"))
    }

    @Test
    fun `wired output removal pauses active system playback`() = runTest {
        val port = RecordingPort(wired)
        port.state = port.state.copy(isPlaying = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker

        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)

        assertTrue(port.events.contains("pause:immediate_output_disconnect"))
    }

    @Test
    fun `wired disconnect stops a pending next track before it can reach the speaker`() = runTest {
        val port = RecordingPort(wired)
        port.state = port.state.copy(resumeRequested = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker

        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)

        assertTrue(port.events.contains("suppress:immediate_output_disconnect"))
        assertTrue(port.events.contains("pause:immediate_output_disconnect"))
    }

    @Test
    fun `noisy broadcast stops buffering system playback`() = runTest {
        val port = RecordingPort(wired)
        port.state = port.state.copy(playWhenReady = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()

        assertTrue(owner.onAudioBecomingNoisy())
        assertTrue(port.events.contains("pause:becoming_noisy_immediate"))
    }

    @Test
    fun `Bluetooth disconnect confirmation continues across buffering`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, playWhenReady = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker
        assertTrue(owner.onAudioBecomingNoisy())
        port.state = port.state.copy(isPlaying = false)

        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.contains("pause:bluetooth_disconnect_confirmed:becoming_noisy"))
        assertFalse(port.events.any { it.startsWith("restore:") })
    }

    @Test
    fun `Bluetooth disconnect confirms while a next track is still resolving`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(resumeRequested = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker

        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.contains("pause:bluetooth_disconnect_confirmed:device_changed_to_${speaker.type}"))
    }

    @Test
    fun `transient Bluetooth loss while buffering restores output without pausing`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(playWhenReady = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        assertTrue(owner.onAudioBecomingNoisy())

        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.contains("restore:bluetooth_disconnect_transient:becoming_noisy"))
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `idle system playback ignores disconnect broadcasts and device changes`() = runTest {
        for (device in listOf(wired, bluetooth)) {
            val port = RecordingPort(device)
            port.state = port.state.copy(stopOnBluetoothDisconnect = true)
            val owner = AudioDeviceRouteOwner(backgroundScope, port)
            owner.register()
            port.device = speaker

            assertFalse(owner.onAudioBecomingNoisy())
            owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)
            advanceTimeBy(10_000L)
            runCurrent()

            assertFalse(port.events.any { it.startsWith("pause:") || it.startsWith("suppress:") })
        }
    }

    @Test
    fun `Bluetooth confirmation cancels when playback stops`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker
        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)
        port.state = port.state.copy(isPlaying = false)

        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.any { it.startsWith("restore:bluetooth_disconnect_canceled") })
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `active native route ignores Android device callbacks`() = runTest {
        val port = RecordingPort(usb)
        port.state = port.state.copy(usbExclusiveEnabled = true)
        port.native = UsbExclusiveNativeState(opened = true, source = "player_pcm")
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker

        owner.onDeviceChange(usbTopologyChanged = true)

        assertFalse(port.events.any { it.startsWith("applyPolicy") })
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `reattached USB route resumes an interrupted playback request`() = runTest {
        val port = RecordingPort(speaker)
        port.state = port.state.copy(
            usbExclusiveEnabled = true, interruptedUsbPlayback = true, resumeRequested = true
        )
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = usb

        owner.onDeviceChange(usbTopologyChanged = true)

        assertTrue(port.events.contains("applyPolicy:true"))
        assertTrue(port.events.contains("resumeUsb:audio_device_added"))
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `idle USB noisy event does not stop playback`() = runTest {
        val port = RecordingPort(usb)
        port.state = port.state.copy(usbExclusiveEnabled = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()

        assertFalse(owner.onAudioBecomingNoisy())
        assertFalse(port.events.any { it.startsWith("stopUsb:") })
    }

    @Test
    fun `Bluetooth noisy event waits for disconnect confirmation`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker

        assertTrue(owner.onAudioBecomingNoisy())
        assertFalse(port.events.any { it.startsWith("pause:") })
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(port.events.contains("pause:bluetooth_disconnect_confirmed:becoming_noisy"))
    }

    @Test
    fun `Bluetooth noisy event stays muted until Android moves media to the speaker`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()

        assertTrue(owner.onAudioBecomingNoisy())
        advanceTimeBy(1_100L)
        runCurrent()
        assertFalse(port.events.any { it.startsWith("restore:") })

        port.device = speaker
        requireNotNull(port.registeredCallback).onAudioDevicesRemoved(arrayOf(audioInfo(bluetooth.type, true)))
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.contains("pause:bluetooth_disconnect_confirmed:device_changed_to_${speaker.type}"))
        assertFalse(port.events.any { it.startsWith("restore:") })
    }

    @Test
    fun `noisy reroute without a device callback still pauses instead of restoring on the speaker`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()

        assertTrue(owner.onAudioBecomingNoisy())
        advanceTimeBy(1_000L)
        port.device = speaker
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.contains("pause:bluetooth_disconnect_confirmed:becoming_noisy"))
        assertFalse(port.events.any { it.startsWith("restore:") })
    }

    @Test
    fun `confirmed Bluetooth disconnect does not wait for the full noisy window`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker

        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)
        advanceTimeBy(901L)
        runCurrent()

        assertTrue(port.events.contains("pause:bluetooth_disconnect_confirmed:device_changed_to_${speaker.type}"))
    }

    @Test
    fun `registered device callback filters input devices and observes USB and headset outputs`() = runTest {
        val port = RecordingPort(speaker)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        val callback = requireNotNull(port.registeredCallback)
        val usbOutput = audioInfo(AudioDeviceInfo.TYPE_USB_DEVICE, true)
        val wiredOutput = audioInfo(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, true)
        val usbInput = audioInfo(AudioDeviceInfo.TYPE_USB_DEVICE, false)

        callback.onAudioDevicesAdded(arrayOf(usbInput, usbOutput))
        callback.onAudioDevicesRemoved(arrayOf(wiredOutput))
        callback.onAudioDevicesAdded(null)

        assertTrue(port.events.count { it.startsWith("applyPolicy") } >= 3)
        assertTrue(port.events.contains("clearFallback"))
    }

    @Test
    fun `Bluetooth confirmation restores playback when output reroutes to wired`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker
        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)
        port.device = wired

        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.contains("restore:bluetooth_disconnect_rerouted:${wired.type}"))
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `transient Bluetooth callback does not confirm disconnect`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, stopOnBluetoothDisconnect = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker
        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)
        port.device = bluetooth

        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(port.events.any { it.startsWith("restore:bluetooth_disconnect_transient") })
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `listener output loss suppresses playback before Android route policy`() = runTest {
        val port = RecordingPort(bluetooth)
        port.state = port.state.copy(isPlaying = true, listenTogetherActive = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()
        port.device = speaker

        owner.onDeviceChange(usbTopologyChanged = false, outputDeviceRemoved = true)

        assertTrue(port.events.contains("suppress:listen_together_output_disconnect"))
        assertFalse(port.events.any { it.startsWith("pause:") })
    }

    @Test
    fun `mixed USB route ignores noisy broadcast without native or system pause`() = runTest {
        val port = RecordingPort(usb)
        port.state = port.state.copy(isPlaying = true, usbExclusiveEnabled = true, allowMixedPlayback = true)
        val owner = AudioDeviceRouteOwner(backgroundScope, port)
        owner.register()

        assertFalse(owner.onAudioBecomingNoisy())
        assertFalse(port.events.any { it.startsWith("pause:") || it.startsWith("stopUsb:") })
    }

    private fun audioInfo(type: Int, sink: Boolean): AudioDeviceInfo = mock(AudioDeviceInfo::class.java).also {
        `when`(it.type).thenReturn(type)
        `when`(it.isSink).thenReturn(sink)
    }

    private class RecordingPort(var device: AudioDevice) : AudioDeviceRoutePort {
        var state = AudioDeviceRouteSnapshot(true, false, false, false, false, false, false, false, false, false)
        var gate: String? = null
        var native = UsbExclusiveNativeState()
        var unregisteredCallbacks = 0
        var registeredCallback: AudioDeviceCallback? = null
        val events = mutableListOf<String>()

        override fun snapshot(): AudioDeviceRouteSnapshot = state
        override fun ensureInitialized() = Unit
        override fun readCurrentDevice(): AudioDevice = device
        override fun publishCurrentDevice(device: AudioDevice) { events += "publish:${device.name}" }
        override fun registerCallback(callback: AudioDeviceCallback) { registeredCallback = callback }
        override fun unregisterCallback(callback: AudioDeviceCallback) { unregisteredCallbacks += 1 }
        override fun logDeviceCallback(reason: String, devices: Array<out AudioDeviceInfo>?) = Unit
        override fun logSnapshot(reason: String) = Unit
        override fun nativeState(): UsbExclusiveNativeState = native
        override fun nativeOpenGateReason(): String? = gate
        override fun forceSystemFallback(reason: String) { events += "forceFallback:$reason" }
        override fun clearForcedSystemFallback() { events += "clearFallback" }
        override fun deferNativeOpen(reason: String, delayMs: Long) { events += "deferOpen:$reason" }
        override fun muteListenTogetherListener(): Boolean = false
        override fun suppressPlayback(reason: String) { events += "suppress:$reason" }
        override fun pausePlayback(reason: String) { events += "pause:$reason" }
        override fun stopUsbPlayback(reason: String) { events += "stopUsb:$reason" }
        override fun applyUsbPolicy(reconfigureSink: Boolean) { events += "applyPolicy:$reconfigureSink" }
        override fun scheduleUsbResumeAfterAttach(reason: String) { events += "resumeUsb:$reason" }
        override fun restorePlayback(reason: String) { events += "restore:$reason" }
    }

    private companion object {
        val usb = AudioDevice("DAC", AudioDeviceInfo.TYPE_USB_DEVICE)
        val speaker = AudioDevice("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val bluetooth = AudioDevice("headset", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val wired = AudioDevice("wired", AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
    }
}
