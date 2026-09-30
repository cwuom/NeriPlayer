package moe.ouom.neriplayer.core.player.usb.route

import kotlinx.coroutines.delay
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import kotlin.time.Duration.Companion.milliseconds

internal data class UsbSystemAudioSnapshot(
    val usbEnabled: Boolean,
    val playerInitialized: Boolean,
    val mediaItemCount: Int,
    val hasMediaItem: Boolean,
    val mediaItemIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val playbackState: Int
)

internal data class UsbSystemAudioReleaseRequest(
    val reason: String,
    val reconfigureAudioSink: Boolean,
    val restoreAudioFocus: Boolean,
    val routeGeneration: Long,
    val playbackWasActive: Boolean,
    val releaseMediaItemIndex: Int? = null,
    val releasePositionMs: Long? = null
)

internal interface UsbSystemAudioRoutePort {
    fun snapshot(): UsbSystemAudioSnapshot
    fun interruptedPositionMs(): Long?
    fun cancelRecovery(reason: String)
    fun cancelSinkReconfiguration()
    fun prepareForDisable(reason: String)
    fun deferNativeOpen(reason: String, delayMs: Long)
    fun updatePathAfterRelease(reason: String, disabling: Boolean, playbackShouldContinue: Boolean)
    fun stopNativeSessions(reason: String, disabling: Boolean)
    fun releaseSystemSound(reason: String)
    fun releaseAudioFocus(reason: String)
    fun nativeCloseInFlightCount(): Int
    fun clearPreferredDevice(reason: String)
    fun clearReleaseMute(reason: String)
    fun restoreAudioFocus()
    fun restoreTransientPlayback(reason: String)
    fun scheduleSinkReconfiguration(reason: String)
    fun resumeInterruptedPlayback(reason: String)
    fun resetSystemAudioPlayer(mediaItemIndex: Int, positionMs: Long, resumePlayback: Boolean, reason: String): Boolean
    fun clearForcedSystemFallback()
    fun clearInterruptedIntent(reason: String)
    fun finishToggle(reason: String)
}

internal class UsbSystemAudioRouteOwner(
    private val transition: UsbRouteTransitionOwner,
    private val sink: UsbSinkRouteOwner,
    private val port: UsbSystemAudioRoutePort,
    private val nowElapsedMs: () -> Long
) {
    fun release(request: UsbSystemAudioReleaseRequest) {
        val disabling = request.reason == "usb_exclusive_disabled"
        val interruptedPositionMs = if (disabling) port.interruptedPositionMs() else null
        val releasePositionMs = request.releasePositionMs ?: interruptedPositionMs
        prepareRelease(request, disabling)
        if (!port.snapshot().playerInitialized) {
            if (disabling) transition.finishSystemAudioRelease()
            return
        }
        val block: suspend () -> Unit = { performRelease(request, disabling, releasePositionMs) }
        if (disabling) transition.launchSystemAudioRelease(block)
        else transition.launchUntrackedRelease(block)
    }

    private fun prepareRelease(request: UsbSystemAudioReleaseRequest, disabling: Boolean) {
        port.cancelRecovery("release:${request.reason}")
        transition.cancelSystemAudioWatchdog()
        port.cancelSinkReconfiguration()
        if (disabling) {
            transition.beginSystemAudioRelease()
            port.prepareForDisable(request.reason)
        }
        port.deferNativeOpen(request.reason, RELEASE_REOPEN_COOLDOWN_MS)
        transition.resetRecoveryAttempts()
        sink.clearPendingPreferenceReconfiguration()
        port.updatePathAfterRelease(request.reason, disabling, !disabling && request.playbackWasActive)
        port.stopNativeSessions(request.reason, disabling)
        port.releaseSystemSound(request.reason)
        port.releaseAudioFocus(request.reason)
    }

    private suspend fun performRelease(request: UsbSystemAudioReleaseRequest, disabling: Boolean, positionMs: Long?) {
        val current = port.snapshot()
        if (!current.releaseStillCurrent(request.routeGeneration, transition.generation, disabling)) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "skip stale USB route release: reason=${request.reason} generation=${request.routeGeneration} current=${transition.generation}"
            )
            return
        }
        completeReleaseAfterRouteCheck(request, disabling, positionMs)
    }

    private suspend fun completeReleaseAfterRouteCheck(
        request: UsbSystemAudioReleaseRequest,
        disabling: Boolean,
        positionMs: Long?
    ) {
        port.clearPreferredDevice(request.reason)
        port.clearReleaseMute(request.reason)
        if (request.restoreAudioFocus) port.restoreAudioFocus()
        if (disabling && request.reconfigureAudioSink) {
            waitForNativeCloseThenReset(request, positionMs)
            return
        }
        port.restoreTransientPlayback("usb_exclusive_release:${request.reason}")
        if (request.reconfigureAudioSink) port.scheduleSinkReconfiguration("release:${request.reason}")
    }

    private suspend fun waitForNativeCloseThenReset(request: UsbSystemAudioReleaseRequest, positionMs: Long?) {
        val closeInFlight = waitForNativeClose()
        if (closeInFlight > 0) {
            resetAfterNativeCloseTimeout(request, positionMs, closeInFlight)
            return
        }
        delay(SYSTEM_AUDIO_RELEASE_DELAY_MS.milliseconds)
        resetAfterNativeClosed(request, positionMs)
    }

    private suspend fun waitForNativeClose(): Int {
        val startedAtMs = nowElapsedMs()
        while (port.nativeCloseInFlightCount() > 0 &&
            nowElapsedMs() - startedAtMs < NATIVE_CLOSE_WAIT_TIMEOUT_MS) {
            delay(NATIVE_CLOSE_WAIT_POLL_MS.milliseconds)
        }
        return port.nativeCloseInFlightCount()
    }

    private fun resetAfterNativeCloseTimeout(
        request: UsbSystemAudioReleaseRequest,
        positionMs: Long?,
        closeInFlight: Int
    ) {
        NPLogger.w(
            "NERI-UsbExclusive",
            "native close timed out; force system audio reset: reason=${request.reason} closeInFlight=$closeInFlight"
        )
        transition.finishSystemAudioRelease()
        resetSystemAudio("native_close_timeout_idle:${request.reason}", request.routeGeneration, false,
            request.releaseMediaItemIndex, positionMs, false)
    }

    private fun resetAfterNativeClosed(request: UsbSystemAudioReleaseRequest, positionMs: Long?) {
        val current = port.snapshot()
        if (!current.canResetAfterRelease(request.routeGeneration, transition.generation)) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "skip stale system audio reset after USB release: reason=${request.reason} " +
                    "generation=${request.routeGeneration} current=${transition.generation} enabled=${current.usbEnabled}"
            )
            return
        }
        transition.finishSystemAudioRelease()
        NPLogger.i("NERI-UsbExclusive", "USB exclusive released; rebuild system audio without auto resume: reason=${request.reason}")
        resetSystemAudio("idle:${request.reason}", request.routeGeneration, false,
            request.releaseMediaItemIndex, positionMs, false)
    }

    fun resetSystemAudio(
        reason: String,
        routeGeneration: Long,
        resumePlayback: Boolean,
        releaseMediaItemIndex: Int? = null,
        releasePositionMs: Long? = null,
        allowWatchdog: Boolean = true
    ) {
        val state = port.snapshot()
        if (!canResetSystemAudio(state, routeGeneration, reason)) return
        if (!state.hasResetMedia()) {
            if (resumePlayback) port.resumeInterruptedPlayback("system_audio_reset_no_media:$reason")
            return
        }
        val index = (releaseMediaItemIndex ?: state.mediaItemIndex).coerceIn(0, state.mediaItemCount - 1)
        val positionMs = (releasePositionMs ?: state.positionMs).coerceAtLeast(0L)
        resetPlayerWithMedia(reason, routeGeneration, resumePlayback, index, positionMs, allowWatchdog)
    }

    private fun canResetSystemAudio(state: UsbSystemAudioSnapshot, routeGeneration: Long, reason: String): Boolean {
        if (!state.playerInitialized) return false
        if (!state.canResetAfterRelease(routeGeneration, transition.generation)) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "skip system audio reset for stale USB release: reason=$reason generation=$routeGeneration " +
                    "current=${transition.generation} enabled=${state.usbEnabled}"
            )
            return false
        }
        return true
    }

    private fun resetPlayerWithMedia(
        reason: String,
        routeGeneration: Long,
        resumePlayback: Boolean,
        index: Int,
        positionMs: Long,
        allowWatchdog: Boolean
    ) {
        transition.cancelSystemAudioWatchdog()
        NPLogger.i(
            "NERI-UsbExclusive",
            "force system audio reset after USB release: reason=$reason index=$index positionMs=$positionMs resume=$resumePlayback"
        )
        if (port.resetSystemAudioPlayer(index, positionMs, resumePlayback, reason)) {
            completeReset(reason, routeGeneration, resumePlayback, index, positionMs, allowWatchdog)
        } else {
            port.finishToggle("usb_system_audio_reset_failed:$reason")
        }
    }

    private fun completeReset(
        reason: String,
        routeGeneration: Long,
        resumePlayback: Boolean,
        index: Int,
        positionMs: Long,
        allowWatchdog: Boolean
    ) {
        sink.markReconfiguredNow()
        port.clearForcedSystemFallback()
        port.restoreAudioFocus()
        if (resumePlayback) port.clearInterruptedIntent("system_audio_reset:$reason")
        port.finishToggle("usb_system_audio_reset:$reason")
        if (resumePlayback && allowWatchdog) scheduleFallbackWatchdog(reason, routeGeneration, index, positionMs)
    }

    private fun scheduleFallbackWatchdog(reason: String, generation: Long, index: Int, positionMs: Long) {
        transition.launchSystemAudioWatchdog(SYSTEM_AUDIO_RESUME_WATCHDOG_MS) watchdog@{
            val current = port.snapshot()
            if (!current.watchdogRouteStillCurrent(generation, transition.generation)) return@watchdog
            if (!current.playbackStalledAt(index, positionMs)) return@watchdog
            NPLogger.w(
                "NERI-UsbExclusive",
                "system audio fallback stalled after USB release; retry reset: reason=$reason " +
                    "index=${current.mediaItemIndex} positionMs=${current.positionMs} " +
                    "state=${playbackStateName(current.playbackState)}"
            )
            resetSystemAudio("system_audio_watchdog:$reason", generation, true,
                current.mediaItemIndex, current.positionMs.coerceAtLeast(0L), false)
        }
    }

    private companion object {
        const val RELEASE_REOPEN_COOLDOWN_MS = 3_500L
        const val SYSTEM_AUDIO_RELEASE_DELAY_MS = 650L
        const val NATIVE_CLOSE_WAIT_TIMEOUT_MS = 4_000L
        const val NATIVE_CLOSE_WAIT_POLL_MS = 50L
        const val SYSTEM_AUDIO_RESUME_WATCHDOG_MS = 1_600L
    }
}
