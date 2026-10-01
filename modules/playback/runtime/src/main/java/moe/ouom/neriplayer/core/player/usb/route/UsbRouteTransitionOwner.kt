package moe.ouom.neriplayer.core.player.usb.route

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import kotlin.time.Duration.Companion.milliseconds

internal class UsbRouteTransitionOwner(
    private val scope: CoroutineScope,
    private val onToggleTimeout: (String) -> Unit,
    initialGeneration: Long = 0L,
    initialRecoveryAttempts: Int = 0
) {
    @Volatile
    var generation = initialGeneration
        private set

    @Volatile
    var systemAudioReleaseInProgress = false
        private set

    @Volatile
    var toggleActive = false
        private set

    @Volatile
    var toggleReason = ""
        private set

    var recoveryAttempts = initialRecoveryAttempts
        private set

    private var systemAudioReleaseJob: Job? = null
    private var systemAudioWatchdogJob: Job? = null
    private var toggleJob: Job? = null
    private var openGatePlaybackJob: Job? = null

    fun advanceGeneration(): Long = ++generation

    fun resetRecoveryAttempts() {
        recoveryAttempts = 0
    }

    fun claimRecoveryAttempt(maxAttempts: Int): Int? {
        if (recoveryAttempts >= maxAttempts) return null
        recoveryAttempts += 1
        return recoveryAttempts
    }

    fun beginUiToggle(targetEnabled: Boolean): Boolean {
        if (toggleActive) return false
        toggleActive = true
        toggleReason = if (targetEnabled) "usb_exclusive_enabled" else "usb_exclusive_disabled"
        scheduleToggleTimeout(
            toggleReason,
            "unlock stale USB toggle transition before settings flow update",
            "usb_toggle_ui_timeout:$toggleReason"
        )
        return true
    }

    fun beginSettingToggle(hasMediaToReconfigure: Boolean, enabled: Boolean) {
        toggleActive = hasMediaToReconfigure
        toggleReason = if (hasMediaToReconfigure) {
            if (enabled) "usb_exclusive_enabled" else "usb_exclusive_disabled"
        } else {
            ""
        }
        toggleJob?.cancel()
        toggleJob = null
        if (hasMediaToReconfigure) {
            scheduleToggleTimeout(
                toggleReason,
                "forcing USB toggle transition unlock after timeout",
                "usb_toggle_timeout"
            )
        }
    }

    private fun scheduleToggleTimeout(reason: String, logMessage: String, preparingReason: String) {
        toggleJob?.cancel()
        toggleJob = scope.launch {
            delay(TOGGLE_TIMEOUT_MS.milliseconds)
            if (!toggleActive || toggleReason != reason) return@launch
            NPLogger.w("NERI-UsbExclusive", "$logMessage: reason=$reason")
            clearToggle()
            onToggleTimeout(preparingReason)
        }
    }

    fun clearToggle() {
        toggleActive = false
        toggleReason = ""
        toggleJob?.cancel()
        toggleJob = null
    }

    fun beginSystemAudioRelease() {
        systemAudioReleaseJob?.cancel()
        systemAudioReleaseJob = null
        systemAudioReleaseInProgress = true
    }

    fun finishSystemAudioRelease() {
        systemAudioReleaseInProgress = false
    }

    fun launchSystemAudioRelease(block: suspend () -> Unit) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (systemAudioReleaseJob === job) {
                    systemAudioReleaseJob = null
                    systemAudioReleaseInProgress = false
                }
            }
        }
        systemAudioReleaseJob = job
        job.start()
    }

    fun launchUntrackedRelease(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    fun cancelSystemAudioRelease(reason: String) {
        cancelSystemAudioWatchdog()
        val job = systemAudioReleaseJob
        if (job?.isActive == true) {
            NPLogger.i("NERI-UsbExclusive", "cancel stale Android audio release before USB activation: reason=$reason")
            job.cancel()
        }
        systemAudioReleaseJob = null
        systemAudioReleaseInProgress = false
    }

    fun hasActiveSystemAudioRelease(): Boolean = systemAudioReleaseJob?.isActive == true

    fun cancelSystemAudioWatchdog() {
        systemAudioWatchdogJob?.cancel()
        systemAudioWatchdogJob = null
    }

    fun launchSystemAudioWatchdog(delayMs: Long, block: suspend () -> Unit) {
        cancelSystemAudioWatchdog()
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs.milliseconds)
            if (systemAudioWatchdogJob !== job) return@launch
            systemAudioWatchdogJob = null
            block()
        }
        systemAudioWatchdogJob = job
        job.start()
    }

    fun cancelOpenGatePlayback() {
        openGatePlaybackJob?.cancel()
        openGatePlaybackJob = null
    }

    fun launchOpenGatePlayback(block: suspend () -> Unit) {
        cancelOpenGatePlayback()
        openGatePlaybackJob = scope.launch { block() }
    }

    fun cancelRouteJobs() {
        systemAudioReleaseJob?.cancel()
        systemAudioReleaseJob = null
        cancelSystemAudioWatchdog()
        toggleJob?.cancel()
        toggleJob = null
        cancelOpenGatePlayback()
        systemAudioReleaseInProgress = false
        toggleActive = false
        toggleReason = ""
    }

    private companion object {
        const val TOGGLE_TIMEOUT_MS = 8_000L
    }
}
