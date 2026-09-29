package moe.ouom.neriplayer.core.player.usb.route

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.usb.isTransientUsbExclusiveOpenGate
import moe.ouom.neriplayer.core.player.policy.usb.shouldDeferUsbExclusiveRecoveryForPendingReconfiguration
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusivePreferences
import kotlin.time.Duration.Companion.milliseconds

internal data class UsbPlaybackRouteSnapshot(
    val enabled: Boolean,
    val mixedPlaybackEnabled: Boolean,
    val playerInitialized: Boolean,
    val playbackActive: Boolean,
    val hasMediaItem: Boolean,
    val mediaItemCount: Int,
    val mediaItemIndex: Int,
    val positionMs: Long,
    val resumeRequested: Boolean
)

internal interface UsbPlaybackRoutePort {
    fun snapshot(): UsbPlaybackRouteSnapshot
    fun isMainThread(): Boolean
    fun setPlaybackEnabled(enabled: Boolean)
    fun currentPreferences(): UsbExclusivePreferences
    fun setPreferences(preferences: UsbExclusivePreferences)
    fun markPreparing(preparing: Boolean, reason: String)
    fun pauseForToggle(enabled: Boolean)
    fun applyAudioFocus()
    fun applyOffloadPreferences()
    fun prepareEnabledPolicy()
    fun logPolicyBeforeSet()
    fun applyPreferredAudioDevice()
    fun scheduleSoundConfigApply()
    fun applyActiveBuffer(reason: String)
    fun cancelLivenessJobs()
    fun stopAfterNativeFailure(reason: String)
    fun manualRouteAvailable(): Boolean
    fun blockManualPlayback(reason: String)
    fun rebuildManualPlayer(reason: String): Boolean
    fun resumeAfterOpenGate(reason: String)
    fun releaseSystemSound(reason: String)
    fun releaseNativeResources()
    fun application(): android.app.Application
}

internal class UsbPlaybackRouteOwner(
    private val scope: CoroutineScope,
    private val transition: UsbRouteTransitionOwner,
    private val sink: UsbSinkRouteOwner,
    private val systemRoute: UsbSystemAudioRouteOwner,
    private val port: UsbPlaybackRoutePort,
    private val nativePort: UsbPlaybackNativeRoutePort,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime
) {
    fun changeSetting(enabled: Boolean) {
        if (!port.isMainThread()) {
            scope.launch { changeSetting(enabled) }
            return
        }
        val changed = port.snapshot().enabled != enabled
        port.setPlaybackEnabled(enabled)
        NPLogger.d("NERI-UsbExclusive", "settingsChanged(): enabled=$enabled, changed=$changed")
        if (!changed) {
            applyUnchangedSetting(enabled)
            return
        }
        applyChangedSetting(enabled)
    }

    private fun applyUnchangedSetting(enabled: Boolean) {
        val fallbackReason = nativePort.forcedFallbackReason()
        nativePort.updateRequested(enabled)
        if (enabled && fallbackReason == "usb_exclusive_disabled") {
            nativePort.clearForcedFallback()
            port.applyAudioFocus()
            applyPolicy(reconfigureSink = false)
        }
    }

    private fun applyChangedSetting(enabled: Boolean) {
        val routeGeneration = transition.advanceGeneration()
        nativePort.updateRequested(enabled)
        val state = port.snapshot()
        val hasMedia = state.playerInitialized && state.hasMediaItem
        transition.beginSettingToggle(hasMedia, enabled)
        port.markPreparing(hasMedia, "settings_changed:$enabled")
        if (hasMedia) port.pauseForToggle(enabled)
        switchRoute(enabled, routeGeneration, state)
        port.scheduleSoundConfigApply()
        if (!hasMedia) finishToggleWithoutMedia()
    }

    private fun switchRoute(enabled: Boolean, routeGeneration: Long, state: UsbPlaybackRouteSnapshot) {
        if (enabled) {
            transition.cancelSystemAudioRelease("usb_exclusive_enabled")
            activate("usb_exclusive_enabled")
        } else {
            systemRoute.release(
                UsbSystemAudioReleaseRequest(
                    reason = "usb_exclusive_disabled",
                    reconfigureAudioSink = true,
                    restoreAudioFocus = false,
                    routeGeneration = routeGeneration,
                    playbackWasActive = false,
                    releaseMediaItemIndex = state.releaseMediaItemIndex(),
                    releasePositionMs = state.releasePositionMs()
                )
            )
            applyPolicy(reconfigureSink = false)
        }
    }

    private fun finishToggleWithoutMedia() {
        transition.clearToggle()
        port.markPreparing(false, "usb_toggle_no_media")
    }

    private fun activate(reason: String, waitForSystemRelease: Boolean = true) {
        val state = port.snapshot()
        if (!state.canRecoverTransport()) return
        cancelActiveSystemRelease(reason, waitForSystemRelease)
        nativePort.clearRecoverableOpenBlock(reason)
        nativePort.clearForcedFallback()
        transition.resetRecoveryAttempts()
        sink.clearPendingPreferenceReconfiguration()
        port.applyAudioFocus()
        applyPolicy(reconfigureSink = true, reason = reason, allowWhilePlaybackActive = true)
    }

    private fun cancelActiveSystemRelease(reason: String, waitForSystemRelease: Boolean) {
        if (waitForSystemRelease && transition.hasActiveSystemAudioRelease()) {
            transition.cancelSystemAudioRelease(reason)
        }
    }

    fun changePreferences(preferences: UsbExclusivePreferences) {
        if (!port.isMainThread()) {
            scope.launch { changePreferences(preferences) }
            return
        }
        val previous = port.currentPreferences()
        port.setPreferences(preferences)
        if (previous == preferences || !port.snapshot().enabled) return
        if (port.snapshot().playbackActive) {
            port.applyActiveBuffer("preferences_changed")
            val requiresReconfiguration = previous.requiresRouteReconfiguration(preferences)
            sink.markPendingPreferenceReconfiguration(requiresReconfiguration)
            NPLogger.i("NERI-UsbExclusive", "USB preferences saved; deferRoute=$requiresReconfiguration")
            if (requiresReconfiguration) sink.deferUntilPlaybackStops("usb_output_preferences_changed")
        } else {
            nativePort.clearForcedFallback()
            retry("usb_output_preferences_changed")
        }
    }

    fun retry(reason: String) {
        if (!port.isMainThread()) {
            scope.launch { retry(reason) }
            return
        }
        retryOnMain(reason)
    }

    private fun retryOnMain(reason: String) {
        val state = port.snapshot()
        if (!state.canRecoverTransport()) return
        if (reason.isActivationReason()) nativePort.clearRecoverableOpenBlock("retry:$reason")
        if (shouldDeferAutomaticRetry(state, reason)) {
            sink.deferUntilPlaybackStops(reason)
            return
        }
        cancelRecovery("manual_retry:$reason")
        transition.resetRecoveryAttempts()
        nativePort.clearForcedFallback()
        NPLogger.d("NERI-UsbExclusive", "retryUsbExclusivePlayback(): reason=$reason")
        applyPolicy(reconfigureSink = true, reason = reason,
            allowWhilePlaybackActive = reason.isUserDrivenActivation())
    }

    fun scheduleTransportRecovery(reason: String) {
        val state = port.snapshot()
        if (!state.canRecoverTransport()) return
        sink.clearPendingPreferenceReconfiguration()
        transition.resetRecoveryAttempts()
        if (recoverTransportIfPossible(reason)) return
        stopOrReportIdleTransportFailure(state, reason)
    }

    private fun recoverTransportIfPossible(reason: String): Boolean =
        reason.isRecoverableFallback() && recoverIfUnhealthy("transport_failure:$reason", true)

    private fun stopOrReportIdleTransportFailure(state: UsbPlaybackRouteSnapshot, reason: String) {
        if (state.playbackActive) {
            NPLogger.w("NERI-UsbExclusive", "stop active playback after native USB failure: reason=$reason")
            stopAfterNativeFailure(reason)
        } else {
            NPLogger.w("NERI-UsbExclusive", "skip automatic native USB recovery while playback is idle: reason=$reason")
        }
    }

    fun markNativePathActive(reason: String) {
        if (transition.recoveryAttempts > 0) NPLogger.i("NERI-UsbExclusive", "native USB path recovered: reason=$reason")
        transition.resetRecoveryAttempts()
        cancelRecovery("native_active:$reason")
        nativePort.clearForcedFallback()
        if (!port.snapshot().mixedPlaybackEnabled) {
            nativePort.activateSoundGuard(port.application(), "native_active:$reason")
        }
    }

    fun recoverAfterTransferFailure(reason: String, runtimeReport: String): Boolean {
        val state = port.snapshot()
        if (!state.enabled || state.mixedPlaybackEnabled) return false
        if (reason.isFirstCompletionTimeout() || runtimeReport.isFirstCompletionTimeout()) {
            return recoverFirstCompletionTimeout(reason, runtimeReport)
        }
        if (reason.isRecoverableTransferFailure() || runtimeReport.isRecoverableTransferFailure()) {
            NPLogger.w("NERI-UsbExclusive", "skip immediate native USB recovery after transfer failure: reason=$reason runtime=$runtimeReport")
        }
        return false
    }

    private fun recoverFirstCompletionTimeout(reason: String, runtimeReport: String): Boolean {
        val attempt = transition.claimRecoveryAttempt(FIRST_COMPLETION_MAX_ATTEMPTS)
        if (attempt == null) {
            NPLogger.w("NERI-UsbExclusive", "first completion timeout recovery limit reached: reason=$reason runtime=$runtimeReport")
            return false
        }
        val scheduled = recoverIfUnhealthy(
            reason = "first_completion_timeout_recovery:$reason",
            forceRecovery = true
        )
        if (!scheduled) {
            nativePort.requireFreshOpen("first_completion_timeout_recovery")
            sink.schedule(
                reason = "usb_exclusive_first_completion_timeout_recovery",
                allowWhilePlaybackActive = true,
                bypassCooldown = true
            )
        }
        NPLogger.w("NERI-UsbExclusive", "recover native USB playback after first completion timeout: attempt=$attempt reason=$reason runtime=$runtimeReport")
        return true
    }

    fun stopAfterNativeFailure(reason: String) {
        if (isTransientUsbExclusiveOpenGate(reason) || reason.isNativeTransitionInFlightGate()) return
        port.markPreparing(false, "native_failure:$reason")
        cancelRecovery("native_failure:$reason")
        sink.cancel()
        transition.cancelSystemAudioWatchdog()
        transition.clearToggle()
        transition.cancelOpenGatePlayback()
        port.stopAfterNativeFailure(reason)
    }

    fun prepareManualPlayback(reason: String): Boolean {
        val state = port.snapshot()
        if (!state.hasManualPlaybackItem()) return true
        if (!port.manualRouteAvailable()) {
            blockManualPlayback(reason)
            return false
        }
        if (reusableNativePlaybackRoute(nativePort.path(), nativePort.native())) return true
        beginManualRouteRebuild(reason)
        val gate = nativePort.openGateReason()
        if (gate != null) {
            NPLogger.i("NERI-UsbExclusive", "wait for native USB route before manual playback: reason=$reason gate=$gate")
            scheduleAfterOpenGate(reason, gate)
            return false
        }
        return rebuildManualRoute(reason)
    }

    private fun blockManualPlayback(reason: String) {
        port.markPreparing(false, "manual_play_no_usb:$reason")
        transition.cancelOpenGatePlayback()
        transition.clearToggle()
        port.blockManualPlayback(reason)
    }

    private fun beginManualRouteRebuild(reason: String) {
        cancelRecovery("manual_play:$reason")
        sink.cancel()
        transition.advanceGeneration()
        transition.resetRecoveryAttempts()
        sink.clearPendingPreferenceReconfiguration()
        nativePort.clearForcedFallback()
        nativePort.clearRecoverableOpenBlock("manual_play:$reason")
    }

    private fun rebuildManualRoute(reason: String): Boolean {
        port.applyAudioFocus()
        applyPolicy(reconfigureSink = false)
        if (!port.rebuildManualPlayer(reason)) return false
        sink.markReconfiguredNow()
        return true
    }

    private fun scheduleAfterOpenGate(reason: String, initialGateReason: String) {
        transition.launchOpenGatePlayback {
            val gate = waitForOpenGate(reason, initialGateReason)
            finishManualPlaybackAfterGate(reason, gate)
        }
    }

    private suspend fun waitForOpenGate(reason: String, initialGateReason: String): String? {
        val startedAtMs = nowElapsedMs()
        var gate: String? = initialGateReason
        while (shouldWaitForOpenGate(port.snapshot(), gate, nowElapsedMs() - startedAtMs, OPEN_GATE_WAIT_TIMEOUT_MS)) {
            delay(OPEN_GATE_WAIT_POLL_MS.milliseconds)
            nativePort.clearRecoverableOpenBlock("manual_play_wait:$reason")
            gate = nativePort.openGateReason()
        }
        return gate
    }

    private fun finishManualPlaybackAfterGate(reason: String, gate: String?) {
        val state = port.snapshot()
        if (!state.hasPendingManualIntent()) {
            cancelManualPlaybackAfterGate(reason, state.enabled)
            return
        }
        if (gate != null) {
            port.markPreparing(false, "manual_play_gate_timeout:$gate")
            stopAfterNativeFailure(gate)
            return
        }
        if (!prepareManualPlayback("open_gate_retry:$reason")) return
        port.applyAudioFocus()
        port.resumeAfterOpenGate(reason)
        NPLogger.i("NERI-UsbExclusive", "resumed pending playback after native gate: reason=$reason")
    }

    private fun cancelManualPlaybackAfterGate(reason: String, usbEnabled: Boolean) {
        port.markPreparing(false, "manual_play_cancelled:$reason")
        if (!usbEnabled) port.resumeAfterOpenGate("open_gate_cancelled:$reason")
    }

    fun recoverIfUnhealthy(reason: String, forceRecovery: Boolean = false): Boolean {
        val state = port.snapshot()
        if (!state.allowsRecovery(forceRecovery)) return false
        if (!port.isMainThread()) {
            scope.launch { recoverIfUnhealthy(reason, forceRecovery) }
            return true
        }
        return recoverOnMain(reason, forceRecovery)
    }

    private fun recoverOnMain(reason: String, forceRecovery: Boolean): Boolean {
        val reconfiguration = sink.snapshot()
        if (shouldDeferUsbExclusiveRecoveryForPendingReconfiguration(reconfiguration.pending, reconfiguration.reason)) return true
        val native = nativePort.native()
        if (!nativeRouteRecoverable(native)) return false
        val path = nativePort.path()
        if (!needsUsbRouteRecovery(path, native, forceRecovery)) return false
        scheduleRouteRecovery(reason, forceRecovery, path, native)
        return true
    }

    private fun nativeRouteRecoverable(native: UsbExclusiveNativeState): Boolean {
        if (native.blocksRouteRecovery()) return false
        return nativePort.openGateReason() == null
    }

    private fun scheduleRouteRecovery(
        reason: String,
        forceRecovery: Boolean,
        path: UsbExclusiveAudioPathState,
        native: UsbExclusiveNativeState
    ) {
        transition.advanceGeneration()
        sink.clearPendingPreferenceReconfiguration()
        nativePort.clearForcedFallback()
        nativePort.clearRecoverableOpenBlock("usb_recovery:$reason")
        nativePort.requireFreshOpen("usb_recovery:$reason")
        port.markPreparing(true, "usb_recovery:$reason")
        port.applyAudioFocus()
        sink.schedule(
            reason = "usb_recovery:$reason",
            allowWhilePlaybackActive = true,
            bypassCooldown = true
        )
        NPLogger.w(
            "NERI-UsbExclusive",
            "recover USB exclusive playback by rebuilding native route: reason=$reason force=$forceRecovery " +
                "attempt=${transition.recoveryAttempts} path=${path.effectivePath} fallback=${path.fallbackReason} " +
                "native=${native.source}/${native.streaming} completedFrames=${native.completedAudioFrames} runtime=${native.runtimeReport}"
        )
    }

    fun cancelRecovery(reason: String) {
        port.cancelLivenessJobs()
        NPLogger.d("NERI-UsbExclusive", "cancelUsbExclusiveRecovery(): reason=$reason")
    }

    fun release() {
        sink.cancel()
        transition.cancelRouteJobs()
        port.markPreparing(false, "player_release")
        port.cancelLivenessJobs()
        port.releaseNativeResources()
    }

    fun applyPolicy(
        reconfigureSink: Boolean = false,
        reason: String = "usb_policy_changed",
        allowWhilePlaybackActive: Boolean = false
    ) {
        if (!port.snapshot().playerInitialized) return
        port.applyOffloadPreferences()
        if (port.snapshot().enabled) {
            if (reconfigureSink) {
                transition.advanceGeneration()
                nativePort.clearForcedFallback()
            }
            port.prepareEnabledPolicy()
        } else {
            nativePort.stopPlayerPcm("apply_policy_disabled")
            port.releaseSystemSound("apply_policy_disabled")
        }
        val generation = transition.generation
        port.logPolicyBeforeSet()
        scope.launch {
            if (!port.snapshot().playerInitialized || transition.generation != generation) return@launch
            port.applyPreferredAudioDevice()
            if (reconfigureSink) sink.schedule(reason, allowWhilePlaybackActive)
        }
    }

    private companion object {
        const val FIRST_COMPLETION_MAX_ATTEMPTS = 1
        const val OPEN_GATE_WAIT_TIMEOUT_MS = 8_000L
        const val OPEN_GATE_WAIT_POLL_MS = 100L
    }
}
