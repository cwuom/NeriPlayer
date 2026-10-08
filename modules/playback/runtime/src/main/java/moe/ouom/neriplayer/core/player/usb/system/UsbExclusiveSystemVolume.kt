package moe.ouom.neriplayer.core.player.usb.system

import kotlin.math.pow
import kotlin.math.roundToLong
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_BIT_PERFECT

internal const val USB_EXCLUSIVE_SYSTEM_VOLUME_EXPONENT = 2.0

/** 比特完美不做任何缩放，只保留播放器显式静音（路由静音、淡出结束） */
internal fun usbExclusiveEffectiveNativeVolume(
    playerVolume: Float,
    systemVolumeFraction: Float,
    bitPerfect: Boolean = DEFAULT_USB_EXCLUSIVE_BIT_PERFECT
): Float {
    val playerGain = playerVolume.coerceIn(0f, 1f)
    if (bitPerfect) return if (playerGain <= 0f) 0f else 1f
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

/**
 * 按 2^(n-1) 缩放并取最近整数，与解码器 int→float 的约定一致：
 * 整数源解出的浮点样本能逐位还原，按 32767/8388607 缩放或截断会让响亮样本差 1 LSB
 */
internal fun usbExclusiveFloatToPcmInt(sample: Float, bits: Int): Int {
    val fullScale = 1L shl (bits - 1)
    val normalized = usbExclusiveFloatSampleForNativePipeline(sample).toDouble()
    return (normalized * fullScale.toDouble()).roundToLong()
        .coerceIn(-fullScale, fullScale - 1)
        .toInt()
}
