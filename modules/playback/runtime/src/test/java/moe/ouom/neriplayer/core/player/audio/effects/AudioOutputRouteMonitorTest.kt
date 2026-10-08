package moe.ouom.neriplayer.core.player.audio.effects

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AudioOutputRouteMonitorTest {
    @Test
    fun `media stream device changes override the connected device guess`() {
        var connected = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_DEVICE
        )
        val manager = mock(AudioManager::class.java)
        `when`(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenAnswer { connected.map(::device).toTypedArray() }
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(manager)
        val routes = mutableListOf<AudioOutputRoute>()
        val monitor = AudioOutputRouteMonitor(context) { routes += it }

        monitor.start()
        assertEquals(AudioOutputRoute.BLUETOOTH, routes.last())
        val receiver = registeredReceiver(context)

        receiver.onReceive(context, streamDevicesIntent(AudioManager.STREAM_MUSIC, 0x4000))
        assertEquals(AudioOutputRoute.USB, routes.last())

        val refreshes = routes.size
        receiver.onReceive(context, streamDevicesIntent(AudioManager.STREAM_RING, 0x2))
        assertEquals(refreshes, routes.size)

        connected = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        monitor.refresh()
        assertEquals(AudioOutputRoute.BLUETOOTH, routes.last())

        monitor.stop()
        verify(context).unregisterReceiver(receiver)
    }

    @Test
    fun `stream device masks decode in media routing priority`() {
        assertEquals(
            listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            audioOutputTypesForStreamDevices(0x2 or 0x100)
        )
        assertEquals(listOf(AudioDeviceInfo.TYPE_USB_HEADSET), audioOutputTypesForStreamDevices(0x4000000))
        assertEquals(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), audioOutputTypesForStreamDevices(0x8))
        assertEquals(emptyList<Int>(), audioOutputTypesForStreamDevices(0))
    }

    private fun registeredReceiver(context: Context): BroadcastReceiver =
        mockingDetails(context).invocations.single { it.method.name == "registerReceiver" }.getArgument(0)

    private fun device(type: Int): AudioDeviceInfo =
        mock(AudioDeviceInfo::class.java).also { `when`(it.type).thenReturn(type) }

    private fun streamDevicesIntent(streamType: Int, devices: Int): Intent =
        mock(Intent::class.java).also {
            `when`(it.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)).thenReturn(streamType)
            `when`(it.getIntExtra("android.media.EXTRA_VOLUME_STREAM_DEVICES", 0)).thenReturn(devices)
        }
}
