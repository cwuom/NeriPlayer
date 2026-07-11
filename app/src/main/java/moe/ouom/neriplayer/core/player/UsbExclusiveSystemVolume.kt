package moe.ouom.neriplayer.core.player

import kotlin.math.pow

internal const val USB_EXCLUSIVE_SYSTEM_VOLUME_EXPONENT = 2.0

internal fun usbExclusiveEffectiveNativeVolume(
    playerVolume: Float,
    systemVolumeFraction: Float
): Float {
    val playerGain = playerVolume.coerceIn(0f, 1f)
    return playerGain * usbExclusiveExponentialSystemVolumeGain(systemVolumeFraction)
}

internal fun usbExclusiveExponentialSystemVolumeGain(volumeFraction: Float): Float {
    val normalized = volumeFraction.coerceIn(0f, 1f)
    return normalized.toDouble()
        .pow(USB_EXCLUSIVE_SYSTEM_VOLUME_EXPONENT)
        .toFloat()
        .coerceIn(0f, 1f)
}
