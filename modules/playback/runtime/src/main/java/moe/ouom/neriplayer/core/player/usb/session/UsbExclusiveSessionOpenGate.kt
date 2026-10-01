package moe.ouom.neriplayer.core.player.usb.session

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusivePendingReopenGate
import moe.ouom.neriplayer.core.player.policy.usb.isNativeCloseInFlightUsbExclusiveOpenGate
import moe.ouom.neriplayer.core.player.policy.usb.isUsbDeviceDetachOpenGate
import moe.ouom.neriplayer.core.player.policy.usb.shouldHandleUsbAudioAttachAfterDetach
import moe.ouom.neriplayer.core.player.policy.usb.shouldIgnoreStaleUsbDeviceDetachOpenBlock
import moe.ouom.neriplayer.core.player.policy.usb.shouldPreserveUsbDeviceDetachOpenBlock
import moe.ouom.neriplayer.core.player.usb.transport.requiresFreshNativeOpen
import moe.ouom.neriplayer.core.player.usb.transport.usbExclusiveErrorCode

internal class UsbExclusiveSessionOpenGate {
    private val pendingReopen = UsbExclusivePendingReopenGate()
    @Volatile private var pendingBlock: PendingBlock? = null
    private var blockedUntilMs = 0L
    private var blockReason = ""
    private var freshOpenRequiredReason: String? = null
    private var deviceEventGeneration = 0L
    private var lastDetachGeneration = 0L
    private var lastAttachGeneration = 0L

    data class PendingBlock(val reason: String, val delayMs: Long)

    fun markDeviceDetached() {
        deviceEventGeneration += 1L
        lastDetachGeneration = deviceEventGeneration
    }

    fun handleDeviceAttached(
        hasAudioStreamingInterface: Boolean,
        matchesSelectedDevice: Boolean
    ): Boolean {
        if (!shouldHandleUsbAudioAttachAfterDetach(
                hasAudioStreamingInterface = hasAudioStreamingInterface,
                matchesSelectedDevice = matchesSelectedDevice,
                lastDetachGeneration = lastDetachGeneration,
                lastAttachGeneration = lastAttachGeneration
            )
        ) {
            return false
        }
        deviceEventGeneration += 1L
        lastAttachGeneration = deviceEventGeneration
        clearDetachDerivedBlocks()
        return true
    }

    private fun clearDetachDerivedBlocks() {
        if (isUsbDeviceDetachOpenGate(blockReason)) clearBlock()
        clearPendingDetachBlock()
    }

    private fun clearPendingDetachBlock() {
        pendingBlock = pendingBlock?.takeUnless { isUsbDeviceDetachOpenGate(it.reason) }
    }

    fun queueBlock(reason: String, delayMs: Long) {
        val current = pendingBlock
        if (current == null || delayMs >= current.delayMs) {
            pendingBlock = PendingBlock(reason, delayMs)
        }
    }

    fun takePendingBlock(): PendingBlock? = pendingBlock.also { pendingBlock = null }

    fun clearPendingBlock() {
        pendingBlock = null
    }

    fun block(
        reason: String,
        delayMs: Long,
        nowMs: Long,
        minimumDelayMs: Long = MIN_OPEN_INTERVAL_MS
    ): Boolean {
        if (shouldIgnoreStaleUsbDeviceDetachOpenBlock(
                incomingReason = reason,
                lastDetachGeneration = lastDetachGeneration,
                lastAttachGeneration = lastAttachGeneration
            )
        ) {
            return false
        }
        val oldRemainingMs = (blockedUntilMs - nowMs).coerceAtLeast(0L)
        if (oldRemainingMs > 0L && shouldPreserveUsbDeviceDetachOpenBlock(
                existingReason = blockReason,
                incomingReason = reason
            )
        ) {
            return false
        }
        val untilMs = nowMs + delayMs.coerceAtLeast(minimumDelayMs).coerceAtLeast(0L)
        if (untilMs <= blockedUntilMs) return false
        blockedUntilMs = untilMs
        blockReason = reason
        return true
    }

    fun error(nowMs: Long, nativeCloseInFlightCount: Int): String? {
        clearExpiredBlock(nowMs)
        if (nativeCloseInFlightCount == 0 &&
            isNativeCloseInFlightUsbExclusiveOpenGate(blockReason)
        ) {
            clearBlock()
        }
        val remainingBlockMs = blockedUntilMs - nowMs
        if (remainingBlockMs > 0L) {
            return "native_open_deferred:$blockReason remainingMs=$remainingBlockMs"
        }
        if (nativeCloseInFlightCount > 0) {
            return "native_open_deferred:native_close_in_flight count=$nativeCloseInFlightCount"
        }
        return null
    }

    fun clearCompletedNativeCloseGate(nativeCloseInFlightCount: Int) {
        if (nativeCloseInFlightCount == 0 &&
            isNativeCloseInFlightUsbExclusiveOpenGate(blockReason)
        ) {
            clearBlock()
        }
    }

    fun clearRecoverableUserActionBlock(): Boolean {
        if (!blockReason.isRecoverableUserActionBlock()) return false
        clearBlock()
        return true
    }

    fun recordNativeOpenFailure(reason: String, nowMs: Long) {
        val fuseMs = if (reason.isHighRiskNativeOpenFailure()) {
            FAILURE_FUSE_MS
        } else {
            TRANSIENT_FUSE_MS
        }
        block(reason, fuseMs, nowMs)
    }

    fun requireFreshOpen(reason: String) {
        freshOpenRequiredReason = reason
    }

    fun freshOpenReason(currentIsPlayerSession: Boolean): String? {
        if (!currentIsPlayerSession) freshOpenRequiredReason = null
        return freshOpenRequiredReason
    }

    fun markOpened() {
        clearBlock()
        freshOpenRequiredReason = null
    }

    fun requestReopenAfterClose(reason: String) {
        pendingReopen.request(reason)
    }

    fun takeReopenAfterClose(nativeCloseInFlightCount: Int): String? {
        return pendingReopen.takeIfNativeCloseComplete(nativeCloseInFlightCount)
    }

    fun reconfigurationReasonForReopen(reason: String): String =
        if (reason == "open_player_pcm_reconfigure") {
            "usb_exclusive_open_gate_retry_after_close"
        } else {
            "usb_exclusive_reopen_after_close:$reason"
        }

    fun shouldReopenAfterReconfigureClose(
        request: UsbExclusiveSessionResources.CloseRequest,
        playbackEnabled: () -> Boolean,
        transportActive: () -> Boolean
    ): Boolean {
        if (!isPlayerReconfigureClose(request)) return false
        if (!playbackEnabled()) return false
        return transportActive()
    }

    private fun isPlayerReconfigureClose(
        request: UsbExclusiveSessionResources.CloseRequest
    ): Boolean = request.source == "player_pcm" && request.reason == "open_player_pcm_reconfigure"

    private fun clearExpiredBlock(nowMs: Long) {
        if (blockedUntilMs > 0L && nowMs >= blockedUntilMs) clearBlock()
    }

    private fun clearBlock() {
        blockedUntilMs = 0L
        blockReason = ""
    }

    private fun String.isHighRiskNativeOpenFailure(): Boolean {
        val code = usbExclusiveErrorCode()
        return code.requiresFreshNativeOpen || HIGH_RISK_OPEN_FAILURE_MARKERS.any {
            contains(it, ignoreCase = true)
        }
    }

    private fun String.isRecoverableUserActionBlock(): Boolean {
        val code = usbExclusiveErrorCode()
        if (code.requiresFreshNativeOpen) return false
        if (NONRECOVERABLE_PREFIXES.any { startsWith(it) }) return false
        if (NONRECOVERABLE_MARKERS.any { contains(it, ignoreCase = true) }) return false
        return RECOVERABLE_MARKERS.any { contains(it, ignoreCase = true) }
    }

    private companion object {
        const val MIN_OPEN_INTERVAL_MS = 3_500L
        const val FAILURE_FUSE_MS = 18_000L
        const val TRANSIENT_FUSE_MS = 5_000L
        val HIGH_RISK_OPEN_FAILURE_MARKERS = listOf(
            "feedback_scheduler", "claim_interface", "set_alt", "nativeOpen", "usb", "transport"
        )
        val NONRECOVERABLE_PREFIXES = listOf(
            "sample_rate_unsupported", "bit_depth_unsupported", "channel_count_unsupported"
        )
        val NONRECOVERABLE_MARKERS = listOf("claim_interface", "set_alt", "nativeOpen")
        val RECOVERABLE_MARKERS = listOf(
            "usb_exclusive_disabled", "release", "failover", "native_failure", "transport",
            "foreground", "stalled"
        )
    }
}
