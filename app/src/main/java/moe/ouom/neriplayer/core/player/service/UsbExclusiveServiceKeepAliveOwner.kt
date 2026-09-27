package moe.ouom.neriplayer.core.player.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveKeepAliveProgress
import moe.ouom.neriplayer.core.player.policy.usb.evaluateUsbExclusiveKeepAliveProgress
import moe.ouom.neriplayer.core.player.policy.usb.shouldRunUsbExclusiveBackgroundAudioAnchor
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRuntimeMetrics
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics

private const val FOREGROUND_KEEPALIVE_INTERVAL_MS = 5_000L
private const val BACKGROUND_KEEPALIVE_INTERVAL_MS = 1_000L
private const val KEEPALIVE_STALL_WARN_MS = 25_000L
private const val KEEPALIVE_STALL_RECOVERY_TICKS = 1
private const val KEEPALIVE_LOG_INTERVAL_TICKS = 3L

internal fun usbExclusiveKeepAliveIntervalMs(appInForeground: Boolean): Long =
    if (appInForeground) FOREGROUND_KEEPALIVE_INTERVAL_MS else BACKGROUND_KEEPALIVE_INTERVAL_MS

internal fun shouldReassertUsbExclusiveForegroundService(
    appInForeground: Boolean,
    foregroundStarted: Boolean,
    usbExclusivePlaybackActive: Boolean,
): Boolean = !appInForeground && foregroundStarted && usbExclusivePlaybackActive

internal interface UsbExclusiveKeepAlivePort {
    fun appInForeground(): Boolean
    fun playbackActive(): Boolean
    fun foregroundStarted(): Boolean
    fun reassertForeground(reason: String): Boolean
    fun ensureForeground(): Boolean
    fun onForegroundFailure(reason: String)
    fun startAnchor(reason: String)
    fun stopAnchor(reason: String)
    fun refreshNative()
    fun maintainWakeLock()
    fun updatePlaybackPresentation()
    fun nativeState(): UsbExclusiveNativeState
    fun pathState(): UsbExclusiveAudioPathState
    fun wakeLockHeld(): Boolean
    fun anchorDiagnostic(): String
    fun usbPlaybackEnabled(): Boolean
    fun transportActive(): Boolean
    fun recover(reason: String)
    fun elapsedRealtime(): Long
}

internal class UsbExclusiveServiceKeepAliveOwner(
    private val scope: CoroutineScope,
    private val port: UsbExclusiveKeepAlivePort,
) {
    private var loopJob: Job? = null
    private var tick = 0L
    private var lastTickAtMs = 0L
    private var lastNativeHandle = 0L
    private var lastCompletedFrames = -1L
    private var lastSignalBytes = -1L
    private var lastZeroFillBytes = -1L
    private var lastOutputPeak = Float.NaN
    private var stallTicks = 0

    fun update(reason: String) {
        if (port.playbackActive()) {
            updateAnchor(reason)
            ensureLoop()
            return
        }
        port.stopAnchor("inactive:$reason")
        cancelLoop()
        resetInactiveBaseline()
        NPLogger.d("NERI-APS", "USB exclusive keepalive idle reason=$reason")
    }

    fun updateAnchor(reason: String) {
        val shouldRun = shouldRunUsbExclusiveBackgroundAudioAnchor(
            appInForeground = port.appInForeground(),
            serviceForeground = port.foregroundStarted(),
            usbExclusivePlaybackActive = port.playbackActive(),
        )
        if (shouldRun) port.startAnchor(reason) else port.stopAnchor(reason)
    }

    fun requestBackgroundForegroundReassert(reason: String) {
        scope.launch { reassertInBackground(reason) }
    }

    fun cancelLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun close() {
        cancelLoop()
        port.stopAnchor("service_destroy")
    }

    private fun reassertInBackground(reason: String) {
        if (port.appInForeground() || !port.playbackActive()) return
        val foregroundReason = "usb_background_transition:$reason"
        val foregroundReady = if (port.foregroundStarted()) {
            port.reassertForeground(foregroundReason)
        } else {
            port.ensureForeground()
        }
        if (!foregroundReady) {
            NPLogger.w("NERI-APS", "USB exclusive background foreground reassert failed: reason=$reason")
            return
        }
        updateAnchor(foregroundReason)
        cancelLoop()
        lastTickAtMs = 0L
        ensureLoop()
        runTick()
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            NPLogger.i("NERI-APS", "USB exclusive keepalive started")
            while (true) {
                delay(usbExclusiveKeepAliveIntervalMs(port.appInForeground()))
                if (!port.playbackActive()) {
                    NPLogger.i("NERI-APS", "USB exclusive keepalive stopped because playback is inactive")
                    resetInactiveBaseline()
                    loopJob = null
                    return@launch
                }
                runTick()
            }
        }
    }

    private fun resetInactiveBaseline() {
        tick = 0L
        lastTickAtMs = 0L
        lastNativeHandle = 0L
        lastCompletedFrames = -1L
        stallTicks = 0
    }

    private fun runTick() {
        val gapMs = recordTickGap(port.elapsedRealtime())
        val reasserted = reassertForegroundIfNeeded()
        if (!port.ensureForeground()) {
            port.onForegroundFailure("usb_keepalive")
            return
        }
        updateAnchor("usb_keepalive")
        port.refreshNative()
        port.maintainWakeLock()
        port.updatePlaybackPresentation()
        val nativeState = port.nativeState()
        val pathState = port.pathState()
        val message = keepAliveDiagnostic(nativeState, pathState, gapMs, reasserted)
        logKeepAliveDiagnostic(message, gapMs)
        recoverIfStalled(nativeState, pathState, message)
    }

    private fun recordTickGap(nowMs: Long): Long {
        val gapMs = if (lastTickAtMs > 0L) nowMs - lastTickAtMs else 0L
        tick += 1L
        lastTickAtMs = nowMs
        return gapMs
    }

    private fun reassertForegroundIfNeeded(): Boolean {
        val shouldReassert = shouldReassertUsbExclusiveForegroundService(
            appInForeground = port.appInForeground(),
            foregroundStarted = port.foregroundStarted(),
            usbExclusivePlaybackActive = port.playbackActive(),
        )
        return shouldReassert && port.reassertForeground("usb_keepalive")
    }

    private fun keepAliveDiagnostic(
        native: UsbExclusiveNativeState,
        path: UsbExclusiveAudioPathState,
        gapMs: Long,
        reasserted: Boolean,
    ): String {
        val level = "pcm=${native.pcmLevelBytes}/${native.pcmCapacityBytes} " +
            "free=${native.pcmFreeBytes} backpressureCurrentMs=${native.pcmBackpressureCurrentMs}"
        val signal = "signalFrames=${native.playerSignalFrames} silentFrames=${native.playerSilentFrames} " +
            "zeroFillBytes=${native.playerZeroFillBytes} peak=${native.lastOutputPeak} " +
            "channelPeaks=${native.lastChannel0OutputPeak}/${native.lastChannel1OutputPeak}"
        return "USB exclusive keepalive tick=$tick gapMs=$gapMs " +
            "path=${path.effectivePath} native=${native.source}/${native.streaming} " +
            "foregroundReasserted=$reasserted wakeLock=${port.wakeLockHeld()} " +
            "audioAnchor=${port.anchorDiagnostic()} completedFrames=${native.completedAudioFrames} " +
            "$level $signal"
    }

    private fun logKeepAliveDiagnostic(message: String, gapMs: Long) {
        if (gapMs > KEEPALIVE_STALL_WARN_MS) {
            NPLogger.w("NERI-APS", "$message possible_background_freeze=true")
        } else if (tick % KEEPALIVE_LOG_INTERVAL_TICKS == 0L) {
            NPLogger.i("NERI-APS", message)
        }
    }

    private fun recoverIfStalled(
        native: UsbExclusiveNativeState,
        path: UsbExclusiveAudioPathState,
        diagnostic: String,
    ) {
        val metrics = native.runtimeReport.usbRuntimeMetrics()
        val expected = nativePlaybackExpected(native, path)
        if (transportStoppedUnexpectedly(expected, native, metrics)) {
            stallTicks = 0
            NPLogger.w("NERI-APS", "USB exclusive keepalive found stopped failed transport; scheduling recovery. $diagnostic")
            port.recover("service_keepalive_transport_stopped")
            return
        }
        if (!expected || !native.streaming) {
            recordBaseline(native)
            stallTicks = 0
            return
        }
        evaluateProgress(native, metrics, diagnostic)
    }

    private fun nativePlaybackExpected(
        native: UsbExclusiveNativeState, path: UsbExclusiveAudioPathState,
    ): Boolean = port.usbPlaybackEnabled() && port.transportActive() &&
        path.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB &&
        path.sinkPlaying && native.source == "player_pcm"

    private fun transportStoppedUnexpectedly(
        expected: Boolean, native: UsbExclusiveNativeState, metrics: UsbExclusiveRuntimeMetrics,
    ): Boolean = expected && native.opened && !native.streaming && !native.paused &&
        !native.transitioning && metrics.transportFailed == true

    private fun recordBaseline(native: UsbExclusiveNativeState) {
        lastNativeHandle = native.handle
        lastCompletedFrames = native.completedAudioFrames
        lastSignalBytes = native.playerSignalBytes
        lastZeroFillBytes = native.playerZeroFillBytes
        lastOutputPeak = native.lastOutputPeak
    }

    private fun evaluateProgress(
        native: UsbExclusiveNativeState,
        metrics: UsbExclusiveRuntimeMetrics,
        diagnostic: String,
    ) {
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = lastNativeHandle,
            currentHandle = native.handle,
            previousCompletedFrames = lastCompletedFrames,
            currentCompletedFrames = native.completedAudioFrames,
            previousSignalBytes = lastSignalBytes,
            currentSignalBytes = native.playerSignalBytes,
            previousZeroFillBytes = lastZeroFillBytes,
            currentZeroFillBytes = native.playerZeroFillBytes,
            previousOutputPeak = lastOutputPeak,
            currentOutputPeak = native.lastOutputPeak,
            outputSampleRate = metrics.sampleRate ?: 0,
            outputFrameBytes = metrics.outputFrameBytes ?: 0,
            currentPcmLevelBytes = metrics.pcmLevelBytes ?: -1L,
            previousStallTicks = stallTicks,
            recoveryTicks = KEEPALIVE_STALL_RECOVERY_TICKS,
        )
        if (decision.progress == UsbExclusiveKeepAliveProgress.COUNTER_RESET) {
            NPLogger.i("NERI-APS", "USB exclusive keepalive reset frame baseline after native counter reset: " +
                "handle=${native.handle} previous=$lastCompletedFrames current=${native.completedAudioFrames}")
        }
        recordBaseline(native)
        stallTicks = decision.stallTicks
        if (!decision.shouldRecover) return
        stallTicks = 0
        NPLogger.w("NERI-APS", "USB exclusive keepalive detected stalled native frames; scheduling recovery. $diagnostic")
        port.recover("service_keepalive_stalled")
    }
}
