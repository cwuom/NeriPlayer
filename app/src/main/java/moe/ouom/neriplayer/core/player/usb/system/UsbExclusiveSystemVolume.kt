package moe.ouom.neriplayer.core.player.usb.system

internal fun usbExclusiveEffectiveNativeVolume(
    playerVolume: Float,
    systemVolumeFraction: Float
): Float {
    val playerGain = playerVolume.coerceIn(0f, 1f)
    return playerGain * usbExclusiveExponentialSystemVolumeGain(systemVolumeFraction)
}

internal fun usbExclusiveExponentialSystemVolumeGain(volumeFraction: Float): Float {
    return volumeFraction.coerceIn(0f, 1f)
}
