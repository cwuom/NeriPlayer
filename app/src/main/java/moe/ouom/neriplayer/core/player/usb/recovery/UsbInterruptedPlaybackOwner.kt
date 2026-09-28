package moe.ouom.neriplayer.core.player.usb.recovery

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.usb.resolveUsbExclusiveInterruptedPlaybackQueueIndex
import moe.ouom.neriplayer.core.player.policy.usb.shouldResumeUsbExclusivePlaybackAfterDeviceAttach
import kotlin.time.Duration.Companion.milliseconds

internal data class UsbInterruptedPlaybackIntent(
    val queueIndex: Int,
    val positionMs: Long,
    val requestToken: Long,
    val reason: String,
    val recordedAtMs: Long
)

internal data class UsbInterruptedPlaybackSnapshot(
    val usbEnabled: Boolean,
    val mixedPlaybackEnabled: Boolean,
    val resumeRequested: Boolean,
    val playerInitialized: Boolean,
    val queueSize: Int,
    val currentIndex: Int,
    val currentIndexMatchesCurrentSong: Boolean,
    val currentSongQueueIndex: Int,
    val requestToken: Long,
    val playJobActive: Boolean,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val reportedPlayWhenReady: Boolean,
    val reportedPlaying: Boolean
)

internal data class UsbReattachAvailability(
    val canRequestPermission: Boolean,
    val selectedOutputAvailable: Boolean,
    val selectedHostPermissionGranted: Boolean
)

internal interface UsbInterruptedPlaybackPort {
    fun snapshot(): UsbInterruptedPlaybackSnapshot
    fun reattachAvailability(): UsbReattachAvailability
    fun requestPermission(reason: String)
    fun nativeOpenGateActive(): Boolean
    fun requestLoudPlaybackConfirmation(onConfirm: () -> Unit, onCancel: () -> Unit): Boolean
    fun updateResumeRequested(requested: Boolean)
    fun resumeOnSystemRoute(intent: UsbInterruptedPlaybackIntent)
    fun resumeOnUsbRoute(intent: UsbInterruptedPlaybackIntent)
}

internal class UsbInterruptedPlaybackOwner(
    private val scope: CoroutineScope,
    private val port: UsbInterruptedPlaybackPort,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    initialIntent: UsbInterruptedPlaybackIntent? = null
) {
    var intent: UsbInterruptedPlaybackIntent? = initialIntent
        private set

    private var reattachJob: Job? = null

    fun cancelReattach() {
        reattachJob?.cancel()
        reattachJob = null
    }

    fun queueIndexForInterruption(): Int? {
        val state = port.snapshot()
        return resolveUsbExclusiveInterruptedPlaybackQueueIndex(
            currentQueueIndex = state.currentIndex,
            queueSize = state.queueSize,
            currentQueueIndexMatchesCurrentSong = state.currentIndexMatchesCurrentSong,
            currentSongQueueIndex = state.currentSongQueueIndex
        )
    }

    fun shouldKeepIntentAfterNativeFailure(): Boolean {
        val state = port.snapshot()
        if (!state.playerInitialized || state.queueSize == 0) return false
        return state.resumeRequested ||
            state.playJobActive ||
            state.playWhenReady ||
            state.isPlaying ||
            state.reportedPlayWhenReady ||
            state.reportedPlaying
    }

    fun rememberAfterNativeFailure(reason: String, positionMs: Long): Boolean {
        val queueIndex = queueIndexForInterruption()
        val shouldKeepIntent = shouldKeepIntentAfterNativeFailure()
        if (shouldKeepIntent && queueIndex != null) {
            remember(reason, queueIndex, positionMs)
            return true
        }
        if (shouldKeepIntent) {
            val state = port.snapshot()
            NPLogger.w(
                "NERI-UsbExclusive",
                "drop USB interrupted playback intent because the current queue item is unavailable: " +
                    "reason=$reason currentIndex=${state.currentIndex} queueSize=${state.queueSize}"
            )
            return false
        }
        clear("native_failure_idle:$reason")
        port.updateResumeRequested(false)
        return false
    }

    fun remember(reason: String, queueIndex: Int, positionMs: Long) {
        val state = port.snapshot()
        if (queueIndex !in 0 until state.queueSize) return
        val saved = UsbInterruptedPlaybackIntent(
            queueIndex = queueIndex,
            positionMs = positionMs.coerceAtLeast(0L),
            requestToken = state.requestToken,
            reason = reason,
            recordedAtMs = nowElapsedMs()
        )
        intent = saved
        port.updateResumeRequested(true)
        NPLogger.i(
            "NERI-UsbExclusive",
            "remember playback intent after USB exclusive interruption: reason=$reason " +
                "queueIndex=${saved.queueIndex} positionMs=${saved.positionMs} token=${saved.requestToken}"
        )
    }

    fun clear(reason: String) {
        val saved = intent ?: return
        intent = null
        NPLogger.d(
            "NERI-UsbExclusive",
            "clear interrupted USB playback intent: reason=$reason " +
                "queueIndex=${saved.queueIndex} positionMs=${saved.positionMs} failure=${saved.reason}"
        )
    }

    fun resumeOnSystemRouteIfNeeded(reason: String): Boolean {
        val saved = intent ?: return false
        return resumeSavedOnSystemRoute(reason, saved)
    }

    private fun resumeSavedOnSystemRoute(reason: String, saved: UsbInterruptedPlaybackIntent): Boolean {
        val state = port.snapshot()
        if (!systemRouteAvailable(state)) return false
        if (!queueIndexValid(saved, state)) {
            clear("invalid_index:$reason")
            return false
        }
        clear("resume:$reason")
        NPLogger.i(
            "NERI-UsbExclusive",
            "resume playback interrupted by USB exclusive failure: reason=$reason " +
                "queueIndex=${saved.queueIndex} positionMs=${saved.positionMs} failure=${saved.reason}"
        )
        port.updateResumeRequested(true)
        port.resumeOnSystemRoute(saved)
        return true
    }

    private fun systemRouteAvailable(state: UsbInterruptedPlaybackSnapshot): Boolean {
        if (state.usbEnabled) return false
        return state.queueSize > 0
    }

    private fun queueIndexValid(saved: UsbInterruptedPlaybackIntent, state: UsbInterruptedPlaybackSnapshot): Boolean =
        saved.queueIndex in 0 until state.queueSize

    fun scheduleResumeAfterDeviceAttach(reason: String) {
        val state = port.snapshot()
        if (!canScheduleReattach(state)) return
        val saved = intent ?: return
        val requestToken = saved.requestToken
        cancelReattach()
        reattachJob = scope.launch { retryDeviceReattach(reason, requestToken) }
    }

    private fun canScheduleReattach(state: UsbInterruptedPlaybackSnapshot): Boolean {
        if (!usbRouteExclusive(state)) return false
        return readyToResume(state)
    }

    private fun usbRouteExclusive(state: UsbInterruptedPlaybackSnapshot): Boolean {
        if (!state.usbEnabled) return false
        return !state.mixedPlaybackEnabled
    }

    private fun readyToResume(state: UsbInterruptedPlaybackSnapshot): Boolean {
        if (!state.playerInitialized) return false
        return state.resumeRequested
    }

    private suspend fun retryDeviceReattach(reason: String, requestToken: Long) {
        repeat(DEVICE_REATTACH_RECOVERY_MAX_ATTEMPTS) { attempt ->
            delay((reattachDelay(attempt)).milliseconds)
            val state = currentReattachState(requestToken) ?: return
            if (tryResumeAfterAttach(reason, state)) return
        }
        NPLogger.w("NERI-UsbExclusive", "USB device reattach recovery timed out: reason=$reason token=$requestToken")
    }

    private fun reattachDelay(attempt: Int): Long =
        if (attempt == 0) DEVICE_REATTACH_INITIAL_DELAY_MS else DEVICE_REATTACH_RETRY_DELAY_MS

    private fun currentReattachState(requestToken: Long): UsbInterruptedPlaybackSnapshot? {
        if (intent?.requestToken != requestToken) return null
        return availableReattachState()
    }

    private fun availableReattachState(): UsbInterruptedPlaybackSnapshot? {
        val state = port.snapshot()
        if (!canScheduleReattach(state)) return null
        return state
    }

    private fun tryResumeAfterAttach(reason: String, state: UsbInterruptedPlaybackSnapshot): Boolean {
        val availability = port.reattachAvailability()
        requestPermissionIfNeeded(reason, availability)
        if (!canResumeAfterAttach(state, availability, port.nativeOpenGateActive())) return false
        return resumePermittedReattach(reason)
    }

    private fun requestPermissionIfNeeded(reason: String, availability: UsbReattachAvailability) {
        if (availability.canRequestPermission) port.requestPermission("usb_device_reattach:$reason")
    }

    private fun resumePermittedReattach(reason: String): Boolean {
        if (requestConfirmationForReattach(reason)) return true
        return resumeOnUsbRouteAfterAttach(reason)
    }

    private fun canResumeAfterAttach(
        state: UsbInterruptedPlaybackSnapshot,
        availability: UsbReattachAvailability,
        nativeOpenGateActive: Boolean
    ): Boolean = shouldResumeUsbExclusivePlaybackAfterDeviceAttach(
        usbExclusivePlaybackEnabled = state.usbEnabled,
        allowMixedPlaybackEnabled = state.mixedPlaybackEnabled,
        hasInterruptedPlayback = intent != null,
        resumePlaybackRequested = state.resumeRequested,
        selectedUsbOutputAvailable = availability.selectedOutputAvailable,
        selectedUsbHostPermissionGranted = availability.selectedHostPermissionGranted,
        nativeOpenGateActive = nativeOpenGateActive
    )

    private fun requestConfirmationForReattach(reason: String): Boolean =
        port.requestLoudPlaybackConfirmation(
            onConfirm = { resumeOnUsbRouteAfterAttach(reason) },
            onCancel = {
                clear("usb_device_reattach_volume_cancel:$reason")
                port.updateResumeRequested(false)
            }
        )

    private fun resumeOnUsbRouteAfterAttach(reason: String): Boolean {
        val saved = intent ?: return false
        return resumeSavedOnUsbRoute(reason, saved)
    }

    private fun resumeSavedOnUsbRoute(reason: String, saved: UsbInterruptedPlaybackIntent): Boolean {
        val state = port.snapshot()
        if (!usbRouteExclusive(state)) return false
        if (!queueIndexValid(saved, state)) return false
        clear("usb_device_reattach:$reason")
        NPLogger.i(
            "NERI-UsbExclusive",
            "resume USB-exclusive playback after DAC reattach: reason=$reason " +
                "queueIndex=${saved.queueIndex} positionMs=${saved.positionMs}"
        )
        port.updateResumeRequested(true)
        port.resumeOnUsbRoute(saved)
        return true
    }

    private companion object {
        const val DEVICE_REATTACH_INITIAL_DELAY_MS = 750L
        const val DEVICE_REATTACH_RETRY_DELAY_MS = 1_000L
        const val DEVICE_REATTACH_RECOVERY_MAX_ATTEMPTS = 30
    }
}
