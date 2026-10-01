package moe.ouom.neriplayer.core.player.service.usb

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemVolumeBridge

private const val DEFAULT_MAX_MEDIA_VOLUME = 100

internal class AndroidUsbExclusiveVolumeRoutingPort(
    private val context: Context,
    private val mediaSession: () -> MediaSession?,
    private val audioAttributes: AudioAttributes,
) : UsbExclusiveVolumeRoutingPort {
    override fun createProvider(): VolumeProvider {
        val (minVolume, maxVolume, currentVolume) = readMediaVolume()
        return UsbExclusiveLockScreenVolumeProvider(
            maxVolume = usbExclusiveVolumeProviderMaxIndex(minVolume, maxVolume),
            initialVolume = usbExclusiveVolumeProviderCurrentIndex(currentVolume, minVolume, maxVolume),
            onVolumeFractionChanged = UsbExclusiveSystemVolumeBridge::updateSessionVolumeFraction,
        )
    }

    override fun setRemote(provider: VolumeProvider) {
        checkNotNull(mediaSession()).setPlaybackToRemote(provider)
    }

    override fun setLocal() {
        mediaSession()?.setPlaybackToLocal(audioAttributes)
    }

    override fun hasSession(): Boolean = mediaSession() != null

    override fun updateSessionFraction(fraction: Float) =
        UsbExclusiveSystemVolumeBridge.updateSessionVolumeFraction(fraction)

    override fun clearSessionFraction() = UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()

    private fun readMediaVolume(): Triple<Int, Int, Int> = runCatching {
        val audioManager = mediaAudioManager()
        val min = minimumMediaVolume(audioManager)
        val max = maximumMediaVolume(audioManager)
        Triple(min, max, currentMediaVolume(audioManager, max))
    }.getOrElse { error ->
        NPLogger.w("NERI-APS", "failed to read media volume for USB volume routing", error)
        Triple(0, DEFAULT_MAX_MEDIA_VOLUME, DEFAULT_MAX_MEDIA_VOLUME)
    }

    private fun mediaAudioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun minimumMediaVolume(audioManager: AudioManager?): Int =
        audioManager?.getStreamMinVolume(AudioManager.STREAM_MUSIC) ?: 0

    private fun maximumMediaVolume(audioManager: AudioManager?): Int =
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: DEFAULT_MAX_MEDIA_VOLUME

    private fun currentMediaVolume(audioManager: AudioManager?, max: Int): Int =
        audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: max
}
