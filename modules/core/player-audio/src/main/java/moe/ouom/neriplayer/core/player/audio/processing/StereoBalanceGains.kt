package moe.ouom.neriplayer.core.player.audio.processing

import moe.ouom.neriplayer.data.model.playback.normalizePlaybackVolumeBalance

internal data class StereoBalanceGains(
    val left: Float,
    val right: Float
) {
    val isCentered: Boolean
        get() = left == 1f && right == 1f
}

internal fun stereoBalanceGains(balance: Float): StereoBalanceGains {
    val normalized = normalizePlaybackVolumeBalance(balance)
    return StereoBalanceGains(
        left = if (normalized > 0f) 1f - normalized else 1f,
        right = if (normalized < 0f) 1f + normalized else 1f
    )
}
