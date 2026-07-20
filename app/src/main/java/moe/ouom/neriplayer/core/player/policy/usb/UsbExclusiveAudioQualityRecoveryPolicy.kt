package moe.ouom.neriplayer.core.player.policy.usb

import kotlin.math.max
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRuntimeMetrics

internal data class UsbExclusiveAudioQualityRecoveryState(
    val handle: Long = 0L,
    val completedTransfers: Long = -1L,
    val isoPacketErrors: Long = 0L,
    val isoPacketErrorTransfers: Long = 0L,
    val isoPacketErrorScore: Int = 0,
    val playerSignalBytes: Long = 0L,
    val playerDroppedBytes: Long = 0L,
    val playerUnderrunBytes: Long = 0L,
    val playerZeroFillBytes: Long = 0L,
    val consecutivePlayerDropTicks: Int = 0,
    val consecutivePcmStarvationTicks: Int = 0
)

internal data class UsbExclusiveAudioQualityRecoveryDecision(
    val shouldRecover: Boolean,
    val state: UsbExclusiveAudioQualityRecoveryState,
    val reason: String,
    val debug: String
)

internal object UsbExclusiveAudioQualityRecoveryPolicy {
    private const val STARTUP_PCM_COUNTER_GRACE_MS = 1_000L
    private const val PLAYER_DROPPED_RECOVERY_TICKS = 2
    private const val PLAYER_DROPPED_LARGE_GAP_MS = 40L
    private const val PCM_STARVATION_RECOVERY_TICKS = 2
    private const val PCM_STARVATION_LARGE_GAP_MS = 80L
    private const val PCM_STARVATION_AUDIBLE_OUTPUT_PEAK_MIN = 0.0001f
    private const val ISO_PACKET_ERROR_RECOVERY_SCORE = 4
    private const val ISO_PACKET_ERROR_RECOVERY_PACKETS = 4L
    private const val ISO_PACKET_ERROR_RECOVERY_TRANSFERS = 3L

    fun reset(handle: Long = 0L): UsbExclusiveAudioQualityRecoveryState {
        return UsbExclusiveAudioQualityRecoveryState(handle = handle)
    }

    fun evaluate(
        previous: UsbExclusiveAudioQualityRecoveryState,
        handle: Long,
        metrics: UsbExclusiveRuntimeMetrics,
        nowMs: Long,
        transportStartedAtMs: Long
    ): UsbExclusiveAudioQualityRecoveryDecision {
        val snapshot = metrics.toQualityState(handle)
        if (handle <= 0L || metrics.source != "player_pcm" || metrics.running != true) {
            return ignore(snapshot, "inactive", "source=${metrics.source} running=${metrics.running}")
        }
        if (metrics.paused == true) {
            return ignore(snapshot, "paused", "paused=true")
        }
        if (previous.handle != handle || snapshot.hasCounterResetSince(previous)) {
            return ignore(snapshot, "baseline", "handle=$handle")
        }

        val stablePcmWindow = isStablePcmQualityWindow(
            nowMs = nowMs,
            transportStartedAtMs = transportStartedAtMs,
            completedTransfers = snapshot.completedTransfers
        )
        val isoPacketDelta = max(0L, snapshot.isoPacketErrors - previous.isoPacketErrors)
        val isoTransferDelta = max(
            0L,
            snapshot.isoPacketErrorTransfers - previous.isoPacketErrorTransfers
        )
        val isoScoreDelta = max(0, snapshot.isoPacketErrorScore - previous.isoPacketErrorScore)
        if (isoPacketDelta > 0L || isoTransferDelta > 0L || isoScoreDelta > 0) {
            val burst = snapshot.isoPacketErrorScore >= ISO_PACKET_ERROR_RECOVERY_SCORE ||
                isoPacketDelta >= ISO_PACKET_ERROR_RECOVERY_PACKETS ||
                isoTransferDelta >= ISO_PACKET_ERROR_RECOVERY_TRANSFERS
            val debug = "isoDelta=$isoPacketDelta transferDelta=$isoTransferDelta " +
                "score=${snapshot.isoPacketErrorScore} scoreDelta=$isoScoreDelta"
            if (stablePcmWindow && burst) {
                return recover(
                    snapshot = snapshot,
                    reason = "iso_packet_error",
                    debug = debug
                )
            }
            return ignore(
                snapshot = snapshot,
                reason = if (stablePcmWindow) {
                    "minor_iso_packet_error"
                } else {
                    "startup_iso_packet_error"
                },
                debug = debug
            )
        }

        val droppedDelta = max(0L, snapshot.playerDroppedBytes - previous.playerDroppedBytes)
        if (droppedDelta > 0L) {
            if (!stablePcmWindow) {
                return ignore(
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
            if (
                !largeDrop &&
                nextTicks < PLAYER_DROPPED_RECOVERY_TICKS &&
                signalDelta > 0L &&
                metrics.hasAudibleOutputPeak()
            ) {
                return ignore(
                    snapshot = armedSnapshot,
                    reason = "minor_player_drop_with_signal",
                    debug = "droppedDelta=$droppedDelta signalDelta=$signalDelta " +
                        "peak=${metrics.bestOutputPeak()} ticks=$nextTicks threshold=$largeGapBytes"
                )
            }
            if (nextTicks >= PLAYER_DROPPED_RECOVERY_TICKS || largeDrop) {
                return recover(
                    snapshot = armedSnapshot,
                    reason = "player_pcm_dropped",
                    debug = "droppedDelta=$droppedDelta ticks=$nextTicks " +
                        "largeDrop=$largeDrop threshold=$largeGapBytes " +
                        "completedTransfers=${snapshot.completedTransfers}"
                )
            }
            return ignore(
                snapshot = armedSnapshot,
                reason = "armed_player_drop",
                debug = "droppedDelta=$droppedDelta ticks=$nextTicks"
            )
        }

        val underrunDelta = max(0L, snapshot.playerUnderrunBytes - previous.playerUnderrunBytes)
        val zeroFillDelta = max(0L, snapshot.playerZeroFillBytes - previous.playerZeroFillBytes)
        val starvationDelta = max(underrunDelta, zeroFillDelta)
        if (starvationDelta <= 0L) {
            if (
                previous.consecutivePlayerDropTicks > 0 &&
                snapshot.isSameRuntimeCounterSampleAs(previous)
            ) {
                return ignore(
                    snapshot = snapshot.copy(
                        consecutivePlayerDropTicks = previous.consecutivePlayerDropTicks
                    ),
                    reason = "awaiting_player_drop_sample",
                    debug = "ticks=${previous.consecutivePlayerDropTicks} " +
                        "completedTransfers=${snapshot.completedTransfers}"
                )
            }
            if (
                previous.consecutivePcmStarvationTicks > 0 &&
                snapshot.isSameRuntimeCounterSampleAs(previous)
            ) {
                return ignore(
                    snapshot = snapshot.copy(
                        consecutivePcmStarvationTicks = previous.consecutivePcmStarvationTicks
                    ),
                    reason = "awaiting_pcm_starvation_sample",
                    debug = "ticks=${previous.consecutivePcmStarvationTicks} " +
                        "completedTransfers=${snapshot.completedTransfers}"
                )
            }
            return ignore(snapshot, "healthy", "completedTransfers=${snapshot.completedTransfers}")
        }
        if (!stablePcmWindow) {
            return ignore(
                snapshot = snapshot,
                reason = "startup_pcm_starvation",
                debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta " +
                    "completedTransfers=${snapshot.completedTransfers}"
            )
        }

        val nextTicks = (previous.consecutivePcmStarvationTicks + 1)
            .coerceAtMost(PCM_STARVATION_RECOVERY_TICKS)
        val armedSnapshot = snapshot.copy(consecutivePcmStarvationTicks = nextTicks)
        val largeGapBytes = largeStarvationGapBytes(metrics)
        val largeGap = largeGapBytes > 0L && starvationDelta >= largeGapBytes
        val signalDelta = max(0L, snapshot.playerSignalBytes - previous.playerSignalBytes)
        if (
            !largeGap &&
            nextTicks < PCM_STARVATION_RECOVERY_TICKS &&
            signalDelta > 0L &&
            metrics.hasAudibleOutputPeak()
        ) {
            return ignore(
                snapshot = armedSnapshot,
                reason = "minor_pcm_starvation_with_signal",
                debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta " +
                    "signalDelta=$signalDelta peak=${metrics.bestOutputPeak()} " +
                    "ticks=$nextTicks threshold=$largeGapBytes"
            )
        }
        if (largeGap) {
            return ignore(
                snapshot = armedSnapshot,
                reason = "large_pcm_starvation",
                debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta " +
                    "ticks=$nextTicks largeGap=$largeGap threshold=$largeGapBytes " +
                    "reopenSuppressed=true"
            )
        }
        if (nextTicks >= PCM_STARVATION_RECOVERY_TICKS) {
            return ignore(
                snapshot = armedSnapshot,
                reason = "persistent_pcm_starvation",
                debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta " +
                    "ticks=$nextTicks threshold=$largeGapBytes reopenSuppressed=true"
            )
        }
        return ignore(
            snapshot = armedSnapshot,
            reason = "armed_pcm_starvation",
            debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta ticks=$nextTicks"
        )
    }

    private fun isStablePcmQualityWindow(
        nowMs: Long,
        transportStartedAtMs: Long,
        completedTransfers: Long
    ): Boolean {
        if (transportStartedAtMs <= 0L) return false
        if (nowMs - transportStartedAtMs < STARTUP_PCM_COUNTER_GRACE_MS) return false
        return completedTransfers > 0L
    }

    private fun largeStarvationGapBytes(metrics: UsbExclusiveRuntimeMetrics): Long {
        val sampleRate = metrics.sampleRate?.takeIf { it > 0 } ?: return Long.MAX_VALUE
        val frameBytes = metrics.outputFrameBytes?.takeIf { it > 0 } ?: return Long.MAX_VALUE
        return sampleRate.toLong() * frameBytes * PCM_STARVATION_LARGE_GAP_MS / 1_000L
    }

    private fun largeDroppedGapBytes(metrics: UsbExclusiveRuntimeMetrics): Long {
        val sampleRate = metrics.sampleRate?.takeIf { it > 0 } ?: return Long.MAX_VALUE
        val frameBytes = metrics.outputFrameBytes?.takeIf { it > 0 } ?: return Long.MAX_VALUE
        return sampleRate.toLong() * frameBytes * PLAYER_DROPPED_LARGE_GAP_MS / 1_000L
    }

    private fun UsbExclusiveRuntimeMetrics.toQualityState(
        handle: Long
    ): UsbExclusiveAudioQualityRecoveryState {
        return UsbExclusiveAudioQualityRecoveryState(
            handle = handle,
            completedTransfers = completedTransfers ?: -1L,
            isoPacketErrors = isoPacketErrors ?: 0L,
            isoPacketErrorTransfers = isoPacketErrorTransfers ?: 0L,
            isoPacketErrorScore = isoPacketErrorScore ?: 0,
            playerSignalBytes = playerSignalBytes ?: 0L,
            playerDroppedBytes = playerDroppedBytes ?: 0L,
            playerUnderrunBytes = playerUnderrunBytes ?: 0L,
            playerZeroFillBytes = playerZeroFillBytes ?: 0L
        )
    }

    private fun UsbExclusiveAudioQualityRecoveryState.hasCounterResetSince(
        previous: UsbExclusiveAudioQualityRecoveryState
    ): Boolean {
        return completedTransfers < previous.completedTransfers ||
            isoPacketErrors < previous.isoPacketErrors ||
            isoPacketErrorTransfers < previous.isoPacketErrorTransfers ||
            isoPacketErrorScore < previous.isoPacketErrorScore ||
            playerSignalBytes < previous.playerSignalBytes ||
            playerDroppedBytes < previous.playerDroppedBytes ||
            playerUnderrunBytes < previous.playerUnderrunBytes ||
            playerZeroFillBytes < previous.playerZeroFillBytes
    }

    private fun UsbExclusiveAudioQualityRecoveryState.isSameRuntimeCounterSampleAs(
        previous: UsbExclusiveAudioQualityRecoveryState
    ): Boolean {
        return completedTransfers == previous.completedTransfers &&
            isoPacketErrors == previous.isoPacketErrors &&
            isoPacketErrorTransfers == previous.isoPacketErrorTransfers &&
            isoPacketErrorScore == previous.isoPacketErrorScore &&
            playerSignalBytes == previous.playerSignalBytes &&
            playerDroppedBytes == previous.playerDroppedBytes &&
            playerUnderrunBytes == previous.playerUnderrunBytes &&
            playerZeroFillBytes == previous.playerZeroFillBytes
    }

    private fun UsbExclusiveRuntimeMetrics.hasAudibleOutputPeak(): Boolean {
        return bestOutputPeak()?.let { it > PCM_STARVATION_AUDIBLE_OUTPUT_PEAK_MIN } == true
    }

    private fun UsbExclusiveRuntimeMetrics.bestOutputPeak(): Float? {
        val recentPeak = lastOutputPeak?.takeIf { !it.isNaN() }
        if (recentPeak != null) return recentPeak
        return outputPeak?.takeIf { !it.isNaN() }
    }

    private fun ignore(
        snapshot: UsbExclusiveAudioQualityRecoveryState,
        reason: String,
        debug: String
    ): UsbExclusiveAudioQualityRecoveryDecision {
        val retainedSnapshot = if (
            reason == "armed_pcm_starvation" ||
            reason == "minor_pcm_starvation_with_signal" ||
            reason == "persistent_pcm_starvation" ||
            reason == "awaiting_pcm_starvation_sample" ||
            reason == "armed_player_drop" ||
            reason == "minor_player_drop_with_signal" ||
            reason == "awaiting_player_drop_sample"
        ) {
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

    private fun recover(
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
}
