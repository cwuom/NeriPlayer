package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveErrorCode
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveFeedbackMode
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRuntimeMetrics

internal data class UsbExclusiveSameHandleRecoveryDecision(
    val shouldAttempt: Boolean,
    val attempt: Int,
    val limit: Int,
    val reason: String
)

internal class UsbExclusiveSameHandleRecoveryPolicy(
    private val maxAttemptsPerRecoveryWindow: Int = DEFAULT_MAX_ATTEMPTS,
    private val stableGraceMs: Long = DEFAULT_STABLE_GRACE_MS
) {
    private var recoveryKey: String? = null
    private var attempts = 0
    private var stableSinceMs: Long? = null

    init {
        require(maxAttemptsPerRecoveryWindow > 0) {
            "same handle recovery attempts must be positive"
        }
        require(stableGraceMs >= 0L) {
            "same handle recovery grace must be non-negative"
        }
    }

    @Synchronized
    fun evaluate(
        handle: Long,
        metrics: UsbExclusiveRuntimeMetrics,
        nowMs: Long
    ): UsbExclusiveSameHandleRecoveryDecision {
        val key = metrics.recoveryKey(handle)
        if (key != recoveryKey) {
            recoveryKey = key
            attempts = 0
            stableSinceMs = null
        }
        updateStableBudget(metrics, nowMs)
        val rejection = rejectionReason(handle, metrics)
        if (rejection != null) {
            return decision(shouldAttempt = false, reason = rejection)
        }
        if (attempts >= maxAttemptsPerRecoveryWindow) {
            return decision(shouldAttempt = false, reason = "attempt_budget_exhausted")
        }
        attempts += 1
        stableSinceMs = null
        return decision(shouldAttempt = true, reason = "rearmable_transfer_stall")
    }

    private fun updateStableBudget(metrics: UsbExclusiveRuntimeMetrics, nowMs: Long) {
        if (!isStableForUsbExclusiveRecoveryReset(metrics)) {
            stableSinceMs = null
            return
        }
        val stableSince = stableSinceMs
        if (stableSince == null) {
            stableSinceMs = nowMs
            return
        }
        if (nowMs - stableSince < stableGraceMs) return
        attempts = 0
        stableSinceMs = nowMs
    }

    private fun rejectionReason(
        handle: Long,
        metrics: UsbExclusiveRuntimeMetrics
    ): String? {
        if (handle == 0L) return "invalid_handle"
        if (!metrics.reportValid || metrics.reportVersion < 2) return "invalid_report"
        if (metrics.source != "player_pcm") return "not_player_pcm"
        if (metrics.deviceOnline != true) return "device_offline"
        if (metrics.transportFailed != true || metrics.terminalFailure != true) {
            return "transport_not_terminal"
        }
        if (metrics.running != false || metrics.transportRunning != false) {
            return "transport_still_running"
        }
        if ((metrics.inFlightTransfers ?: 0) <= 0) return "no_in_flight_transfer"
        if (metrics.isNoFeedbackClockedEndpoint() &&
            metrics.errorCode in NO_FEEDBACK_FRESH_OPEN_ERRORS
        ) {
            return "no_feedback_endpoint_requires_fresh_open"
        }
        return when (metrics.errorCode) {
            UsbExclusiveErrorCode.TransferFirstCompletionTimeout,
            UsbExclusiveErrorCode.TransferCompletionStalled -> null
            else -> "error_not_rearmable"
        }
    }

    private fun decision(
        shouldAttempt: Boolean,
        reason: String
    ): UsbExclusiveSameHandleRecoveryDecision {
        return UsbExclusiveSameHandleRecoveryDecision(
            shouldAttempt = shouldAttempt,
            attempt = attempts,
            limit = maxAttemptsPerRecoveryWindow,
            reason = reason
        )
    }

    private fun UsbExclusiveRuntimeMetrics.recoveryKey(handle: Long): String {
        return buildString {
            append(handle)
            append('|')
            append(candidateId ?: "unknown_candidate")
            append('|')
            append(recoveryEpoch ?: -1L)
        }
    }

    private fun UsbExclusiveRuntimeMetrics.isNoFeedbackClockedEndpoint(): Boolean {
        if (feedbackMode != UsbExclusiveFeedbackMode.Disabled) return false
        return when (syncType?.trim()?.lowercase()) {
            "adaptive", "synchronous" -> true
            else -> false
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 2
        const val DEFAULT_STABLE_GRACE_MS = 10_000L

        private val NO_FEEDBACK_FRESH_OPEN_ERRORS = setOf(
            UsbExclusiveErrorCode.TransferFirstCompletionTimeout,
            UsbExclusiveErrorCode.TransferCompletionStalled
        )
    }
}
