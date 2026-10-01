package moe.ouom.neriplayer.core.player.policy.usb.quality

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryState
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryDecision

private val RETAINED_QUALITY_COUNTER_REASONS = setOf(
    "armed_pcm_starvation", "minor_pcm_starvation_with_signal", "persistent_pcm_starvation",
    "awaiting_pcm_starvation_sample", "armed_player_drop", "minor_player_drop_with_signal",
    "awaiting_player_drop_sample"
)

internal fun ignoreUsbQuality(
    snapshot: UsbExclusiveAudioQualityRecoveryState,
    reason: String,
    debug: String
): UsbExclusiveAudioQualityRecoveryDecision {
    val retainedSnapshot = if (reason in RETAINED_QUALITY_COUNTER_REASONS) {
        snapshot
    } else {
        snapshot.copy(
            consecutivePlayerDropTicks = 0,
            consecutivePcmStarvationTicks = 0
        )
    }
    return UsbExclusiveAudioQualityRecoveryDecision(
        shouldRecover = false,
        state = retainedSnapshot,
        reason = reason,
        debug = debug
    )
}

internal fun recoverUsbQuality(
    snapshot: UsbExclusiveAudioQualityRecoveryState,
    reason: String,
    debug: String
): UsbExclusiveAudioQualityRecoveryDecision {
    return UsbExclusiveAudioQualityRecoveryDecision(
        shouldRecover = true,
        state = snapshot,
        reason = reason,
        debug = debug
    )
}
