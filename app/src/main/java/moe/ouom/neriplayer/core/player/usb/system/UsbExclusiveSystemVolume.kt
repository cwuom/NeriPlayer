package moe.ouom.neriplayer.core.player.usb.system

import kotlin.math.pow

internal const val USB_EXCLUSIVE_SYSTEM_VOLUME_EXPONENT = 2.0

internal fun usbExclusiveEffectiveNativeVolume(
    playerVolume: Float,
    systemVolumeFraction: Float
): Float {
    val playerGain = playerVolume.coerceIn(0f, 1f)
    return playerGain * usbExclusiveSystemVolumeGain(systemVolumeFraction)
}

internal fun usbExclusiveSystemVolumeGain(volumeFraction: Float): Float {
    val normalized = volumeFraction.coerceIn(0f, 1f)
    return normalized.toDouble()
        .pow(USB_EXCLUSIVE_SYSTEM_VOLUME_EXPONENT)
        .toFloat()
        .coerceIn(0f, 1f)
}

internal fun usbExclusiveFloatSampleForNativePipeline(sample: Float): Float {
    return if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
}
