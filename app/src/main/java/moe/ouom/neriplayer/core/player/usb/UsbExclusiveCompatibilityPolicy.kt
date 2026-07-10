package moe.ouom.neriplayer.core.player.usb

import kotlin.math.abs
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig

private const val PARAMETER_EPSILON = 0.0001f

fun PlaybackSoundConfig.requiresSystemAudioProcessor(
    listenTogetherSyncRate: Float = 1f
): Boolean {
    return equalizerEnabled ||
        loudnessGainMb > 0 ||
        abs(speed - 1f) > PARAMETER_EPSILON ||
        abs(pitch - 1f) > PARAMETER_EPSILON ||
        abs(listenTogetherSyncRate - 1f) > PARAMETER_EPSILON
}
