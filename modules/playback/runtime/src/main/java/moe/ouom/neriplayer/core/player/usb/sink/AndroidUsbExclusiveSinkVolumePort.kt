@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb.sink

import androidx.media3.exoplayer.audio.AudioSink
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeBridge

internal class AndroidUsbExclusiveSinkVolumePort(
    private val fallbackSink: AudioSink
) : UsbExclusiveSinkVolumePort {
    override fun bitPerfect(): Boolean = PlayerManager.usbExclusivePreferences.bitPerfect

    override fun setNativeBitPerfect(handle: Long, enabled: Boolean) {
        UsbExclusiveNativeBridge.setPlayerBitPerfect(handle, enabled)
    }

    override fun setNativeVolume(handle: Long, volume: Float) {
        UsbExclusiveSessionController.setPlayerVolume(handle, volume)
    }

    override fun setFallbackVolume(volume: Float) {
        fallbackSink.setVolume(volume)
    }

    override fun publishVolume(volume: Float) {
        UsbExclusiveAudioPathTracker.updateVolume(volume)
    }

    override fun hasHardwareVolume(handle: Long): Boolean = UsbExclusiveNativeBridge.hasHardwareVolume(handle)

    override fun setHardwareVolume(handle: Long, fraction: Float) {
        UsbExclusiveNativeBridge.setHardwareVolume(handle, fraction)
    }

    override fun publishHardwareVolume(available: Boolean) {
        UsbExclusiveAudioPathTracker.updateHardwareVolume(available)
    }
}
