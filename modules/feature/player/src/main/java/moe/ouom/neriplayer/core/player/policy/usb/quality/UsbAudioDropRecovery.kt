package moe.ouom.neriplayer.core.player.policy.usb.quality

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryState
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryDecision
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import kotlin.math.max

private const val PLAYER_DROPPED_RECOVERY_TICKS = 2
private const val PLAYER_DROPPED_LARGE_GAP_MS = 40L

internal fun evaluateUsbPlayerDrop(
    previous: UsbExclusiveAudioQualityRecoveryState,
    snapshot: UsbExclusiveAudioQualityRecoveryState,
    metrics: UsbExclusiveRuntimeMetrics,
    stablePcmWindow: Boolean
): UsbExclusiveAudioQualityRecoveryDecision? {
    val droppedDelta = max(0L, snapshot.playerDroppedBytes - previous.playerDroppedBytes)
    if (droppedDelta <= 0L) return null
    if (!stablePcmWindow) {
        return ignoreUsbQuality(
            snapshot = snapshot,
            reason = "startup_player_drop",
            debug = "droppedDelta=$droppedDelta completedTransfers=${snapshot.completedTransfers}"
        )
    }
    val nextTicks = (previous.consecutivePlayerDropTicks + 1)
        .coerceAtMost(PLAYER_DROPPED_RECOVERY_TICKS)
    val armedSnapshot = snapshot.copy(consecutivePlayerDropTicks = nextTicks)
    val largeGapBytes = largeDroppedGapBytes(metrics)
    val largeDrop = largeGapBytes > 0L && droppedDelta >= largeGapBytes
    val signalDelta = max(0L, snapshot.playerSignalBytes - previous.playerSignalBytes)
    if (isMinorUsbQualityGap(largeDrop, nextTicks, PLAYER_DROPPED_RECOVERY_TICKS, signalDelta, metrics)) {
        return ignoreUsbQuality(
            snapshot = armedSnapshot,
            reason = "minor_player_drop_with_signal",
            debug = "droppedDelta=$droppedDelta signalDelta=$signalDelta " +
                "peak=${metrics.bestOutputPeak()} ticks=$nextTicks threshold=$largeGapBytes"
        )
    }
    if (nextTicks >= PLAYER_DROPPED_RECOVERY_TICKS || largeDrop) {
        return recoverUsbQuality(
            snapshot = armedSnapshot,
            reason = "player_pcm_dropped",
            debug = "droppedDelta=$droppedDelta ticks=$nextTicks " +
                "largeDrop=$largeDrop threshold=$largeGapBytes " +
                "completedTransfers=${snapshot.completedTransfers}"
        )
    }
    return ignoreUsbQuality(
        snapshot = armedSnapshot,
        reason = "armed_player_drop",
        debug = "droppedDelta=$droppedDelta ticks=$nextTicks"
    )
}

private fun largeDroppedGapBytes(metrics: UsbExclusiveRuntimeMetrics): Long =
    usbQualityGapBytes(metrics, PLAYER_DROPPED_LARGE_GAP_MS)
