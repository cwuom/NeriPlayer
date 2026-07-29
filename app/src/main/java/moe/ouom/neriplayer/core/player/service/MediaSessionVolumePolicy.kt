package moe.ouom.neriplayer.core.player.service

import android.media.AudioManager
import androidx.media.VolumeProviderCompat
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState

internal fun shouldUseUsbExclusiveRemoteVolumeRouting(
    effectivePath: String,
    bitPerfect: Boolean
): Boolean {
    return effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB && !bitPerfect
}

internal fun usbExclusiveVolumeProviderMaxIndex(minVolume: Int, maxVolume: Int): Int {
    return (maxVolume - minVolume).coerceAtLeast(1)
}

internal fun usbExclusiveVolumeProviderCurrentIndex(
    currentVolume: Int,
    minVolume: Int,
    maxVolume: Int
): Int {
    val providerMax = usbExclusiveVolumeProviderMaxIndex(minVolume, maxVolume)
    return (currentVolume.coerceIn(minVolume, maxVolume) - minVolume)
        .coerceIn(0, providerMax)
}

internal fun usbExclusiveVolumeFractionFromProviderIndex(
    providerIndex: Int,
    providerMaxIndex: Int
): Float {
    val maxIndex = providerMaxIndex.coerceAtLeast(1)
    return (providerIndex.coerceIn(0, maxIndex).toFloat() / maxIndex.toFloat())
        .coerceIn(0f, 1f)
}

internal fun usbExclusiveVolumeProviderIndexFromFraction(
    volumeFraction: Float,
    providerMaxIndex: Int
): Int {
    val maxIndex = providerMaxIndex.coerceAtLeast(1)
    return (volumeFraction.coerceIn(0f, 1f) * maxIndex.toFloat())
        .toInt()
        .coerceIn(0, maxIndex)
}

internal fun adjustedUsbExclusiveVolumeProviderIndex(
    currentIndex: Int,
    providerMaxIndex: Int,
    direction: Int
): Int {
    val maxIndex = providerMaxIndex.coerceAtLeast(1)
    val current = currentIndex.coerceIn(0, maxIndex)
    return when (direction) {
        AudioManager.ADJUST_RAISE -> (current + 1).coerceAtMost(maxIndex)
        AudioManager.ADJUST_LOWER -> (current - 1).coerceAtLeast(0)
        AudioManager.ADJUST_MUTE -> 0
        AudioManager.ADJUST_UNMUTE -> if (current == 0) 1 else current
        AudioManager.ADJUST_TOGGLE_MUTE -> if (current == 0) 1 else 0
        else -> current
    }
}

internal class UsbExclusiveLockScreenVolumeProvider(
    maxVolume: Int,
    initialVolume: Int,
    private val onVolumeFractionChanged: (Float) -> Unit
) : VolumeProviderCompat(
    VOLUME_CONTROL_ABSOLUTE,
    maxVolume.coerceAtLeast(1),
    initialVolume.coerceIn(0, maxVolume.coerceAtLeast(1))
) {
    override fun onAdjustVolume(direction: Int) {
        updateVolume(
            adjustedUsbExclusiveVolumeProviderIndex(
                currentIndex = currentVolume,
                providerMaxIndex = maxVolume,
                direction = direction
            )
        )
    }

    override fun onSetVolumeTo(volume: Int) {
        updateVolume(volume)
    }

    private fun updateVolume(volume: Int) {
        val nextVolume = volume.coerceIn(0, maxVolume)
        if (nextVolume == currentVolume) return
        setCurrentVolume(nextVolume)
        onVolumeFractionChanged(
            usbExclusiveVolumeFractionFromProviderIndex(nextVolume, maxVolume)
        )
    }
}
