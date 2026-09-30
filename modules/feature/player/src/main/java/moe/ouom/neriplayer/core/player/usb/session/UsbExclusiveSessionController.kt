package moe.ouom.neriplayer.core.player.usb.session

import moe.ouom.neriplayer.core.player.usb.transport.isBenignBackpressure

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveRuntimeReportSamplingPolicy
import moe.ouom.neriplayer.core.player.policy.usb.isNativeCloseInFlightUsbExclusiveOpenGate
import moe.ouom.neriplayer.core.player.policy.usb.usbExclusiveTransferWindowDurationMs
import moe.ouom.neriplayer.core.player.lifecycle.scheduleUsbAudioSinkReconfiguration
import moe.ouom.neriplayer.core.player.usb.sink.ResolvedUsbOutputFormat
import moe.ouom.neriplayer.core.player.usb.sink.UsbExclusiveOutputFormatResolver
import moe.ouom.neriplayer.core.player.usb.sink.describeUsbInputFormat
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveIoGate
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeBridge
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRecoveryActionAckStatus
import moe.ouom.neriplayer.core.player.usb.transport.booleanField
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics
import moe.ouom.neriplayer.data.model.settings.usb.normalizeUsbExclusiveBackgroundBufferMs
import moe.ouom.neriplayer.data.model.settings.usb.normalizeUsbExclusiveForegroundBufferMs
import moe.ouom.neriplayer.core.logging.NPLogger

object UsbExclusiveSessionController {
    private const val TAG = "NERI-UsbExclusiveNative"
    private const val PLAYER_PCM_OPEN_MIN_INTERVAL_MS = 3_500L
    private const val PLAYER_PCM_RECONFIGURE_CLOSE_GATE_MS = 750L
    private const val PLAYER_PCM_FOCUS_COOLDOWN_MS = 8_000L
    private const val EMERGENCY_CLOSE_WAIT_MS = 1_500L
    private val transitionInFlight = AtomicBoolean(false)
    private val playerTransportCommandGate = UsbExclusiveTransportCommandGate()
    private val openGate = UsbExclusiveSessionOpenGate()
    private val ioGate = UsbExclusiveIoGate()
    private val focusSuppressed = AtomicBoolean(false)
    private val resources = UsbExclusiveSessionResources(
        ioGate,
        ::onNativeCloseComplete,
        AndroidUsbExclusiveSelectedDeviceKeyPort
    )
    private val sessionLock = ReentrantLock()
    @Volatile
    private var pendingPlayerPcmStopReason: String? = null
    @Volatile
    private var pendingPlayerPcmStopShouldBlockOpen = true
    @Volatile
    private var lastPlayerPcmWriteIssueLogAtMs = 0L
    @Volatile
    private var lastPlayerPcmBackpressureLogAtMs = 0L
    @Volatile
    private var lastPlayerPcmStateEmitAtMs = 0L
    @Volatile
    private var lastPlayerPcmRuntimeReportSampleAtMs = 0L
    private val latestPlayerPcmRuntime = AtomicReference(PlayerPcmRuntimeCache())
    private const val PCM_STATE_EMIT_INTERVAL_MS = 500L
    private const val PCM_RUNTIME_REPORT_SAMPLE_INTERVAL_MS = 2_000L

    private data class PlayerPcmRuntimeCache(
        val handle: Long = 0L,
        val report: String = "idle"
    )

    private val _state = MutableStateFlow(
        UsbExclusiveNativeState(
            available = UsbExclusiveNativeBridge.ensureLoaded()
        )
    )
    val state: StateFlow<UsbExclusiveNativeState> = _state.asStateFlow()

    fun nativeCloseInFlightCount(): Int = resources.nativeCloseInFlightCount

    internal fun canReusePlayerPcmOutput(
        currentOutputFormat: String,
        preferredOutputFormat: String
    ): Boolean = UsbExclusiveSessionReusePolicy.canReuseOutput(
        currentOutputFormat,
        preferredOutputFormat
    )

    internal fun canReuseResolvedPlayerPcmOutput(
        currentOutputFormat: String,
        currentRequestedOutputFormat: String,
        preferredOutputFormat: String,
        candidateDescriptions: Set<String>
    ): Boolean = UsbExclusiveSessionReusePolicy.canReuseResolvedOutput(
        currentOutputFormat,
        currentRequestedOutputFormat,
        preferredOutputFormat,
        candidateDescriptions
    )

    internal fun canReconfigurePlayerPcmOutputInPlace(
        state: UsbExclusiveNativeState
    ): Boolean = UsbExclusiveSessionReusePolicy.canReconfigureInPlace(state)

    internal fun canReusePlayerPcmSession(state: UsbExclusiveNativeState): Boolean =
        UsbExclusiveSessionReusePolicy.canReuseSession(state)

    internal fun shouldRetryAlternativePlayerPcmReconfigure(reason: String): Boolean =
        UsbExclusiveSessionReusePolicy.shouldRetryAlternativeReconfigure(reason)

    internal fun hasHealthyPlayerPcmSession(): Boolean =
        UsbExclusiveSessionReusePolicy.hasHealthySession(_state.value, ioGate.isOpen())

    fun handleUsbDeviceDetached(device: UsbDevice?): Boolean {
        if (!resources.matchesActiveDevice(device)) return false
        return detachActiveUsbDevice(device)
    }

    private fun detachActiveUsbDevice(device: UsbDevice?): Boolean {
        val reason = "usb_device_detached"
        ioGate.close()
        focusSuppressed.set(false)
        resources.markDeviceDetached(_state.value.handle)
        val closeRequest = sessionLock.withLock {
            openGate.markDeviceDetached()
            val request = stopInternalLocked(
                reason = reason,
                terminalError = "deviceOnline=false lastError=$reason"
            )
            blockNativeOpenLocked(reason, 18_000L)
            pendingPlayerPcmStopReason = null
            request
        }
        scheduleNativeClose(closeRequest)
        NPLogger.w(TAG, "handled USB detach ${resources.detachedDeviceDescription(device)}")
        return true
    }

    fun handleUsbDeviceAttached(context: Context, device: UsbDevice?): Boolean {
        val attachedDevice = device ?: return false
        return handlePresentUsbDeviceAttached(context.applicationContext, attachedDevice)
    }

    private fun handlePresentUsbDeviceAttached(
        context: Context,
        attachedDevice: UsbDevice
    ): Boolean {
        val hasAudioStreamingInterface = resources.isAudioStreamingDevice(attachedDevice)
        val matchesSelectedDevice = resources.matchesPreferredDevice(context, attachedDevice)
        val handled = sessionLock.withLock {
            admitUsbDeviceAttachLocked(hasAudioStreamingInterface, matchesSelectedDevice)
        }
        if (!handled) return false
        requestPlayerPcmReopenAfterClose("usb_device_attached")
        NPLogger.i(
            TAG,
            "handled USB audio attach deviceId=${attachedDevice.deviceId} " +
                "deviceName=${attachedDevice.deviceName} closeInFlight=${resources.nativeCloseInFlightCount}"
        )
        return true
    }

    private fun admitUsbDeviceAttachLocked(
        hasAudioStreamingInterface: Boolean,
        matchesSelectedDevice: Boolean
    ): Boolean {
        if (!openGate.handleDeviceAttached(hasAudioStreamingInterface, matchesSelectedDevice)) {
            return false
        }
        clearDetachedIdleStateLocked()
        return true
    }

    private fun clearDetachedIdleStateLocked() {
        val current = _state.value
        if (!current.isDetachedIdleState()) return
        _state.value = current.copy(
            transitioning = false,
            runtimeReport = attachedIdleRuntimeReport(resources.nativeCloseInFlightCount),
            lastError = null
        )
    }

    fun setPlayerFocusSuppressed(suppressed: Boolean, reason: String) {
        focusSuppressed.set(suppressed)
        val current = _state.value
        if (current.handle == 0L || current.source != "player_pcm" || !current.opened) return
        val applied = UsbExclusiveNativeBridge.setPlayerFocusMuted(current.handle, suppressed)
        NPLogger.d(
            TAG,
            "setPlayerFocusSuppressed(): suppressed=$suppressed applied=$applied reason=$reason"
        )
    }

    fun emergencyShutdown(reason: String) {
        try {
            forceStopAllSessions("emergency:$reason")
            awaitEmergencyClose()
        } finally {
            finishEmergencyShutdown(reason)
        }
    }

    private fun awaitEmergencyClose() {
        val deadlineMs = SystemClock.elapsedRealtime() + EMERGENCY_CLOSE_WAIT_MS
        while (shouldWaitForEmergencyClose(deadlineMs)) SystemClock.sleep(5L)
    }

    private fun shouldWaitForEmergencyClose(deadlineMs: Long): Boolean {
        if (SystemClock.elapsedRealtime() >= deadlineMs) return false
        return hasPendingNativeClose()
    }

    private fun hasPendingNativeClose(): Boolean {
        if (transitionInFlight.get()) return true
        return resources.hasNativeCloseInFlight
    }

    private fun finishEmergencyShutdown(reason: String) {
        if (!hasPendingNativeClose()) {
            UsbExclusiveWakeLock.release("emergency:$reason")
            return
        }
        NPLogger.w(
            TAG,
            "emergency shutdown keeps WakeLock until native close finishes reason=$reason " +
                "transition=${transitionInFlight.get()} closeInFlight=${resources.nativeCloseInFlightCount}"
        )
    }

    fun refresh(context: Context) {
        if (transitionInFlight.get() || playerTransportCommandGate.isHeld()) {
            val reason = if (playerTransportCommandGate.isHeld()) {
                "player_transport_command_in_flight"
            } else {
                "native_transition_in_flight"
            }
            markNativeTransitionInFlight(reason)
            return
        }
        if (!sessionLock.tryLock()) {
            markNativeRefreshDeferred()
            return
        }
        try {
            val current = _state.value
            val nowMs = SystemClock.elapsedRealtime()
            val openGateError = openGateErrorLocked(nowMs)
            val runtimeReport = if (current.handle != 0L) {
                UsbExclusiveNativeBridge.runtimeReport(current.handle)
            } else {
                val snapshot = UsbExclusiveDiagnostics.snapshot(context)
                val idleRuntimeReport = buildNativeIdleRuntimeReport(snapshot)
                if (openGateError != null) {
                    openGateError
                } else if (!current.lastError.isNullOrBlank() && current.lastError != "none") {
                    current.lastError.takeIf { it.isPersistentIdleNativeError() }
                        ?: idleRuntimeReport
                } else {
                    idleRuntimeReport
                }
            }
            if (current.source == "player_pcm" && current.handle != 0L) {
                rememberPlayerPcmRuntimeReport(current.handle, runtimeReport)
            } else {
                clearPlayerPcmRuntimeReport()
            }
            val lastError = if (current.handle != 0L) {
                current.lastError
            } else {
                runtimeReport.takeIf { it.isPersistentIdleNativeError() }
            }
            _state.value = current.copy(
                available = UsbExclusiveNativeBridge.ensureLoaded(),
                streaming = resolveUsbExclusiveStreamingState(
                    hasNativeHandle = current.handle != 0L,
                    runtimeRunning = runtimeReport.booleanField("running"),
                    currentStreaming = current.streaming
                ),
                paused = resolveUsbExclusivePausedState(
                    hasActivePlayerSession = current.source == "player_pcm" &&
                        current.handle != 0L,
                    runtimePaused = runtimeReport.booleanField("paused"),
                    currentPaused = current.paused
                ),
                transitioning = false,
                lastError = lastError,
                completedAudioFrames = if (current.handle != 0L) {
                    UsbExclusiveNativeBridge.completedAudioFrames(current.handle)
                } else {
                    0L
                },
                queuedAudioFrames = if (current.handle != 0L) {
                    UsbExclusiveNativeBridge.queuedPlayerFrames(current.handle)
                } else {
                    0L
                }
            ).withRuntimeReport(runtimeReport)
        } finally {
            sessionLock.unlock()
        }
    }

    fun startGeneratedTone(context: Context): Boolean {
        if (!beginToneOpenTransition()) return false
        val startedHandle = try {
            startToneAfterTransition(context.applicationContext)
        } finally {
            finishPlayerPcmOpenTransition()
        }
        return resources.isCurrentOpenHandle(startedHandle, _state.value)
    }

    private fun beginToneOpenTransition(): Boolean {
        if (rejectOpenDuringPlayerTransportCommand()) return false
        return acquireToneOpenTransition()
    }

    private fun acquireToneOpenTransition(): Boolean {
        if (!transitionInFlight.compareAndSet(false, true)) {
            _state.value = _state.value.copy(
                lastError = "transition_in_flight",
                runtimeReport = "transition_in_flight"
            )
            return false
        }
        return confirmPlayerPcmOpenTransition()
    }

    private class ToneStartAdmission(
        val allowed: Boolean,
        val closeRequest: UsbExclusiveSessionResources.CloseRequest?
    )

    private fun startToneAfterTransition(context: Context): Long {
        val admission = admitToneStart()
        if (!admission.allowed) return 0L
        return openToneAfterAdmission(context, admission.closeRequest)
    }

    private fun admitToneStart(): ToneStartAdmission = sessionLock.withLock {
        val current = _state.value
        if (UsbExclusiveSessionReusePolicy.hasOpenedPlayerSession(current)) {
            _state.value = current.copy(
                transitioning = false,
                runtimeReport = "player_pcm_session_active",
                lastError = "player_pcm_session_active"
            )
            return@withLock ToneStartAdmission(false, null)
        }
        ToneStartAdmission(true, stopInternalLocked("start_generated_tone"))
    }

    private fun openToneAfterAdmission(
        context: Context,
        closeRequest: UsbExclusiveSessionResources.CloseRequest?
    ): Long {
        if (closeRequest != null) return deferToneOpenDuringClose(closeRequest)
        return openToneAfterCloseCheck(context)
    }

    private fun deferToneOpenDuringClose(
        closeRequest: UsbExclusiveSessionResources.CloseRequest
    ): Long {
        scheduleNativeClose(closeRequest)
        sessionLock.withLock {
            blockNativeOpenLocked("start_generated_tone", PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
            _state.value = _state.value.copy(
                transitioning = false,
                runtimeReport = "native_open_deferred:start_generated_tone",
                lastError = "native_open_deferred:start_generated_tone"
            )
        }
        return 0L
    }

    private fun openToneAfterCloseCheck(context: Context): Long {
        val gateError = sessionLock.withLock {
            openGateErrorLocked(SystemClock.elapsedRealtime())
        }
        if (gateError != null) return deferToneOpenByGate(gateError)
        return openToneOnDevice(context)
    }

    private fun deferToneOpenByGate(error: String): Long {
        _state.value = _state.value.copy(
            transitioning = false,
            runtimeReport = error,
            lastError = error
        )
        return 0L
    }

    private fun openToneOnDevice(context: Context): Long {
        val openedDevice = resources.openDevice(
            context = context,
            selectedDeviceKey = resources.preferredDeviceKey(context)
        ) ?: return failMissingToneDevice()
        return openToneOnSelectedDevice(context, openedDevice)
    }

    private fun failMissingToneDevice(): Long {
        val error = "No permitted USB audio streaming device"
        _state.value = _state.value.copy(
            available = UsbExclusiveNativeBridge.ensureLoaded(),
            transitioning = false,
            runtimeReport = error,
            lastError = error
        )
        return 0L
    }

    private fun openToneOnSelectedDevice(
        context: Context,
        openedDevice: UsbExclusiveSessionResources.OpenedDevice
    ): Long {
        focusSuppressed.set(false)
        val result = resources.openTone(context, openedDevice)
        if (result.handle == 0L) return failNativeToneOpen(openedDevice, result.error)
        return commitOpenedTone(context, openedDevice, result.handle)
    }

    private fun failNativeToneOpen(
        openedDevice: UsbExclusiveSessionResources.OpenedDevice,
        error: String?
    ): Long {
        val openError = error ?: return 0L
        _state.value = _state.value.copy(
            available = UsbExclusiveNativeBridge.ensureLoaded(),
            transitioning = false,
            selectedDeviceName = openedDevice.device.productName,
            runtimeReport = openError,
            lastError = openError
        )
        return 0L
    }

    private fun commitOpenedTone(
        context: Context,
        openedDevice: UsbExclusiveSessionResources.OpenedDevice,
        handle: Long
    ): Long = sessionLock.withLock {
        resources.commitConnection(openedDevice.connection)
        UsbExclusiveWakeLock.acquire(context, "tone_started")
        _state.value = openedToneState(
            handle,
            openedDevice.displayName,
            UsbExclusiveNativeBridge.runtimeReport(handle)
        )
        handle
    }

    fun stopGeneratedTone() {
        blockWritesImmediately()
        if (!transitionInFlight.compareAndSet(false, true)) {
            pendingPlayerPcmStopReason = "stop_generated_tone"
            return
        }
        try {
            val current = _state.value
            if (current.source != "tone") {
                return
            }
            _state.value = current.copy(transitioning = true)
            val closeRequest = sessionLock.withLock {
                val request = stopInternalLocked("stop_generated_tone")
                _state.value = _state.value.copy(
                    transitioning = false
                )
                request
            }
            scheduleNativeClose(closeRequest)
            UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(
                PlayerManager.application,
                "stop_generated_tone"
            )
        } finally {
            transitionInFlight.set(false)
        }
    }

    fun openPlayerPcm(
        context: Context,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): Long {
        val inputFormat = describeUsbInputFormat(inputSampleRate, inputChannelCount, inputEncoding)
        NPLogger.d(TAG, "openPlayerPcm(): request input=$inputFormat")
        if (!beginPlayerPcmOpenTransition(inputFormat)) return 0L
        val openedHandle = try {
            resolveAndOpenPlayerPcm(
                context.applicationContext,
                inputSampleRate,
                inputChannelCount,
                inputEncoding,
                inputFormat
            )
        } finally {
            finishPlayerPcmOpenTransition()
        }
        return resources.committedPlayerHandle(openedHandle, _state.value)
    }

    private fun beginPlayerPcmOpenTransition(inputFormat: String): Boolean {
        if (rejectOpenDuringPlayerTransportCommand()) return false
        return acquirePlayerPcmOpenTransition(inputFormat)
    }

    private fun acquirePlayerPcmOpenTransition(inputFormat: String): Boolean {
        if (!transitionInFlight.compareAndSet(false, true)) {
            NPLogger.w(TAG, "openPlayerPcm(): native transition is in progress input=$inputFormat")
            _state.value = _state.value.copy(
                transitioning = true,
                runtimeReport = "transition_in_flight",
                lastError = "transition_in_flight"
            )
            return false
        }
        return confirmPlayerPcmOpenTransition()
    }

    private fun confirmPlayerPcmOpenTransition(): Boolean {
        if (playerTransportCommandGate.isHeld()) {
            transitionInFlight.set(false)
            markNativeTransitionInFlight("player_transport_command_in_flight")
            return false
        }
        _state.value = _state.value.copy(transitioning = true)
        return true
    }

    private fun rejectOpenDuringPlayerTransportCommand(): Boolean {
        if (!playerTransportCommandGate.isHeld()) return false
        markNativeTransitionInFlight("player_transport_command_in_flight")
        return true
    }

    private fun finishPlayerPcmOpenTransition() {
        drainPendingPlayerPcmStopIfNeeded()
        drainPendingPlayerPcmOpenBlockIfNeeded()
        transitionInFlight.set(false)
        val current = _state.value
        if (current.transitioning) _state.value = current.copy(transitioning = false)
    }

    private class PlayerPcmOpenRequest(
        val context: Context,
        val inputSampleRate: Int,
        val inputChannelCount: Int,
        val inputEncoding: Int,
        val inputFormat: String,
        val preferredOutput: ResolvedUsbOutputFormat,
        val outputCandidates: List<ResolvedUsbOutputFormat>
    ) {
        val candidateDescriptions: Set<String> = outputCandidates
            .map(ResolvedUsbOutputFormat::description)
            .toSet()
    }

    private fun resolveAndOpenPlayerPcm(
        context: Context,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int,
        inputFormat: String
    ): Long {
        val resolution = UsbExclusiveOutputFormatResolver.resolve(
            context = context,
            inputSampleRate = inputSampleRate,
            inputChannelCount = inputChannelCount,
            inputEncoding = inputEncoding,
            resolvedDeviceKey = resources.selectedDeviceKey
        )
        val preferred = resolution.format
            ?: return failPlayerPcmResolution(inputFormat, resolution.error)
        val request = PlayerPcmOpenRequest(
            context,
            inputSampleRate,
            inputChannelCount,
            inputEncoding,
            inputFormat,
            preferred,
            UsbExclusiveOutputFormatResolver.openCandidates(preferred)
        )
        NPLogger.d(
            TAG,
            "openPlayerPcm(): resolved output=${preferred.description} " +
                "candidates=${request.candidateDescriptions.joinToString()}"
        )
        return sessionLock.withLock { openResolvedPlayerPcmLocked(request) }
    }

    private fun failPlayerPcmResolution(inputFormat: String, error: String?): Long {
        val resolutionError = error ?: "output_format_unresolved"
        val closeRequest = sessionLock.withLock {
            val request = stopInternalLocked("output_format_unresolved:$resolutionError")
            _state.value = _state.value.withUnresolvedOutput(inputFormat, resolutionError)
            request
        }
        scheduleNativeClose(closeRequest)
        NPLogger.w(TAG, "resolveOutputFormat(): $resolutionError input=$inputFormat")
        return 0L
    }

    private fun openResolvedPlayerPcmLocked(request: PlayerPcmOpenRequest): Long {
        val current = _state.value
        val freshOpenReason = openGate.freshOpenReason(
            UsbExclusiveSessionReusePolicy.hasPlayerHandle(current)
        )
        val reused = tryReusePlayerPcmLocked(request, current, freshOpenReason)
        if (reused != 0L) return reused
        return openAfterReuseMissLocked(request, current, freshOpenReason)
    }

    private fun tryReusePlayerPcmLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState,
        freshOpenReason: String?
    ): Long {
        if (freshOpenReason != null) return 0L
        return tryReuseAllowedPlayerPcmLocked(request, current)
    }

    private fun tryReuseAllowedPlayerPcmLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState
    ): Long {
        if (!canReusePlayerPcmSession(current)) return 0L
        return prepareReusablePlayerPcmLocked(request, current)
    }

    private fun prepareReusablePlayerPcmLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState
    ): Long {
        if (!canReuseResolvedPlayerPcmOutput(
                current.outputFormat,
                current.requestedOutputFormat,
                request.preferredOutput.description,
                request.candidateDescriptions
            )
        ) return 0L
        return prepareMatchingPlayerPcmLocked(request, current)
    }

    private fun prepareMatchingPlayerPcmLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState
    ): Long {
        val report = resources.prepareExistingPlayerPcm(
            handle = current.handle,
            outputDescription = current.outputFormat,
            bufferDurationMs = request.preferredOutput.bufferDurationMs,
            inputSampleRate = request.inputSampleRate,
            inputChannelCount = request.inputChannelCount,
            inputEncoding = request.inputEncoding,
            appInForeground = PlayerManager.usbExclusiveAppInForeground
        ) ?: return 0L
        rememberPlayerPcmRuntimeReport(current.handle, report)
        _state.value = current.withReusedOutput(
            request.inputFormat,
            request.preferredOutput,
            report
        )
        NPLogger.d(
            TAG,
            "openPlayerPcm(): reused native handle=${current.handle} " +
                "output=${current.outputFormat} requested=${request.preferredOutput.description}"
        )
        return current.handle
    }

    private fun openAfterReuseMissLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState,
        freshOpenReason: String?
    ): Long {
        val reconfigured = tryReconfigureExistingPlayerPcmLocked(request, current, freshOpenReason)
        if (reconfigured != 0L) return reconfigured
        return openAfterReconfigureMissLocked(request, current, freshOpenReason)
    }

    private fun tryReconfigureExistingPlayerPcmLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState,
        freshOpenReason: String?
    ): Long {
        if (freshOpenReason != null) return 0L
        return tryReconfigureOnOpenGateLocked(request, current)
    }

    private fun tryReconfigureOnOpenGateLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState
    ): Long {
        if (!ioGate.isOpen()) return 0L
        return tryReconfigureEligiblePlayerPcmLocked(request, current)
    }

    private fun tryReconfigureEligiblePlayerPcmLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState
    ): Long {
        if (!canReconfigurePlayerPcmOutputInPlace(current)) return 0L
        return tryReconfigurePlayerPcmOutputLocked(
            current,
            request.preferredOutput,
            request.outputCandidates,
            request.inputSampleRate,
            request.inputChannelCount,
            request.inputEncoding
        )
    }

    private fun openAfterReconfigureMissLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState,
        freshOpenReason: String?
    ): Long {
        logPlayerPcmReuseMiss(request, current, freshOpenReason)
        val gateError = openGateErrorLocked(SystemClock.elapsedRealtime())
        if (gateError != null) return deferPlayerPcmByOpenGateLocked(request, current, gateError)
        return closePreviousAndOpenPlayerPcmLocked(request)
    }

    private fun logPlayerPcmReuseMiss(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState,
        freshOpenReason: String?
    ) {
        if (!UsbExclusiveSessionReusePolicy.hasPlayerHandle(current)) return
        logExistingPlayerPcmReuseMiss(request, current, freshOpenReason)
    }

    private fun logExistingPlayerPcmReuseMiss(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState,
        freshOpenReason: String?
    ) {
        if (freshOpenReason != null) {
            NPLogger.i(
                TAG,
                "openPlayerPcm(): skip native handle reuse because fresh open is " +
                    "required reason=$freshOpenReason handle=${current.handle}"
            )
            return
        }
        logExistingPlayerOutputChange(request, current)
    }

    private fun logExistingPlayerOutputChange(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState
    ) {
        if (!current.opened) return
        logOutputChangedForExistingPlayerPcm(request, current)
    }

    private fun logOutputChangedForExistingPlayerPcm(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState
    ) {
        if (!ioGate.isOpen()) return
        NPLogger.i(
            TAG,
            "openPlayerPcm(): skip native handle reuse because output changed " +
                "current=${current.outputFormat} " +
                "requestedBefore=${current.requestedOutputFormat} " +
                "requestedNow=${request.preferredOutput.description}"
        )
    }

    private fun deferPlayerPcmByOpenGateLocked(
        request: PlayerPcmOpenRequest,
        current: UsbExclusiveNativeState,
        gateError: String
    ): Long {
        if (isNativeCloseInFlightUsbExclusiveOpenGate(gateError)) {
            openGate.requestReopenAfterClose("native_close_in_flight")
        }
        val closeRequest = stopPlayerPcmForOpenGateLocked(current, gateError)
        publishDeferredPlayerPcmOpen(request, gateError)
        NPLogger.w(TAG, "openPlayerPcm(): deferred by native open gate: $gateError")
        scheduleNativeClose(closeRequest)
        return 0L
    }

    private fun stopPlayerPcmForOpenGateLocked(
        current: UsbExclusiveNativeState,
        gateError: String
    ): UsbExclusiveSessionResources.CloseRequest? {
        if (current.handle == 0L) return null
        return stopInternalLocked("open_gate:$gateError")
    }

    private fun closePreviousAndOpenPlayerPcmLocked(request: PlayerPcmOpenRequest): Long {
        val closeRequest = stopInternalLocked("open_player_pcm_reconfigure")
        if (closeRequest != null) return deferPlayerPcmDuringCloseLocked(request, closeRequest)
        return openNewPlayerPcmLocked(request)
    }

    private fun deferPlayerPcmDuringCloseLocked(
        request: PlayerPcmOpenRequest,
        closeRequest: UsbExclusiveSessionResources.CloseRequest
    ): Long {
        scheduleNativeClose(closeRequest)
        blockNativeOpenLocked(
            reason = "native_close_in_flight",
            delayMs = PLAYER_PCM_RECONFIGURE_CLOSE_GATE_MS,
            minimumDelayMs = PLAYER_PCM_RECONFIGURE_CLOSE_GATE_MS
        )
        publishDeferredPlayerPcmOpen(request, "native_open_deferred:native_close_in_flight")
        NPLogger.w(TAG, "openPlayerPcm(): deferred while previous native session closes")
        return 0L
    }

    private fun publishDeferredPlayerPcmOpen(request: PlayerPcmOpenRequest, error: String) {
        _state.value = _state.value.withDeferredOutput(
            request.inputFormat,
            request.preferredOutput.description,
            error
        )
    }

    private fun openNewPlayerPcmLocked(request: PlayerPcmOpenRequest): Long {
        val openedDevice = resources.openDevice(
            context = request.context,
            selectedDeviceKey = resources.preferredDeviceKey(request.context)
        ) ?: return failMissingPlayerPcmDevice()
        return openPlayerPcmOnDeviceLocked(request, openedDevice)
    }

    private fun failMissingPlayerPcmDevice(): Long {
        NPLogger.w(TAG, "openPlayerPcm(): no permitted USB audio streaming device")
        val error = "No permitted USB audio streaming device"
        _state.value = _state.value.copy(
            available = UsbExclusiveNativeBridge.ensureLoaded(),
            source = "idle",
            runtimeReport = error,
            lastError = error
        )
        return 0L
    }

    private fun openPlayerPcmOnDeviceLocked(
        request: PlayerPcmOpenRequest,
        openedDevice: UsbExclusiveSessionResources.OpenedDevice
    ): Long {
        focusSuppressed.set(false)
        val result = resources.openPlayerPcm(
            context = request.context,
            openedDevice = openedDevice,
            outputCandidates = request.outputCandidates,
            inputSampleRate = request.inputSampleRate,
            inputChannelCount = request.inputChannelCount,
            inputEncoding = request.inputEncoding,
            appInForeground = PlayerManager.usbExclusiveAppInForeground,
            allowMixedPlayback = PlayerManager.allowMixedPlaybackEnabled
        )
        if (result.handle == 0L) return failNativePlayerPcmOpen(openedDevice, result)
        return commitOpenedPlayerPcmLocked(request, openedDevice, result)
    }

    private fun failNativePlayerPcmOpen(
        openedDevice: UsbExclusiveSessionResources.OpenedDevice,
        result: UsbExclusiveSessionResources.PlayerOpenResult
    ): Long {
        val error = result.error ?: return 0L
        _state.value = _state.value.copy(
            available = UsbExclusiveNativeBridge.ensureLoaded(),
            source = "idle",
            selectedDeviceName = openedDevice.device.productName,
            runtimeReport = error,
            lastError = error
        )
        fuseFailedPlayerPcmOpen(error, result.shouldFuseOpen)
        return 0L
    }

    private fun fuseFailedPlayerPcmOpen(error: String, shouldFuse: Boolean) {
        if (shouldFuse) recordNativeOpenFailureLocked(error)
    }

    private fun commitOpenedPlayerPcmLocked(
        request: PlayerPcmOpenRequest,
        openedDevice: UsbExclusiveSessionResources.OpenedDevice,
        result: UsbExclusiveSessionResources.PlayerOpenResult
    ): Long {
        val handle = result.handle
        val activeOutput = requireNotNull(result.outputFormat)
        resources.commitConnection(openedDevice.connection)
        UsbExclusiveNativeBridge.setPlayerFocusMuted(handle, focusSuppressed.get())
        openGate.markOpened()
        val report = UsbExclusiveNativeBridge.runtimeReport(handle)
        rememberPlayerPcmRuntimeReport(handle, report)
        _state.value = openedPlayerPcmState(
            handle,
            openedDevice.displayName,
            request.inputFormat,
            activeOutput,
            request.preferredOutput,
            report
        )
        NPLogger.i(
            TAG,
            "openPlayerPcm(): opened handle=$handle device=${openedDevice.displayName} " +
                "runtime=${_state.value.runtimeReport}"
        )
        return handle
    }

    fun deferPlayerPcmOpen(
        reason: String,
        delayMs: Long = PLAYER_PCM_FOCUS_COOLDOWN_MS
    ) {
        val normalizedDelayMs = delayMs.coerceAtLeast(PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
        if (transitionInFlight.get()) {
            NPLogger.w(
                TAG,
                "deferPlayerPcmOpen(): queued while transition active reason=$reason " +
                    "delayMs=$normalizedDelayMs"
            )
            queuePendingPlayerPcmOpenBlock(reason, normalizedDelayMs)
            return
        }
        sessionLock.withLock {
            blockNativeOpenLocked(reason, normalizedDelayMs)
            val current = _state.value
            publishIdleOpenGateErrorLocked(reason)
            NPLogger.w(
                TAG,
                "deferPlayerPcmOpen(): reason=$reason delayMs=$normalizedDelayMs " +
                    "source=${current.source} handle=${current.handle}"
            )
        }
    }

    fun playerPcmOpenGateReason(): String? {
        if (transitionInFlight.get()) {
            return "native_transition_in_flight"
        }
        sessionLock.withLock {
            return openGateErrorLocked(SystemClock.elapsedRealtime())
        }
    }

    fun requireFreshPlayerPcmOpen(reason: String) {
        sessionLock.withLock {
            val current = _state.value
            if (!UsbExclusiveSessionReusePolicy.hasPlayerHandle(current)) return
            openGate.requireFreshOpen(reason)
            NPLogger.i(
                TAG,
                "requireFreshPlayerPcmOpen(): reason=$reason handle=${current.handle} " +
                    "streaming=${current.streaming} paused=${current.paused}"
            )
        }
    }

    fun clearRecoverablePlayerPcmOpenBlock(reason: String) {
        if (transitionInFlight.get()) {
            deferRecoverableOpenBlockClear(reason)
            return
        }
        clearRecoverableOpenBlockAfterTransition(reason)
    }

    private fun deferRecoverableOpenBlockClear(reason: String) {
        if (resources.nativeCloseInFlightCount > 0) {
            requestPlayerPcmReopenAfterClose("clear_open_block:$reason")
        }
        markNativeTransitionInFlight("clear_open_block_deferred:$reason")
    }

    private fun clearRecoverableOpenBlockAfterTransition(reason: String) {
        val shouldReopenAfterClose = sessionLock.withLock {
            clearRecoverableOpenBlockLocked(reason)
        }
        if (shouldReopenAfterClose) {
            requestPlayerPcmReopenAfterClose("clear_open_block:$reason")
        }
    }

    private fun clearRecoverableOpenBlockLocked(reason: String): Boolean {
        val waitingForNativeClose = resources.hasNativeCloseInFlight
        if (openGate.clearRecoverableUserActionBlock()) resetDeferredIdleOpenState(reason)
        return waitingForNativeClose
    }

    private fun resetDeferredIdleOpenState(reason: String) {
        NPLogger.d(TAG, "clearRecoverablePlayerPcmOpenBlock(): reason=$reason")
        val current = _state.value
        if (!current.isIdleOpenDeferred()) return
        _state.value = current.copy(runtimeReport = "native_idle", lastError = null)
    }

    fun configureActivePlayerBufferDuration(
        durationMs: Int,
        appInForeground: Boolean
    ): Boolean {
        val normalizedDurationMs = normalizeActivePlayerBufferDuration(durationMs, appInForeground)
        if (transitionInFlight.get()) {
            NPLogger.w(
                TAG,
                "configureActivePlayerBufferDuration(): deferred by transition durationMs=$normalizedDurationMs"
            )
            return false
        }
        return sessionLock.withLock {
            configureActivePlayerBufferLocked(normalizedDurationMs, appInForeground)
        }
    }

    private fun normalizeActivePlayerBufferDuration(durationMs: Int, appInForeground: Boolean): Int {
        return if (appInForeground) {
            normalizeUsbExclusiveForegroundBufferMs(durationMs)
        } else {
            normalizeUsbExclusiveBackgroundBufferMs(durationMs)
        }
    }

    private fun configureActivePlayerBufferLocked(durationMs: Int, appInForeground: Boolean): Boolean {
        val current = _state.value
        if (!UsbExclusiveSessionReusePolicy.hasOpenedPlayerSession(current)) {
            NPLogger.w(
                TAG,
                "configureActivePlayerBufferDuration(): no active player pcm " +
                    "durationMs=$durationMs source=${current.source} handle=${current.handle}"
            )
            return false
        }
        return configureOpenedPlayerBufferLocked(current, durationMs, appInForeground)
    }

    private fun configureOpenedPlayerBufferLocked(
        current: UsbExclusiveNativeState,
        durationMs: Int,
        appInForeground: Boolean
    ): Boolean {
        val configured = resources.configurePlayerBufferDuration(
            current.handle,
            durationMs,
            appInForeground
        )
        val report = UsbExclusiveNativeBridge.runtimeReport(current.handle)
        rememberPlayerPcmRuntimeReport(current.handle, report)
        if (!configured) return rejectPlayerBufferDuration(current, durationMs, report)
        _state.value = current.copy(bufferDurationMs = durationMs, lastError = null)
            .withRuntimeReport(report)
        NPLogger.d(
            TAG,
            "configureActivePlayerBufferDuration(): applied durationMs=$durationMs " +
                "handle=${current.handle}"
        )
        return true
    }

    private fun rejectPlayerBufferDuration(
        current: UsbExclusiveNativeState,
        durationMs: Int,
        report: String
    ): Boolean {
        NPLogger.w(
            TAG,
            "configureActivePlayerBufferDuration(): native rejected durationMs=$durationMs " +
                "handle=${current.handle} report=$report"
        )
        _state.value = current.copy(lastError = report).withRuntimeReport(report)
        return false
    }

    fun configureActivePlayerTransferWindow(
        durationMs: Int,
        appInForeground: Boolean
    ): Boolean {
        val normalizedDurationMs = usbExclusiveTransferWindowDurationMs(
            bufferDurationMs = durationMs,
            appInForeground = appInForeground
        )
        if (transitionInFlight.get()) return false
        sessionLock.withLock {
            val current = _state.value
            if (current.handle == 0L || current.source != "player_pcm" || !current.opened) {
                return false
            }
            val configured = UsbExclusiveNativeBridge.configurePlayerTransferWindow(
                current.handle,
                normalizedDurationMs
            )
            val runtimeReport = UsbExclusiveNativeBridge.runtimeReport(current.handle)
            rememberPlayerPcmRuntimeReport(current.handle, runtimeReport)
            _state.value = if (configured) {
                current.copy(lastError = null).withRuntimeReport(runtimeReport)
            } else {
                current.copy(lastError = runtimeReport).withRuntimeReport(runtimeReport)
            }
            if (configured) {
                NPLogger.d(
                    TAG,
                    "configureActivePlayerTransferWindow(): applied durationMs=$normalizedDurationMs " +
                        "handle=${current.handle}"
                )
            } else {
                NPLogger.w(
                    TAG,
                    "configureActivePlayerTransferWindow(): native rejected " +
                        "durationMs=$normalizedDurationMs handle=${current.handle} " +
                        "report=$runtimeReport"
                )
            }
            return configured
        }
    }

    fun writePlayerPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        volume: Float
    ): Int {
        if (!ioGate.tryEnterWrite()) return 0
        try {
            val current = _state.value
            if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
                return 0
            }
            val written = UsbExclusiveNativeBridge.writePlayerPcm(
                handle = handle,
                buffer = buffer,
                offset = offset,
                size = size,
                volume = volume
            )
            if (written <= 0 || written < size) {
                val nowMs = SystemClock.elapsedRealtime()
                val report = UsbExclusiveNativeBridge.runtimeReport(handle)
                rememberPlayerPcmRuntimeReport(handle, report)
                lastPlayerPcmRuntimeReportSampleAtMs = nowMs
                val metrics = report.usbRuntimeMetrics()
                _state.value = current.copy(
                    completedAudioFrames = UsbExclusiveNativeBridge.completedAudioFrames(handle),
                    queuedAudioFrames = UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
                ).withRuntimeReport(report)
                if (metrics.isBenignBackpressure) {
                    if (nowMs - lastPlayerPcmBackpressureLogAtMs >= 5_000L) {
                        lastPlayerPcmBackpressureLogAtMs = nowMs
                        NPLogger.d(
                            TAG,
                            "writePlayerPcm(): native queue backpressure handle=$handle " +
                                "requested=$size written=$written report=$report"
                        )
                    }
                } else if (nowMs - lastPlayerPcmWriteIssueLogAtMs >= 1_000L) {
                    lastPlayerPcmWriteIssueLogAtMs = nowMs
                    NPLogger.w(
                        TAG,
                        "writePlayerPcm(): short write handle=$handle requested=$size " +
                            "written=$written report=$report"
                    )
                }
            } else {
                val nowMs = SystemClock.elapsedRealtime()
                val shouldEmitState = nowMs - lastPlayerPcmStateEmitAtMs >=
                    PCM_STATE_EMIT_INTERVAL_MS
                val shouldSampleReport =
                    UsbExclusiveRuntimeReportSamplingPolicy.shouldSampleFullRuntimeReport(
                        nowMs = nowMs,
                        lastSampleAtMs = lastPlayerPcmRuntimeReportSampleAtMs,
                        intervalMs = PCM_RUNTIME_REPORT_SAMPLE_INTERVAL_MS
                    )
                if (shouldEmitState || shouldSampleReport) {
                    val updatedState = current.copy(
                        completedAudioFrames = UsbExclusiveNativeBridge.completedAudioFrames(handle),
                        queuedAudioFrames = UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
                    ).withLivePlayerPcmFreeBytes(
                        UsbExclusiveNativeBridge.playerPcmFreeBytes(handle)
                    )
                    if (shouldSampleReport) {
                        val report = UsbExclusiveNativeBridge.runtimeReport(handle)
                        rememberPlayerPcmRuntimeReport(handle, report)
                        lastPlayerPcmRuntimeReportSampleAtMs = nowMs
                        _state.value = updatedState.withRuntimeReport(report)
                    } else {
                        _state.value = updatedState
                    }
                }
                if (shouldEmitState) {
                    lastPlayerPcmStateEmitAtMs = nowMs
                }
            }
            return written
        } finally {
            ioGate.exitWrite()
        }
    }

    fun runtimeReportForWritePlanning(handle: Long): String {
        val current = _state.value
        if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
            return current.runtimeReport
        }
        val latest = latestPlayerPcmRuntime.get()
        return if (latest.handle == handle && latest.report.isNotBlank()) {
            latest.report
        } else {
            current.runtimeReport
        }
    }

    private fun rememberPlayerPcmRuntimeReport(handle: Long, report: String) {
        if (handle == 0L || report.isBlank()) return
        latestPlayerPcmRuntime.set(PlayerPcmRuntimeCache(handle, report))
        lastPlayerPcmRuntimeReportSampleAtMs = SystemClock.elapsedRealtime()
    }

    private fun clearPlayerPcmRuntimeReport() {
        latestPlayerPcmRuntime.set(PlayerPcmRuntimeCache())
    }

    private fun tryBeginPlayerTransportCommand(command: String, handle: Long): Boolean {
        if (!playerTransportCommandGate.tryAcquire()) {
            NPLogger.w(TAG, "$command(): transport command already in flight handle=$handle")
            return false
        }
        if (!transitionInFlight.get()) return true
        playerTransportCommandGate.release()
        NPLogger.w(TAG, "$command(): native transition already in flight handle=$handle")
        return false
    }

    private fun finishPlayerTransportCommand(reason: String, maintainWakeLock: Boolean) {
        playerTransportCommandGate.release()
        if (maintainWakeLock) {
            maintainWakeLock(PlayerManager.application, reason)
        }
    }

    fun playPlayerPcm(handle: Long): Boolean {
        if (!tryBeginPlayerTransportCommand("playPlayerPcm", handle)) return false
        var wakeLockAcquired = false
        try {
            val commandState = sessionLock.withLock {
                val current = _state.value
                if (
                    current.handle != handle ||
                    current.source != "player_pcm" ||
                    !current.opened ||
                    !ioGate.isOpen()
                ) {
                    NPLogger.w(
                        TAG,
                        "playPlayerPcm(): ignored stale handle=$handle " +
                            "currentHandle=${current.handle} source=${current.source} " +
                            "opened=${current.opened}"
                    )
                    return@withLock null
                }
                _state.value = current.copy(
                    transitioning = true,
                    runtimeReport = "player_pcm_start_in_flight"
                )
                current
            } ?: return false

            UsbExclusiveWakeLock.acquire(PlayerManager.application, "player_pcm_start")
            wakeLockAcquired = true
            val started = runCatching { UsbExclusiveNativeBridge.playPlayerPcm(handle) }
                .getOrDefault(false)
            val report = runCatching { UsbExclusiveNativeBridge.runtimeReport(handle) }
                .getOrDefault("native_play_runtime_unavailable")
            val completedFrames = runCatching {
                UsbExclusiveNativeBridge.completedAudioFrames(handle)
            }.getOrDefault(commandState.completedAudioFrames)
            val queuedFrames = runCatching {
                UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
            }.getOrDefault(commandState.queuedAudioFrames)
            val applied = sessionLock.withLock {
                val current = _state.value
                val matchesActiveSession = current.matchesPlayerSession(handle)
                if (matchesActiveSession) {
                    rememberPlayerPcmRuntimeReport(handle, report)
                    _state.value = current.copy(
                        streaming = started,
                        paused = false,
                        transitioning = false,
                        lastError = if (started) null else report,
                        completedAudioFrames = completedFrames,
                        queuedAudioFrames = queuedFrames
                    ).withRuntimeReport(report)
                }
                matchesActiveSession
            }
            if (started && applied) {
                NPLogger.d(TAG, "playPlayerPcm(): started handle=$handle report=$report")
            } else {
                NPLogger.w(
                    TAG,
                    "playPlayerPcm(): failed handle=$handle applied=$applied report=$report"
                )
            }
            return started && applied
        } finally {
            finishPlayerTransportCommand(
                reason = "player_pcm_start_complete",
                maintainWakeLock = wakeLockAcquired
            )
        }
    }

    fun pausePlayerPcm(handle: Long): Boolean {
        if (!tryBeginPlayerTransportCommand("pausePlayerPcm", handle)) return false
        var wakeLockAcquired = false
        try {
            val commandState = sessionLock.withLock {
                val current = _state.value
                if (!current.matchesPlayerSession(handle)) return@withLock null
                _state.value = current.copy(
                    transitioning = true,
                    runtimeReport = "player_pcm_pause_in_flight"
                )
                current
            } ?: return false

            UsbExclusiveWakeLock.acquire(PlayerManager.application, "player_pcm_pause")
            wakeLockAcquired = true
            val paused = runCatching { UsbExclusiveNativeBridge.pausePlayerPcm(handle) }
                .getOrDefault(false)
            val report = runCatching { UsbExclusiveNativeBridge.runtimeReport(handle) }
                .getOrDefault("native_pause_runtime_unavailable")
            val completedFrames = runCatching {
                UsbExclusiveNativeBridge.completedAudioFrames(handle)
            }.getOrDefault(commandState.completedAudioFrames)
            val queuedFrames = runCatching {
                UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
            }.getOrDefault(commandState.queuedAudioFrames)
            val applied = sessionLock.withLock {
                val current = _state.value
                val matchesActiveSession = current.matchesPlayerSession(handle)
                if (matchesActiveSession) {
                    rememberPlayerPcmRuntimeReport(handle, report)
                    _state.value = current.copy(
                        streaming = if (paused) {
                            false
                        } else {
                            report.booleanField("running") ?: current.streaming
                        },
                        paused = paused,
                        transitioning = false,
                        lastError = if (paused) null else report,
                        completedAudioFrames = completedFrames,
                        queuedAudioFrames = queuedFrames
                    ).withRuntimeReport(report)
                }
                matchesActiveSession
            }
            if (paused && applied) {
                NPLogger.d(TAG, "pausePlayerPcm(): paused handle=$handle report=$report")
            } else {
                NPLogger.w(
                    TAG,
                    "pausePlayerPcm(): failed handle=$handle applied=$applied report=$report"
                )
            }
            return paused && applied
        } finally {
            finishPlayerTransportCommand(
                reason = "player_pcm_pause_complete",
                maintainWakeLock = wakeLockAcquired
            )
        }
    }

    fun rearmPlayerPcmOutput(
        handle: Long,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): Boolean {
        val outputFormat = sessionLock.withLock { rearmOutputFormatLocked(handle) } ?: return false
        return rearmSelectedPlayerOutput(
            handle,
            outputFormat,
            inputSampleRate,
            inputChannelCount,
            inputEncoding
        )
    }

    private fun rearmOutputFormatLocked(handle: Long): ResolvedUsbOutputFormat? {
        val current = _state.value
        if (!UsbExclusiveSessionReusePolicy.canRearmPlayerSession(current, handle)) return null
        return UsbExclusiveOutputFormatResolver.outputFormatFromDescription(
            description = current.outputFormat,
            bufferDurationMs = current.bufferDurationMs
        )
    }

    private fun rearmSelectedPlayerOutput(
        handle: Long,
        outputFormat: ResolvedUsbOutputFormat,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): Boolean {
        val rearm = resources.rearmPlayerPcm(
            handle = handle,
            outputFormat = outputFormat,
            inputSampleRate = inputSampleRate,
            inputChannelCount = inputChannelCount,
            inputEncoding = inputEncoding,
            appInForeground = PlayerManager.usbExclusiveAppInForeground,
            focusSuppressed = focusSuppressed.get()
        )
        val committed = sessionLock.withLock {
            val latest = _state.value
            commitRearmedPlayerOutputLocked(
                latest,
                handle,
                outputFormat,
                inputSampleRate,
                inputChannelCount,
                inputEncoding,
                rearm
            )
        }
        if (!committed) return false
        return reportRearmedPlayerOutput(handle, outputFormat, rearm)
    }

    private fun commitRearmedPlayerOutputLocked(
        latest: UsbExclusiveNativeState,
        handle: Long,
        outputFormat: ResolvedUsbOutputFormat,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int,
        rearm: UsbExclusiveSessionResources.RearmResult
    ): Boolean {
        if (!latest.matchesPlayerSession(handle)) return false
        rememberPlayerPcmRuntimeReport(handle, rearm.report)
        _state.value = latest.withRearmedOutput(
            outputFormat,
            describeUsbInputFormat(inputSampleRate, inputChannelCount, inputEncoding),
            rearm
        )
        return true
    }

    private fun reportRearmedPlayerOutput(
        handle: Long,
        outputFormat: ResolvedUsbOutputFormat,
        rearm: UsbExclusiveSessionResources.RearmResult
    ): Boolean {
        if (!rearm.ready) {
            NPLogger.w(
                TAG,
                "rearmPlayerPcmOutput(): failed handle=$handle reconfigured=${rearm.reconfigured} " +
                    "bufferConfigured=${rearm.bufferConfigured} " +
                    "transferWindowConfigured=${rearm.transferWindowConfigured} " +
                    "prepared=${rearm.prepared} report=${rearm.report}"
            )
            return false
        }
        NPLogger.i(
            TAG,
            "rearmPlayerPcmOutput(): rearmed handle=$handle output=${outputFormat.description}"
        )
        return true
    }

    fun flushPlayerPcm(handle: Long): Boolean {
        if (!tryBeginPlayerTransportCommand("flushPlayerPcm", handle)) return false
        var wakeLockAcquired = false
        try {
            val commandState = sessionLock.withLock {
                val current = _state.value
                if (!current.matchesPlayerSession(handle)) return@withLock null
                _state.value = current.copy(
                    transitioning = true,
                    runtimeReport = "player_pcm_flush_in_flight"
                )
                current
            } ?: return false

            UsbExclusiveWakeLock.acquire(PlayerManager.application, "player_pcm_flush")
            wakeLockAcquired = true
            val flushed = runCatching { UsbExclusiveNativeBridge.flushPlayerPcm(handle) }
                .getOrDefault(false)
            val report = runCatching { UsbExclusiveNativeBridge.runtimeReport(handle) }
                .getOrDefault("native_flush_runtime_unavailable")
            val completedFrames = if (flushed) {
                0L
            } else {
                runCatching { UsbExclusiveNativeBridge.completedAudioFrames(handle) }
                    .getOrDefault(commandState.completedAudioFrames)
            }
            val queuedFrames = if (flushed) {
                0L
            } else {
                runCatching { UsbExclusiveNativeBridge.queuedPlayerFrames(handle) }
                    .getOrDefault(commandState.queuedAudioFrames)
            }
            val applied = sessionLock.withLock {
                val current = _state.value
                val matchesActiveSession = current.matchesPlayerSession(handle)
                if (matchesActiveSession) {
                    rememberPlayerPcmRuntimeReport(handle, report)
                    _state.value = current.copy(
                        streaming = report.booleanField("running") ?: if (flushed) {
                            false
                        } else {
                            commandState.streaming
                        },
                        paused = resolveUsbExclusivePausedState(
                            hasActivePlayerSession = true,
                            runtimePaused = report.booleanField("paused"),
                            currentPaused = if (flushed) false else commandState.paused
                        ),
                        transitioning = false,
                        lastError = if (flushed) null else report,
                        completedAudioFrames = completedFrames,
                        queuedAudioFrames = queuedFrames
                    ).withRuntimeReport(report)
                }
                matchesActiveSession
            }
            if (flushed && applied) {
                NPLogger.d(TAG, "flushPlayerPcm(): flushed handle=$handle report=$report")
            } else {
                NPLogger.w(
                    TAG,
                    "flushPlayerPcm(): failed handle=$handle applied=$applied report=$report"
                )
            }
            return flushed && applied
        } finally {
            finishPlayerTransportCommand(
                reason = "player_pcm_flush_complete",
                maintainWakeLock = wakeLockAcquired
            )
        }
    }

    fun setPlayerVolume(handle: Long, volume: Float): Boolean {
        val current = _state.value
        if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
            return false
        }
        return UsbExclusiveNativeBridge.setPlayerVolume(handle, volume)
    }

    fun completedAudioFrames(handle: Long): Long {
        val current = _state.value
        if (current.handle != handle || current.source != "player_pcm") return 0L
        return UsbExclusiveNativeBridge.completedAudioFrames(handle)
    }

    fun queuedPlayerFrames(handle: Long): Long {
        val current = _state.value
        if (current.handle != handle || current.source != "player_pcm") return 0L
        return UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
    }

    fun playerPcmFreeBytes(handle: Long): Long? {
        val current = _state.value
        if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
            return null
        }
        return UsbExclusiveNativeBridge.playerPcmFreeBytes(handle)
    }

    fun acknowledgeRecoveryAction(
        handle: Long,
        actionGeneration: Long,
        actionId: Long
    ): UsbExclusiveRecoveryActionAckStatus {
        val current = _state.value
        if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
            return UsbExclusiveRecoveryActionAckStatus.NoPending
        }
        val status = UsbExclusiveNativeBridge.acknowledgeRecoveryAction(
            handle = handle,
            actionGeneration = actionGeneration,
            actionId = actionId
        )
        NPLogger.d(
            TAG,
            "acknowledgeRecoveryAction(): handle=$handle generation=$actionGeneration " +
                "actionId=$actionId status=$status"
        )
        return status
    }

    internal fun maintainWakeLock(context: Context, reason: String) {
        val current = _state.value
        if (
            shouldHoldUsbExclusiveWakeLock(
                streaming = current.streaming,
                transitioning = isSessionTransitioning(current),
                transportCommandInFlight = playerTransportCommandGate.isHeld(),
                nativeCloseInFlightCount = resources.nativeCloseInFlightCount
            )
        ) {
            UsbExclusiveWakeLock.acquire(context, reason)
        } else {
            UsbExclusiveWakeLock.release("$reason:idle")
        }
    }

    private fun isSessionTransitioning(current: UsbExclusiveNativeState): Boolean {
        if (current.transitioning) return true
        return transitionInFlight.get()
    }

    fun closePlayerPcm(handle: Long) {
        val closeRequest = sessionLock.withLock {
            if (_state.value.handle == handle && _state.value.source == "player_pcm") {
                stopInternalLocked("close_player_pcm")
            } else {
                null
            }
        }
        scheduleNativeClose(closeRequest)
    }

    fun stopPlayerPcmSession(reason: String) {
        blockWritesImmediately()
        if (transitionInFlight.get()) {
            pendingPlayerPcmStopReason = reason
            pendingPlayerPcmStopShouldBlockOpen = true
            val current = _state.value
            _state.value = current.copy(
                transitioning = true,
                runtimeReport = "stop_deferred:$reason"
            )
            NPLogger.d(TAG, "stopPlayerPcmSession(): deferred while transition is active, reason=$reason")
            return
        }
        val closeRequest = sessionLock.withLock {
            val current = _state.value
            if (current.source != "player_pcm") {
                pendingPlayerPcmStopReason = null
                return@withLock null
            }
            pendingPlayerPcmStopReason = null
            NPLogger.d(TAG, "stopPlayerPcmSession(): reason=$reason")
            val request = stopInternalLocked("stop_player_pcm_session:$reason")
            blockNativeOpenLocked(reason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
            request
        }
        scheduleNativeClose(closeRequest)
    }

    fun forceStopAllSessions(reason: String, blockOpen: Boolean = true) {
        blockWritesImmediately()
        if (transitionInFlight.get()) {
            deferForceStopAllSessions(reason, blockOpen)
            return
        }
        val closeRequest = sessionLock.withLock {
            forceStopAllSessionsLocked(reason, blockOpen)
        }
        scheduleNativeClose(closeRequest)
    }

    private fun deferForceStopAllSessions(reason: String, blockOpen: Boolean) {
        pendingPlayerPcmStopReason = reason
        pendingPlayerPcmStopShouldBlockOpen = blockOpen
        if (blockOpen) {
            queuePendingPlayerPcmOpenBlock(reason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
        } else {
            openGate.clearPendingBlock()
        }
        NPLogger.w(
            TAG,
            "forceStopAllSessions(): deferred while transition is active, reason=$reason blockOpen=$blockOpen"
        )
    }

    private fun forceStopAllSessionsLocked(
        reason: String,
        blockOpen: Boolean
    ): UsbExclusiveSessionResources.CloseRequest? {
        pendingPlayerPcmStopReason = null
        pendingPlayerPcmStopShouldBlockOpen = true
        NPLogger.w(
            TAG,
            "forceStopAllSessions(): reason=$reason source=${_state.value.source} " +
                "handle=${_state.value.handle} opened=${_state.value.opened} blockOpen=$blockOpen"
        )
        val request = stopInternalLocked("force_stop_all:$reason")
        if (blockOpen) blockNativeOpenLocked(reason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
        return request
    }

    fun refreshRuntime(handle: Long) {
        if (handle == 0L) return
        sessionLock.withLock {
            val current = _state.value
            if (current.handle != handle) return
            val runtimeReport = UsbExclusiveNativeBridge.runtimeReport(handle)
            if (current.source == "player_pcm") {
                rememberPlayerPcmRuntimeReport(handle, runtimeReport)
            }
            _state.value = current.copy(
                completedAudioFrames = UsbExclusiveNativeBridge.completedAudioFrames(handle),
                queuedAudioFrames = UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
            ).withRuntimeReport(runtimeReport)
        }
    }

    private fun tryReconfigurePlayerPcmOutputLocked(
        current: UsbExclusiveNativeState,
        preferredOutput: ResolvedUsbOutputFormat,
        outputCandidates: List<ResolvedUsbOutputFormat>,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): Long {
        val result = resources.tryReconfigurePlayerPcm(
            handle = current.handle,
            currentOutputDescription = current.outputFormat,
            outputCandidates = outputCandidates,
            inputSampleRate = inputSampleRate,
            inputChannelCount = inputChannelCount,
            inputEncoding = inputEncoding,
            appInForeground = PlayerManager.usbExclusiveAppInForeground,
            focusSuppressed = focusSuppressed.get(),
            shouldRetry = ::shouldRetryAlternativePlayerPcmReconfigure,
            onRuntimeReport = { report -> rememberPlayerPcmRuntimeReport(current.handle, report) }
        )
        if (!result.complete) return publishFailedPlayerReconfigure(current, result.report)
        val output = result.requireOutputFormat()
        val report = result.requireReport()
        _state.value = current.withReconfiguredOutput(
            output,
            preferredOutput,
            describeUsbInputFormat(inputSampleRate, inputChannelCount, inputEncoding),
            report
        )
        NPLogger.i(
            TAG,
            "openPlayerPcm(): reconfigured native handle=${current.handle} " +
                "output=${output.description} requested=${preferredOutput.description}"
        )
        return current.handle
    }

    private fun publishFailedPlayerReconfigure(
        current: UsbExclusiveNativeState,
        report: String?
    ): Long {
        report?.let { failureReport ->
            _state.value = current.copy(lastError = failureReport).withRuntimeReport(failureReport)
        }
        return 0L
    }

    private fun stopInternalLocked(
        reason: String = "stop_internal"
    ): UsbExclusiveSessionResources.CloseRequest? {
        return stopInternalLocked(reason, terminalError = null)
    }

    private fun stopInternalLocked(
        reason: String,
        terminalError: String?
    ): UsbExclusiveSessionResources.CloseRequest? {
        val current = _state.value
        val connection = resources.takeActiveConnection()
        ioGate.close()
        focusSuppressed.set(false)
        resources.stopNativeHandle(current, reason)
        resources.clearActiveDeviceIdentity()
        _state.value = _state.value.afterNativeStop(terminalError)
        clearPlayerPcmRuntimeReport()
        if (current.handle == 0L) {
            resources.closeIdleConnection(connection)
            UsbExclusiveWakeLock.release(reason)
            return null
        }
        return UsbExclusiveSessionResources.CloseRequest(
            handle = current.handle,
            connection = connection,
            source = current.source,
            reason = reason
        )
    }

    private fun scheduleNativeClose(request: UsbExclusiveSessionResources.CloseRequest?) {
        resources.scheduleClose(request)
    }

    private fun onNativeCloseComplete(
        request: UsbExclusiveSessionResources.CloseRequest,
        remainingCloses: Int
    ) {
        requestReopenAfterReconfigureClose(request)
        handleCompletedNativeClose(remainingCloses)
        reevaluateWakeLockAfterNativeClose(request)
    }

    private fun requestReopenAfterReconfigureClose(
        request: UsbExclusiveSessionResources.CloseRequest
    ) {
        if (openGate.shouldReopenAfterReconfigureClose(
                request,
                { PlayerManager.usbExclusivePlaybackEnabled },
                { PlayerManager.isTransportActiveWithoutInitialization() }
            )
        ) {
            openGate.requestReopenAfterClose("open_player_pcm_reconfigure")
        }
    }

    private fun handleCompletedNativeClose(remainingCloses: Int) {
        if (remainingCloses != 0) return
        sessionLock.withLock { clearCompletedNativeCloseGateLocked() }
        trySchedulePendingPlayerPcmReopen()
    }

    private fun reevaluateWakeLockAfterNativeClose(
        request: UsbExclusiveSessionResources.CloseRequest
    ) {
        runCatching {
            maintainWakeLock(PlayerManager.application, "${request.reason}:close_complete")
        }.onFailure { error ->
            NPLogger.w(TAG, "failed to re-evaluate USB WakeLock after native close", error)
        }
    }

    private fun drainPendingPlayerPcmStopIfNeeded() {
        val closeRequest = sessionLock.withLock {
            val pendingReason = pendingPlayerPcmStopReason ?: return
            val shouldBlockOpen = pendingPlayerPcmStopShouldBlockOpen
            pendingPlayerPcmStopReason = null
            pendingPlayerPcmStopShouldBlockOpen = true
            applyPendingPlayerPcmStopLocked(pendingReason, shouldBlockOpen)
        }
        scheduleNativeClose(closeRequest)
    }

    private fun applyPendingPlayerPcmStopLocked(
        pendingReason: String,
        shouldBlockOpen: Boolean
    ): UsbExclusiveSessionResources.CloseRequest? {
        val request = stopPendingPlayerPcmSessionLocked(pendingReason)
        if (shouldBlockOpen) blockNativeOpenLocked(pendingReason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
        _state.value = _state.value.copy(
            transitioning = false,
            runtimeReport = "stop_applied:$pendingReason"
        )
        return request
    }

    private fun stopPendingPlayerPcmSessionLocked(
        reason: String
    ): UsbExclusiveSessionResources.CloseRequest? {
        if (!resources.hasCloseableSession(_state.value.handle)) return null
        NPLogger.d(TAG, "drainPendingPlayerPcmStopIfNeeded(): reason=$reason")
        return stopInternalLocked("pending_stop:$reason")
    }

    private fun drainPendingPlayerPcmOpenBlockIfNeeded() {
        sessionLock.withLock {
            val block = openGate.takePendingBlock() ?: return
            blockNativeOpenLocked(block.reason, block.delayMs)
            publishIdleOpenGateErrorLocked(block.reason)
            NPLogger.d(
                TAG,
                "drainPendingPlayerPcmOpenBlockIfNeeded(): reason=${block.reason} delayMs=${block.delayMs}"
            )
        }
    }

    private fun publishIdleOpenGateErrorLocked(reason: String) {
        val current = _state.value
        if (!current.canPublishIdleOpenGateError()) return
        val error = deferredOpenGateErrorLocked(reason)
        _state.value = current.copy(
            transitioning = false,
            runtimeReport = error,
            lastError = error
        )
    }

    private fun deferredOpenGateErrorLocked(reason: String): String =
        openGateErrorLocked(SystemClock.elapsedRealtime()) ?: "native_open_deferred:$reason"

    private fun queuePendingPlayerPcmOpenBlock(reason: String, delayMs: Long) {
        openGate.queueBlock(reason, delayMs)
        val current = _state.value
        _state.value = current.copy(
            transitioning = true,
            runtimeReport = "native_open_deferred:$reason",
            lastError = "native_open_deferred:$reason"
        )
        NPLogger.d(TAG, "queuePendingPlayerPcmOpenBlock(): reason=$reason delayMs=$delayMs")
    }

    private fun markNativeTransitionInFlight(runtimeReport: String) {
        val current = _state.value
        _state.value = current.copy(
            transitioning = true,
            runtimeReport = runtimeReport,
            runtimeReportValid = false,
            runtimeReportInvalidReason = runtimeReport
        )
    }

    private fun markNativeRefreshDeferred() {
        val current = _state.value
        _state.value = current.copy(
            runtimeReport = "native_refresh_deferred",
            runtimeReportValid = false,
            runtimeReportInvalidReason = "native_refresh_deferred"
        )
    }

    private fun openGateErrorLocked(nowMs: Long): String? {
        return openGate.error(nowMs, resources.nativeCloseInFlightCount)
    }

    private fun requestPlayerPcmReopenAfterClose(reason: String) {
        openGate.requestReopenAfterClose(reason)
        trySchedulePendingPlayerPcmReopen()
    }

    private fun trySchedulePendingPlayerPcmReopen() {
        val reason = openGate.takeReopenAfterClose(resources.nativeCloseInFlightCount) ?: return
        schedulePendingPlayerPcmReopen(reason)
    }

    private fun schedulePendingPlayerPcmReopen(reason: String) {
        if (!PlayerManager.usbExclusivePlaybackEnabled) {
            NPLogger.d(TAG, "drop pending USB reopen while exclusive playback is disabled reason=$reason")
            return
        }
        NPLogger.i(TAG, "native close gate cleared, trigger one USB reopen reason=$reason")
        PlayerManager.scheduleUsbAudioSinkReconfiguration(
            reason = openGate.reconfigurationReasonForReopen(reason),
            allowWhilePlaybackActive = true,
            bypassCooldown = true
        )
    }

    private fun clearCompletedNativeCloseGateLocked() {
        if (resources.nativeCloseInFlightCount > 0) return
        clearNativeCloseGateWhenIdleLocked()
    }

    private fun clearNativeCloseGateWhenIdleLocked() {
        openGate.clearCompletedNativeCloseGate(resources.nativeCloseInFlightCount)
        val current = _state.value
        if (!current.isWaitingForNativeCloseGate()) return
        publishClearedNativeCloseGateLocked(current)
    }

    private fun publishClearedNativeCloseGateLocked(current: UsbExclusiveNativeState) {
        val nextGate = openGateErrorLocked(SystemClock.elapsedRealtime())
        _state.value = current.copy(
            transitioning = false,
            runtimeReport = nextGate ?: "native_idle",
            lastError = nextGate
        )
    }

    private fun blockNativeOpenLocked(
        reason: String,
        delayMs: Long,
        minimumDelayMs: Long = PLAYER_PCM_OPEN_MIN_INTERVAL_MS
    ) {
        if (openGate.block(reason, delayMs, SystemClock.elapsedRealtime(), minimumDelayMs)) {
            NPLogger.w(TAG, "blockNativeOpenLocked(): reason=$reason delayMs=$delayMs")
        }
    }

    private fun recordNativeOpenFailureLocked(reason: String) {
        openGate.recordNativeOpenFailure(reason, SystemClock.elapsedRealtime())
    }

    private fun blockWritesImmediately() {
        ioGate.close()
        val handle = _state.value.handle
        if (handle != 0L) {
            runCatching { UsbExclusiveNativeBridge.stop(handle) }
        }
    }

}
