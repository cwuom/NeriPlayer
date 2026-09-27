package moe.ouom.neriplayer.core.player.usb.route

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.usb.UsbAudioSinkReconfigurationCoordinator
import moe.ouom.neriplayer.core.player.policy.usb.UsbAudioSinkReconfigurationSnapshot
import moe.ouom.neriplayer.core.player.policy.usb.UsbAudioSinkReconfigurationToken
import moe.ouom.neriplayer.core.player.policy.usb.shouldSkipRedundantUsbExclusiveReconfiguration

internal data class UsbSinkRouteSnapshot(
    val routeGeneration: Long,
    val enabled: Boolean,
    val appInForeground: Boolean,
    val playbackActive: Boolean,
    val playerInitialized: Boolean,
    val hasMediaItem: Boolean,
    val mediaItemCount: Int,
    val mediaItemIndex: Int,
    val positionMs: Long,
    val resumePlayback: Boolean
)

internal interface UsbSinkRoutePort {
    fun routeGeneration(): Long
    fun snapshot(): UsbSinkRouteSnapshot
    fun hasHealthyNativePlayerSession(): Boolean
    fun stopCurrentSink(reason: String, resumePlayback: Boolean): Boolean
    fun prepareSink(mediaItemIndex: Int, positionMs: Long, resumePlayback: Boolean, reason: String): Boolean
    fun finishToggleTransition(preparingReason: String)
    fun clearForcedSystemFallback()
}

internal class UsbSinkRouteOwner(
    private val scope: CoroutineScope,
    private val port: UsbSinkRoutePort,
    private val nowElapsedMs: () -> Long,
    initialPendingPreferenceReconfiguration: Boolean = false,
    initialLastReconfiguredAtMs: Long = 0L
) {
    private data class ReconfigurationRequest(
        val mediaItemIndex: Int,
        val positionMs: Long,
        val resumePlayback: Boolean,
        val waitForSystemAudioRelease: Boolean
    )

    private val coordinator = UsbAudioSinkReconfigurationCoordinator()

    var pendingPreferenceReconfiguration = initialPendingPreferenceReconfiguration
        private set

    var lastReconfiguredAtMs = initialLastReconfiguredAtMs
        private set

    fun snapshot(): UsbAudioSinkReconfigurationSnapshot = coordinator.snapshot()

    fun cancel() {
        coordinator.invalidate()?.cancel()
    }

    fun clearPendingPreferenceReconfiguration() {
        pendingPreferenceReconfiguration = false
    }

    fun markPendingPreferenceReconfiguration(pending: Boolean) {
        pendingPreferenceReconfiguration = pending
    }

    fun markReconfiguredNow() {
        lastReconfiguredAtMs = nowElapsedMs()
        clearPendingPreferenceReconfiguration()
    }

    fun schedule(
        reason: String,
        allowWhilePlaybackActive: Boolean = false,
        bypassCooldown: Boolean = false
    ) {
        val scheduledGeneration = port.routeGeneration()
        val start = coordinator.begin(reason)
        start.supersededJob?.cancel()
        val token = start.token
        val job = scope.launch(start = CoroutineStart.LAZY) reconfigure@{
            executeReconfiguration(token, scheduledGeneration, reason, allowWhilePlaybackActive, bypassCooldown)
        }
        if (!coordinator.install(token, job)) {
            job.cancel()
            coordinator.abandonIfUninstalled(token)
            return
        }
        job.invokeOnCompletion { coordinator.complete(token, job) }
        job.start()
    }

    private suspend fun executeReconfiguration(
        token: UsbAudioSinkReconfigurationToken,
        generation: Long,
        reason: String,
        allowWhilePlaybackActive: Boolean,
        bypassCooldown: Boolean
    ) {
        if (!awaitReconfigurationDelay(token, generation, reason, allowWhilePlaybackActive, bypassCooldown)) return
        val request = readyRequest(token, generation, reason, allowWhilePlaybackActive) ?: return
        if (!stopForReconfiguration(token, request, reason)) return
        if (!readyAfterSystemRelease(token, generation, reason, request.waitForSystemAudioRelease)) return
        finishReconfiguration(token, request, reason)
    }

    private suspend fun awaitReconfigurationDelay(
        token: UsbAudioSinkReconfigurationToken,
        generation: Long,
        reason: String,
        allowWhilePlaybackActive: Boolean,
        bypassCooldown: Boolean
    ): Boolean {
        val initial = port.snapshot()
        if (!coordinator.isLatest(token) || !routeCurrent(generation, reason, "before delay")) return false
        if (deferWhilePlaybackActive(initial, allowWhilePlaybackActive, reason, "before delay")) return false
        val elapsedMs = nowElapsedMs() - lastReconfiguredAtMs
        val cooldownMs = sinkReconfigurationCooldownMs(reason, initial.enabled, bypassCooldown)
        delay((cooldownMs - elapsedMs).coerceAtLeast(USB_SINK_RECONFIGURE_DEBOUNCE_MS))
        return true
    }

    private fun stopForReconfiguration(
        token: UsbAudioSinkReconfigurationToken,
        request: ReconfigurationRequest,
        reason: String
    ): Boolean {
        NPLogger.d(
            "NERI-UsbExclusive",
            "reconfigureAudioSink(): reason=$reason index=${request.mediaItemIndex} " +
                "positionMs=${request.positionMs} playing=${request.resumePlayback}"
        )
        return coordinator.isLatest(token) && port.stopCurrentSink(reason, request.resumePlayback)
    }

    private fun finishReconfiguration(
        token: UsbAudioSinkReconfigurationToken,
        request: ReconfigurationRequest,
        reason: String
    ) {
        val prepared = port.prepareSink(request.mediaItemIndex, request.positionMs, request.resumePlayback, reason)
        if (!coordinator.isLatest(token)) return
        completePreparedReconfiguration(prepared)
    }

    private fun completePreparedReconfiguration(prepared: Boolean) {
        if (prepared) markReconfiguredNow()
        port.finishToggleTransition(if (prepared) "usb_reconfigure_success" else "usb_reconfigure_failed")
    }

    private fun readyRequest(
        token: UsbAudioSinkReconfigurationToken,
        generation: Long,
        reason: String,
        allowWhilePlaybackActive: Boolean
    ): ReconfigurationRequest? {
        val state = mediaSnapshotFor(token) ?: return null
        if (!routeCurrent(generation, reason, "after delay")) return null
        if (skipReadyNativeRoute(reason, state.enabled, generation)) return null
        if (deferWhilePlaybackActive(state, allowWhilePlaybackActive, reason, "after delay")) return null
        return state.toReconfigurationRequest(reason)
    }

    private fun mediaSnapshotFor(token: UsbAudioSinkReconfigurationToken): UsbSinkRouteSnapshot? {
        if (!coordinator.isLatest(token)) return null
        return port.snapshot().takeIf(UsbSinkRouteSnapshot::hasRouteMedia)
    }

    private fun UsbSinkRouteSnapshot.toReconfigurationRequest(reason: String): ReconfigurationRequest? {
        if (!hasReconfigurableItemCount()) return null
        return ReconfigurationRequest(
            mediaItemIndex.coerceIn(0, mediaItemCount - 1),
            positionMs.coerceAtLeast(0L),
            resumePlayback,
            enabled && reason.isUsbExclusiveActivationReason()
        )
    }

    private suspend fun readyAfterSystemRelease(
        token: UsbAudioSinkReconfigurationToken,
        generation: Long,
        reason: String,
        waitForRelease: Boolean
    ): Boolean {
        if (!coordinator.isLatest(token)) return false
        if (!waitForRelease) return true
        delay(SYSTEM_AUDIO_RELEASE_DELAY_MS)
        return currentAfterSystemRelease(token, generation, reason)
    }

    private fun currentAfterSystemRelease(
        token: UsbAudioSinkReconfigurationToken,
        generation: Long,
        reason: String
    ): Boolean {
        if (!coordinator.isLatest(token)) return false
        if (!port.snapshot().hasRouteMedia()) return false
        return routeCurrent(generation, reason, "after system release delay")
    }

    fun deferUntilPlaybackStops(reason: String) {
        pendingPreferenceReconfiguration = true
        val start = coordinator.begin("deferred:$reason")
        start.supersededJob?.cancel()
        val token = start.token
        val job = scope.launch(start = CoroutineStart.LAZY) deferred@{
            executeDeferredReconfiguration(token, reason)
        }
        if (!coordinator.install(token, job)) {
            job.cancel()
            coordinator.abandonIfUninstalled(token)
            return
        }
        job.invokeOnCompletion { coordinator.complete(token, job) }
        job.start()
    }

    private suspend fun executeDeferredReconfiguration(token: UsbAudioSinkReconfigurationToken, reason: String) {
        if (!coordinator.isLatest(token)) return
        if (!foregroundForDeferredSwitch(reason)) return
        NPLogger.i("NERI-UsbExclusive", "defer native USB switch until playback stops: reason=$reason")
        waitForPlaybackToStop()
        if (!readyAfterDeferredWait(token)) return
        finishDeferredReconfiguration(reason)
    }

    private fun readyAfterDeferredWait(token: UsbAudioSinkReconfigurationToken): Boolean {
        if (!canRunDeferredReconfiguration()) {
            clearPendingPreferenceReconfiguration()
            return false
        }
        return coordinator.isLatest(token)
    }

    private fun foregroundForDeferredSwitch(reason: String): Boolean {
        if (port.snapshot().appInForeground) return true
        clearPendingPreferenceReconfiguration()
        NPLogger.i("NERI-UsbExclusive", "skip deferred native USB switch while app is backgrounded: reason=$reason")
        return false
    }

    private fun finishDeferredReconfiguration(reason: String) {
        port.clearForcedSystemFallback()
        clearPendingPreferenceReconfiguration()
        schedule("deferred:$reason")
    }

    private suspend fun waitForPlaybackToStop() {
        while (port.snapshot().waitForPlaybackToStop()) {
            delay(SAFE_SWITCH_POLL_MS)
        }
    }

    private fun canRunDeferredReconfiguration(): Boolean = port.snapshot().readyForDeferredSwitch()

    private fun routeCurrent(generation: Long, reason: String, phase: String): Boolean {
        val current = port.snapshot().routeGeneration
        if (current == generation) return true
        NPLogger.d(
            "NERI-UsbExclusive",
            "skip stale USB reconfiguration $phase: reason=$reason generation=$generation current=$current"
        )
        return false
    }

    private fun deferWhilePlaybackActive(
        state: UsbSinkRouteSnapshot,
        allowWhilePlaybackActive: Boolean,
        reason: String,
        phase: String
    ): Boolean {
        if (!state.enabled || allowWhilePlaybackActive || !state.playbackActive) return false
        if (!state.appInForeground) {
            clearPendingPreferenceReconfiguration()
            NPLogger.i("NERI-UsbExclusive", "skip USB reconfiguration wait while app is backgrounded $phase: reason=$reason")
            return true
        }
        pendingPreferenceReconfiguration = true
        NPLogger.i(
            "NERI-UsbExclusive",
            "defer USB reconfiguration while playback is active: reason=$reason " +
                "allowWhilePlaybackActive=$allowWhilePlaybackActive activation=${reason.isUsbExclusiveActivationReason()}"
        )
        return true
    }

    private fun skipReadyNativeRoute(reason: String, enabled: Boolean, generation: Long): Boolean {
        if (!shouldSkipRedundantUsbExclusiveReconfiguration(
                reason = reason,
                usbExclusiveEnabled = enabled,
                hasHealthyPlayerPcmSession = port.hasHealthyNativePlayerSession()
            )) return false
        clearPendingPreferenceReconfiguration()
        port.finishToggleTransition("native_route_already_ready:$reason")
        NPLogger.i(
            "NERI-UsbExclusive",
            "skip redundant USB reconfiguration because native player session is ready: " +
                "reason=$reason generation=$generation"
        )
        return true
    }

    private fun String.isUsbExclusiveReason(): Boolean =
        contains("usb", ignoreCase = true) || contains("native", ignoreCase = true)

    private fun String.isUsbExclusiveActivationReason(): Boolean {
        if (!isUsbExclusiveReason()) return false
        if (listOf("disabled", "fallback", "failed").any { contains(it, ignoreCase = true) }) return false
        return listOf("enabled", "preference", "policy", "foreground", "permission", "device")
            .any { contains(it, ignoreCase = true) }
    }

    private companion object {
        const val SAFE_SWITCH_POLL_MS = 800L
        const val SYSTEM_AUDIO_RELEASE_DELAY_MS = 650L
    }
}
