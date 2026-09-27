package moe.ouom.neriplayer.core.player.usb.recovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.policy.usb.USB_EXCLUSIVE_DEFERRED_RUNTIME_REFRESH_RETRY_DELAY_MS
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveForegroundRecoveryAction
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveKeepAliveDecision
import moe.ouom.neriplayer.core.player.policy.usb.evaluateUsbExclusiveKeepAliveProgress
import moe.ouom.neriplayer.core.player.policy.usb.resolveUsbExclusiveForegroundRecoveryAction
import moe.ouom.neriplayer.core.player.policy.usb.shouldApplyActiveUsbBufferResize
import moe.ouom.neriplayer.core.player.policy.usb.shouldRestoreUsbExclusiveForegroundPlaybackIntent
import moe.ouom.neriplayer.core.player.policy.usb.shouldRetryUsbExclusiveDeferredRuntimeRefresh
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics

internal data class UsbExclusiveLivenessSnapshot(
    val playbackEnabled: Boolean,
    val playerInitialized: Boolean,
    val routeGeneration: Long,
    val transportActive: Boolean,
    val playerPositionMs: Long,
    val playerState: Int,
    val playWhenReady: Boolean,
    val isPlaying: Boolean
)

internal interface UsbExclusiveLivenessPort {
    fun snapshot(): UsbExclusiveLivenessSnapshot
    fun pathState(): UsbExclusiveAudioPathState
    fun nativeState(): UsbExclusiveNativeState
    fun refreshNativeState()
    fun nativeOpenGateReason(): String?
    fun backgroundAuditContext(): String
    fun reassertServiceForeground(reason: String)
    fun updateBackgroundAnchor(reason: String)
    fun recoverRoute(reason: String, forceRecovery: Boolean = false): Boolean
    fun applyAudioFocus()
    fun applyPlaybackPolicy()
    fun restorePlaybackIntent(reason: String)
    fun markForegroundStable()
    fun targetBufferDurationMs(foreground: Boolean): Int
    fun configureTransferWindow(durationMs: Int, foreground: Boolean): Boolean
    fun configureBufferDuration(durationMs: Int, foreground: Boolean): Boolean
}

internal class UsbExclusiveLivenessOwner(
    private val scope: CoroutineScope,
    private val port: UsbExclusiveLivenessPort,
    initialForeground: Boolean = true
) {
    @Volatile
    var appInForeground: Boolean = initialForeground
        private set

    private var foregroundRecoveryJob: Job? = null
    private var backgroundAuditJob: Job? = null

    fun updateForegroundState(foreground: Boolean, reason: String) {
        if (appInForeground == foreground) return
        appInForeground = foreground
        if (foreground) {
            cancelBackgroundAudit()
        } else {
            cancelForegroundRecovery()
            scheduleBackgroundAudit(reason)
            if (port.snapshot().playbackEnabled) port.reassertServiceForeground(reason)
        }
        port.updateBackgroundAnchor(reason)
        if (port.snapshot().playbackEnabled) applyActiveBuffer(reason)
    }

    fun cancelJobs() {
        cancelForegroundRecovery()
        cancelBackgroundAudit()
    }

    private fun cancelForegroundRecovery() {
        foregroundRecoveryJob?.cancel()
        foregroundRecoveryJob = null
    }

    private fun cancelBackgroundAudit() {
        backgroundAuditJob?.cancel()
        backgroundAuditJob = null
    }

    fun scheduleBackgroundAudit(reason: String) {
        cancelBackgroundAudit()
        val initial = port.snapshot()
        if (!initial.playbackEnabled || !initial.playerInitialized) return
        val generation = initial.routeGeneration
        backgroundAuditJob = scope.launch {
            auditBackground(reason, generation)
            if (backgroundAuditJob === coroutineContext[Job]) backgroundAuditJob = null
        }
    }

    private suspend fun auditBackground(reason: String, generation: Long) {
        var elapsedMs = 0L
        val progress = BackgroundAuditProgress()
        for (checkpointMs in BACKGROUND_AUDIT_CHECKPOINTS_MS) {
            delay((checkpointMs - elapsedMs).coerceAtLeast(0L))
            elapsedMs = checkpointMs
            val playback = port.snapshot()
            if (!shouldContinueBackgroundAudit(playback, generation)) return
            val native = refreshNativeStateWithDeferredRetry(reason, "background_audit_$checkpointMs")
            val path = port.pathState()
            val currentPlayback = port.snapshot()
            logBackgroundAudit(reason, checkpointMs, currentPlayback, native, path)
            val decision = progress.observe(currentPlayback, native, path)
            if (decision?.shouldRecover == true) {
                logFakeBackgroundProgress(reason, checkpointMs, native, decision)
                port.recoverRoute("background_audit_fake_progress:$reason:$checkpointMs", forceRecovery = true)
                return
            }
            port.recoverRoute("background_audit:$reason:$checkpointMs")
        }
    }

    private fun shouldContinueBackgroundAudit(playback: UsbExclusiveLivenessSnapshot, generation: Long): Boolean {
        if (!hasActiveBackgroundAudit()) return false
        return playbackAvailableForAudit(playback, generation)
    }

    private fun hasActiveBackgroundAudit(): Boolean = backgroundAuditJob != null && !appInForeground

    private fun playbackAvailableForAudit(playback: UsbExclusiveLivenessSnapshot, generation: Long): Boolean {
        if (!playback.playbackEnabled) return false
        return initializedForGeneration(playback, generation)
    }

    private fun initializedForGeneration(playback: UsbExclusiveLivenessSnapshot, generation: Long): Boolean {
        if (!playback.playerInitialized) return false
        return playback.routeGeneration == generation
    }

    private fun logBackgroundAudit(
        reason: String,
        checkpointMs: Long,
        playback: UsbExclusiveLivenessSnapshot,
        native: UsbExclusiveNativeState,
        path: UsbExclusiveAudioPathState
    ) {
        NPLogger.i(
            "NERI-UsbExclusive",
            "background USB audit: reason=$reason elapsedMs=$checkpointMs " +
                "${port.backgroundAuditContext()} path=${path.effectivePath} " +
                "sinkPlaying=${path.sinkPlaying} nativeStreaming=${native.streaming} " +
                "completedFrames=${native.completedAudioFrames} " +
                "queuedFrames=${native.queuedAudioFrames} " +
                "pcm=${native.pcmLevelBytes}/${native.pcmCapacityBytes} " +
                "pcmFree=${native.pcmFreeBytes} " +
                "backpressureEvents=${native.pcmBackpressureEvents} " +
                "backpressureCurrentMs=${native.pcmBackpressureCurrentMs} " +
                "signalFrames=${native.playerSignalFrames} " +
                "silentFrames=${native.playerSilentFrames} " +
                "zeroFillBytes=${native.playerZeroFillBytes} " +
                "outputPeak=${native.outputPeak} " +
                "lastOutputPeak=${native.lastOutputPeak} " +
                "lastChannelPeaks=${native.lastChannel0OutputPeak}/" +
                "${native.lastChannel1OutputPeak} " +
                "playerState=${playbackStateName(playback.playerState)} " +
                "playWhenReady=${playback.playWhenReady} isPlaying=${playback.isPlaying} " +
                "positionMs=${playback.playerPositionMs} runtime=${native.runtimeReport}"
        )
    }

    private fun logFakeBackgroundProgress(
        reason: String,
        checkpointMs: Long,
        native: UsbExclusiveNativeState,
        decision: UsbExclusiveKeepAliveDecision
    ) {
        NPLogger.w(
            "NERI-UsbExclusive",
            "background USB audit detected fake native progress: " +
                "reason=$reason elapsedMs=$checkpointMs progress=${decision.progress} " +
                "completedFrames=${native.completedAudioFrames} " +
                "signalBytes=${native.playerSignalBytes} " +
                "zeroFillBytes=${native.playerZeroFillBytes} " +
                "lastOutputPeak=${native.lastOutputPeak}"
        )
    }

    fun recoverOnForeground(reason: String) {
        val playback = port.snapshot()
        if (!playback.playbackEnabled || !playback.playerInitialized) return
        cancelForegroundRecovery()
        foregroundRecoveryJob = scope.launch { probeForeground(reason) }
    }

    private suspend fun probeForeground(reason: String) {
        val initial = startForegroundProbe(reason) ?: return
        followUpForegroundProbe(reason, initial)
    }

    private suspend fun startForegroundProbe(reason: String): UsbExclusiveNativeState? {
        if (!isPlaybackAvailable()) return null
        port.applyAudioFocus()
        port.applyPlaybackPolicy()
        val initial = refreshNativeStateWithDeferredRetry(reason, "foreground_initial")
        if (!validForegroundSample(initial, reason, followUp = false)) return null
        return initialIfRecoveryNeeded(initial, reason)
    }

    private fun initialIfRecoveryNeeded(initial: UsbExclusiveNativeState, reason: String): UsbExclusiveNativeState? {
        if (port.recoverRoute("foreground_recovery:$reason")) return null
        return initialIfActionable(initial)
    }

    private fun initialIfActionable(initial: UsbExclusiveNativeState): UsbExclusiveNativeState? {
        if (resolveForegroundAction(initial, port.pathState()) == UsbExclusiveForegroundRecoveryAction.NONE) return null
        return initial
    }

    private suspend fun followUpForegroundProbe(reason: String, initial: UsbExclusiveNativeState) {
        delay(FOREGROUND_STALL_CHECK_MS)
        val current = followUpSample(reason) ?: return
        completeForegroundProbe(reason, initial, current)
    }

    private suspend fun followUpSample(reason: String): UsbExclusiveNativeState? {
        if (!isPlaybackAvailable()) return null
        val current = refreshNativeStateWithDeferredRetry(reason, "foreground_follow_up")
        return current.takeIf { validForegroundSample(it, reason, followUp = true) }
    }

    private fun completeForegroundProbe(reason: String, initial: UsbExclusiveNativeState, current: UsbExclusiveNativeState) {
        val currentAction = resolveForegroundAction(current, port.pathState())
        if (currentAction == UsbExclusiveForegroundRecoveryAction.NONE) return
        restorePlaybackIntentIfNeeded(reason, currentAction)
        finishActionableForegroundProbe(reason, initial, current, currentAction)
    }

    private fun finishActionableForegroundProbe(
        reason: String,
        initial: UsbExclusiveNativeState,
        current: UsbExclusiveNativeState,
        currentAction: UsbExclusiveForegroundRecoveryAction
    ) {
        if (recoverStoppedTransport(reason, current, currentAction)) return
        if (recoverStalledProgress(reason, initial, current)) return
        port.markForegroundStable()
    }

    private fun isPlaybackAvailable(): Boolean {
        val playback = port.snapshot()
        return playback.playbackEnabled && playback.playerInitialized
    }

    private fun validForegroundSample(native: UsbExclusiveNativeState, reason: String, followUp: Boolean): Boolean {
        if (!native.runtimeReportValid) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "skip foreground USB recovery because ${if (followUp) "follow-up " else ""}native sample is invalid: " +
                    "reason=$reason invalidReason=${native.runtimeReportInvalidReason}"
            )
            return false
        }
        if (!followUp && (native.transitioning || port.nativeOpenGateReason() != null)) {
            NPLogger.i(
                "NERI-UsbExclusive",
                "skip foreground USB recovery while native transition is active: reason=$reason " +
                    "runtime=${native.runtimeReport}"
            )
            return false
        }
        return true
    }

    private fun resolveForegroundAction(
        native: UsbExclusiveNativeState,
        path: UsbExclusiveAudioPathState
    ): UsbExclusiveForegroundRecoveryAction = resolveUsbExclusiveForegroundRecoveryAction(
        nativePathActive = path.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB &&
            native.source == "player_pcm",
        sinkPlaying = path.sinkPlaying,
        nativeOpened = native.opened,
        nativeStreaming = native.streaming,
        nativePaused = native.paused,
        nativeTransitioning = native.transitioning
    )

    private fun restorePlaybackIntentIfNeeded(reason: String, action: UsbExclusiveForegroundRecoveryAction) {
        if (!shouldRestoreUsbExclusiveForegroundPlaybackIntent(action, port.snapshot().transportActive)) return
        port.restorePlaybackIntent(reason)
        port.applyAudioFocus()
    }

    private fun recoverStoppedTransport(
        reason: String,
        native: UsbExclusiveNativeState,
        action: UsbExclusiveForegroundRecoveryAction
    ): Boolean {
        if (action != UsbExclusiveForegroundRecoveryAction.RECOVER_STOPPED_TRANSPORT) return false
        NPLogger.w(
            "NERI-UsbExclusive",
            "foreground USB transport stopped while the sink is still playing; " +
                "rebuild native route: reason=$reason completedFrames=${native.completedAudioFrames}"
        )
        port.recoverRoute("foreground_stopped:$reason", forceRecovery = true)
        return true
    }

    private fun recoverStalledProgress(
        reason: String,
        initial: UsbExclusiveNativeState,
        current: UsbExclusiveNativeState
    ): Boolean {
        val metrics = current.runtimeReport.usbRuntimeMetrics()
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = initial.handle,
            currentHandle = current.handle,
            previousCompletedFrames = initial.completedAudioFrames,
            currentCompletedFrames = current.completedAudioFrames,
            previousSignalBytes = initial.playerSignalBytes,
            currentSignalBytes = current.playerSignalBytes,
            previousZeroFillBytes = initial.playerZeroFillBytes,
            currentZeroFillBytes = current.playerZeroFillBytes,
            previousOutputPeak = initial.lastOutputPeak,
            currentOutputPeak = current.lastOutputPeak,
            outputSampleRate = metrics.sampleRate.orZero(),
            outputFrameBytes = metrics.outputFrameBytes.orZero(),
            currentPcmLevelBytes = metrics.pcmLevelBytes.orUnknown(),
            previousStallTicks = 0,
            recoveryTicks = 2
        )
        if (!decision.shouldRecover) return false
        NPLogger.w(
            "NERI-UsbExclusive",
            "foreground USB stream lost audible progress; rebuild native route: " +
                "reason=$reason progress=${decision.progress} " +
                "completedBefore=${initial.completedAudioFrames} " +
                "completedAfter=${current.completedAudioFrames} " +
                "signalBefore=${initial.playerSignalBytes} " +
                "signalAfter=${current.playerSignalBytes} " +
                "zeroFillBefore=${initial.playerZeroFillBytes} " +
                "zeroFillAfter=${current.playerZeroFillBytes}"
        )
        port.recoverRoute("foreground_stalled:$reason", forceRecovery = true)
        return true
    }

    private suspend fun refreshNativeStateWithDeferredRetry(reason: String, stage: String): UsbExclusiveNativeState {
        var retryAttempt = 0
        while (true) {
            port.refreshNativeState()
            val native = port.nativeState()
            if (!shouldRetryUsbExclusiveDeferredRuntimeRefresh(
                    runtimeReportValid = native.runtimeReportValid,
                    runtimeReportInvalidReason = native.runtimeReportInvalidReason,
                    retryAttempt = retryAttempt
                )
            ) return native
            retryAttempt += 1
            NPLogger.d("NERI-UsbExclusive", "retry deferred USB runtime refresh: reason=$reason stage=$stage retry=$retryAttempt")
            delay(USB_EXCLUSIVE_DEFERRED_RUNTIME_REFRESH_RETRY_DELAY_MS)
        }
    }

    fun applyActiveBuffer(reason: String) {
        val target = port.targetBufferDurationMs(appInForeground)
        val native = port.nativeState()
        if (!shouldApplyActiveUsbBufferResize(native.streaming, native.bufferDurationMs, target)) {
            val transferApplied = port.configureTransferWindow(target, appInForeground)
            NPLogger.d(
                "NERI-UsbExclusive",
                "defer active USB buffer update: reason=$reason foreground=$appInForeground " +
                    "current=${native.bufferDurationMs} target=$target transferWindowApplied=$transferApplied"
            )
            return
        }
        if (port.configureBufferDuration(target, appInForeground)) {
            NPLogger.d("NERI-UsbExclusive", "updated active USB buffer: reason=$reason foreground=$appInForeground bufferMs=$target")
        }
    }

    private class BackgroundAuditProgress {
        private var handle = 0L
        private var completedFrames = -1L
        private var signalBytes = -1L
        private var zeroFillBytes = -1L
        private var outputPeak = Float.NaN
        private var stallTicks = 0

        fun observe(
            playback: UsbExclusiveLivenessSnapshot,
            native: UsbExclusiveNativeState,
            path: UsbExclusiveAudioPathState
        ): UsbExclusiveKeepAliveDecision? {
            val decision = if (shouldCheckFakeProgress(playback, native, path)) evaluateProgress(native) else null
            updateBaseline(native, decision)
            return decision
        }

        private fun evaluateProgress(native: UsbExclusiveNativeState): UsbExclusiveKeepAliveDecision {
            val metrics = native.runtimeReport.usbRuntimeMetrics()
            return evaluateUsbExclusiveKeepAliveProgress(
                previousHandle = handle,
                currentHandle = native.handle,
                previousCompletedFrames = completedFrames,
                currentCompletedFrames = native.completedAudioFrames,
                previousSignalBytes = signalBytes,
                currentSignalBytes = native.playerSignalBytes,
                previousZeroFillBytes = zeroFillBytes,
                currentZeroFillBytes = native.playerZeroFillBytes,
                previousOutputPeak = outputPeak,
                currentOutputPeak = native.lastOutputPeak,
                outputSampleRate = metrics.sampleRate.orZero(),
                outputFrameBytes = metrics.outputFrameBytes.orZero(),
                currentPcmLevelBytes = metrics.pcmLevelBytes.orUnknown(),
                previousStallTicks = stallTicks,
                recoveryTicks = 1
            )
        }

        private fun updateBaseline(native: UsbExclusiveNativeState, decision: UsbExclusiveKeepAliveDecision?) {
            stallTicks = decision?.stallTicks ?: 0
            handle = native.handle
            completedFrames = native.completedAudioFrames
            signalBytes = native.playerSignalBytes
            zeroFillBytes = native.playerZeroFillBytes
            outputPeak = native.lastOutputPeak
        }

        private fun shouldCheckFakeProgress(
            playback: UsbExclusiveLivenessSnapshot,
            native: UsbExclusiveNativeState,
            path: UsbExclusiveAudioPathState
        ): Boolean {
            if (!playback.transportActive) return false
            return nativeSinkAndStreamActive(native, path)
        }

        private fun nativeSinkAndStreamActive(
            native: UsbExclusiveNativeState,
            path: UsbExclusiveAudioPathState
        ): Boolean {
            if (!nativeSinkPlaying(path)) return false
            return nativeStreamSteady(native)
        }

        private fun nativeSinkPlaying(path: UsbExclusiveAudioPathState): Boolean =
            path.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB && path.sinkPlaying

        private fun nativeStreamSteady(native: UsbExclusiveNativeState): Boolean =
            native.source == "player_pcm" && streamingWithoutTransition(native)

        private fun streamingWithoutTransition(native: UsbExclusiveNativeState): Boolean =
            native.streaming && !native.transitioning
    }

    private companion object {
        val BACKGROUND_AUDIT_CHECKPOINTS_MS = listOf(1_000L, 5_000L, 15_000L)
        const val FOREGROUND_STALL_CHECK_MS = 1_000L
    }
}

private fun Int?.orZero(): Int = this ?: 0

private fun Long?.orUnknown(): Long = this ?: -1L
