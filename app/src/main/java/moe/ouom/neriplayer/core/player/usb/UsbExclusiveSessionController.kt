package moe.ouom.neriplayer.core.player.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.player.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnosticsSnapshot
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
import moe.ouom.neriplayer.data.settings.normalizeUsbExclusiveBackgroundBufferMs
import moe.ouom.neriplayer.data.settings.normalizeUsbExclusiveForegroundBufferMs
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.toUsbExclusivePreferences
import moe.ouom.neriplayer.util.NPLogger

object UsbExclusiveSessionController {
    private const val TAG = "NERI-UsbExclusiveNative"
    private const val PLAYER_PCM_OPEN_MIN_INTERVAL_MS = 3_500L
    private const val PLAYER_PCM_RECONFIGURE_CLOSE_GATE_MS = 180L
    private const val PLAYER_PCM_FOCUS_COOLDOWN_MS = 8_000L
    private const val PLAYER_PCM_FAILURE_FUSE_MS = 18_000L
    private const val PLAYER_PCM_TRANSIENT_FUSE_MS = 5_000L
    private const val EMERGENCY_CLOSE_WAIT_MS = 1_500L
    private const val NO_ACTIVE_USB_DEVICE_ID = -1
    private val transitionInFlight = AtomicBoolean(false)
    private val nativeCloseInFlight = AtomicInteger(0)
    private val ioGate = UsbExclusiveIoGate()
    private val focusSuppressed = AtomicBoolean(false)
    private val activeDeviceId = AtomicInteger(NO_ACTIVE_USB_DEVICE_ID)
    private val activeDeviceName = AtomicReference<String?>(null)
    private val nativeCloseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NeriUsbExclusiveClose").apply { isDaemon = true }
    }
    private val sessionLock = ReentrantLock()
    private var activeConnection: UsbDeviceConnection? = null
    @Volatile
    private var pendingPlayerPcmStopReason: String? = null
    @Volatile
    private var pendingPlayerPcmStopShouldBlockOpen = true
    @Volatile
    private var pendingPlayerPcmOpenBlock: PendingPlayerPcmOpenBlock? = null
    private var lastPlayerPcmNativeOpenAtMs = 0L
    private var lastPlayerPcmNativeCloseAtMs = 0L
    private var playerPcmOpenBlockedUntilMs = 0L
    private var playerPcmOpenBlockReason = ""
    private var playerPcmFreshOpenRequiredReason: String? = null
    @Volatile
    private var lastPlayerPcmWriteIssueLogAtMs = 0L
    @Volatile
    private var lastPlayerPcmBackpressureLogAtMs = 0L
    @Volatile
    private var lastPlayerPcmStateEmitAtMs = 0L
    private val latestPlayerPcmRuntime = AtomicReference(PlayerPcmRuntimeCache())
    private const val PCM_STATE_EMIT_INTERVAL_MS = 500L

    private data class PlayerPcmRuntimeCache(
        val handle: Long = 0L,
        val report: String = "idle"
    )

    private data class PendingPlayerPcmOpenBlock(
        val reason: String,
        val delayMs: Long
    )

    private data class NativeCloseRequest(
        val handle: Long,
        val connection: UsbDeviceConnection?,
        val source: String,
        val reason: String
    )

    private val _state = MutableStateFlow(
        UsbExclusiveNativeState(
            available = UsbExclusiveNativeBridge.ensureLoaded()
        )
    )
    val state: StateFlow<UsbExclusiveNativeState> = _state.asStateFlow()

    fun nativeCloseInFlightCount(): Int = nativeCloseInFlight.get()

    fun handleUsbDeviceDetached(device: UsbDevice?): Boolean {
        if (!matchesActiveDevice(device)) return false
        val reason = "usb_device_detached"
        ioGate.close()
        focusSuppressed.set(false)
        val currentHandle = _state.value.handle
        if (currentHandle != 0L) {
            runCatching { UsbExclusiveNativeBridge.markDeviceDetached(currentHandle) }
        }
        val closeRequest = sessionLock.withLock {
            val request = stopInternalLocked(
                reason = reason,
                terminalError = "deviceOnline=false lastError=$reason"
            )
            blockNativeOpenLocked(reason, PLAYER_PCM_FAILURE_FUSE_MS)
            pendingPlayerPcmStopReason = null
            request
        }
        scheduleNativeClose(closeRequest)
        NPLogger.w(
            TAG,
            "handled USB detach deviceId=${device?.deviceId} deviceName=${device?.deviceName}"
        )
        return true
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
            val deadlineMs = SystemClock.elapsedRealtime() + EMERGENCY_CLOSE_WAIT_MS
            while (
                (transitionInFlight.get() || nativeCloseInFlight.get() > 0) &&
                SystemClock.elapsedRealtime() < deadlineMs
            ) {
                SystemClock.sleep(5L)
            }
        } finally {
            if (!transitionInFlight.get() && nativeCloseInFlight.get() == 0) {
                UsbExclusiveWakeLock.release("emergency:$reason")
            } else {
                NPLogger.w(
                    TAG,
                    "emergency shutdown keeps WakeLock until native close finishes reason=$reason " +
                        "transition=${transitionInFlight.get()} closeInFlight=${nativeCloseInFlight.get()}"
                )
            }
        }
    }

    fun refresh(context: Context) {
        if (transitionInFlight.get()) {
            markNativeTransitionInFlight("native_transition_in_flight")
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
        if (!transitionInFlight.compareAndSet(false, true)) {
            _state.value = _state.value.copy(
                lastError = "transition_in_flight",
                runtimeReport = "transition_in_flight"
            )
            return false
        }
        _state.value = _state.value.copy(transitioning = true)
        var startedHandle = 0L
        try {
            val closeRequest = sessionLock.withLock {
                val current = _state.value
                if (current.source == "player_pcm" && current.opened) {
                    _state.value = current.copy(
                        transitioning = false,
                        runtimeReport = "player_pcm_session_active",
                        lastError = "player_pcm_session_active"
                    )
                    return false
                }
                stopInternalLocked("start_generated_tone")
            }
            if (closeRequest != null) {
                scheduleNativeClose(closeRequest)
                sessionLock.withLock {
                    blockNativeOpenLocked("start_generated_tone", PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
                    _state.value = _state.value.copy(
                        transitioning = false,
                        runtimeReport = "native_open_deferred:start_generated_tone",
                        lastError = "native_open_deferred:start_generated_tone"
                    )
                }
                return false
            }
            val openGateError = sessionLock.withLock {
                openGateErrorLocked(SystemClock.elapsedRealtime())
            }
            if (openGateError != null) {
                _state.value = _state.value.copy(
                    transitioning = false,
                    runtimeReport = openGateError,
                    lastError = openGateError
                )
                return false
            }

            val appContext = context.applicationContext
            val openedDevice = openPermittedUsbAudioDevice(
                context = appContext,
                selectedDeviceKey = selectedDeviceKey(appContext)
            )
            if (openedDevice == null) {
                _state.value = _state.value.copy(
                    available = UsbExclusiveNativeBridge.ensureLoaded(),
                    transitioning = false,
                    runtimeReport = "No permitted USB audio streaming device",
                    lastError = "No permitted USB audio streaming device"
                )
                return false
            }
            val (targetDevice, connection) = openedDevice
            beginDeviceSession(targetDevice)

            UsbExclusiveSystemSoundGuard.activate(appContext, "tone_open_start")
            val handle = runCatching {
                UsbExclusiveNativeBridge.open(connection)
            }.getOrElse { error ->
                NPLogger.e(TAG, "Failed to open USB exclusive native session", error)
                0L
            }

            if (handle == 0L) {
                val openError = runCatching {
                    UsbExclusiveNativeBridge.lastOpenError()
                }.getOrDefault("nativeOpen failed")
                NPLogger.e(
                    TAG,
                    "nativeOpen failed for device=${targetDevice.productName ?: targetDevice.deviceName}, fd=${connection.fileDescriptor}, error=$openError"
                )
                runCatching { connection.close() }
                endDeviceSession()
                UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(appContext, "tone_open_failed")
                _state.value = _state.value.copy(
                    available = UsbExclusiveNativeBridge.ensureLoaded(),
                    transitioning = false,
                    selectedDeviceName = targetDevice.productName?.toString(),
                    runtimeReport = openError,
                    lastError = openError
                )
                return false
            }
            if (!ioGate.isOpen()) {
                closeHandleAndConnection(handle, connection, "tone_detached_during_open")
                return false
            }

            val started = runCatching {
                UsbExclusiveNativeBridge.startGeneratedTone(handle)
            }.getOrDefault(false)

            if (!started || !ioGate.isOpen()) {
                val startError = if (started) {
                    "deviceOnline=false lastError=usb_device_detached"
                } else {
                    UsbExclusiveNativeBridge.runtimeReport(handle)
                }
                closeHandleAndConnection(handle, connection, "tone_start_failed")
                UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(appContext, "tone_start_failed")
                _state.value = _state.value.copy(
                    available = UsbExclusiveNativeBridge.ensureLoaded(),
                    transitioning = false,
                    selectedDeviceName = targetDevice.productName?.toString(),
                    runtimeReport = startError,
                    lastError = startError
                )
                return false
            }

            sessionLock.withLock {
                activeConnection = connection
                UsbExclusiveWakeLock.acquire(appContext, "tone_started")
                _state.value = UsbExclusiveNativeState(
                    available = true,
                    opened = true,
                    streaming = true,
                    transitioning = false,
                    source = "tone",
                    handle = handle,
                    selectedDeviceName = targetDevice.productName?.toString() ?: targetDevice.deviceName,
                    lastError = null
                ).withRuntimeReport(UsbExclusiveNativeBridge.runtimeReport(handle))
                startedHandle = handle
            }
        } finally {
            drainPendingPlayerPcmStopIfNeeded()
            drainPendingPlayerPcmOpenBlockIfNeeded()
            transitionInFlight.set(false)
            val current = _state.value
            if (current.transitioning) {
                _state.value = current.copy(transitioning = false)
            }
        }
        return startedHandle != 0L &&
            ioGate.isOpen() &&
            _state.value.handle == startedHandle
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
        val inputFormat = describeUsbInputFormat(
            inputSampleRate,
            inputChannelCount,
            inputEncoding
        )
        NPLogger.d(TAG, "openPlayerPcm(): request input=$inputFormat")
        if (!transitionInFlight.compareAndSet(false, true)) {
            NPLogger.w(TAG, "openPlayerPcm(): native transition is in progress input=$inputFormat")
            _state.value = _state.value.copy(
                transitioning = true,
                runtimeReport = "transition_in_flight",
                lastError = "transition_in_flight"
            )
            return 0L
        }
        _state.value = _state.value.copy(transitioning = true)
        var openedHandle = 0L
        try {
            val appContext = context.applicationContext
            val formatResolution = UsbExclusiveOutputFormatResolver.resolve(
                context = appContext,
                inputSampleRate = inputSampleRate,
                inputChannelCount = inputChannelCount,
                inputEncoding = inputEncoding
            )
            val resolvedOutput = formatResolution.format
            if (resolvedOutput == null) {
                val resolutionError = formatResolution.error ?: "output_format_unresolved"
                val closeRequest = sessionLock.withLock {
                    val request = stopInternalLocked("output_format_unresolved:$resolutionError")
                    _state.value = _state.value.copy(
                        opened = false,
                        streaming = false,
                        paused = false,
                        source = "idle",
                        handle = 0L,
                        inputFormat = describeUsbInputFormat(
                            inputSampleRate,
                            inputChannelCount,
                            inputEncoding
                        ),
                        outputFormat = "unresolved",
                        runtimeReport = resolutionError,
                        lastError = resolutionError
                    )
                    request
                }
                scheduleNativeClose(closeRequest)
                NPLogger.w(TAG, "resolveOutputFormat(): $resolutionError input=$inputFormat")
                return 0L
            }
            NPLogger.d(TAG, "openPlayerPcm(): resolved output=${resolvedOutput.description}")

            sessionLock.withLock {
                val current = _state.value
                if (
                    playerPcmFreshOpenRequiredReason != null &&
                    (current.handle == 0L || current.source != "player_pcm")
                ) {
                    playerPcmFreshOpenRequiredReason = null
                }
                val freshOpenReason = playerPcmFreshOpenRequiredReason
                if (
                    freshOpenReason == null &&
                    current.handle != 0L &&
                    current.source == "player_pcm" &&
                    current.opened &&
                    ioGate.isOpen() &&
                    current.outputFormat == resolvedOutput.description &&
                    UsbExclusiveNativeBridge.configurePlayerBufferDuration(
                        current.handle,
                        resolvedOutput.bufferDurationMs
                    ) &&
                    UsbExclusiveNativeBridge.preparePlayerPcm(
                        handle = current.handle,
                        inputSampleRate = inputSampleRate,
                        inputChannelCount = inputChannelCount,
                        inputEncoding = inputEncoding
                    )
                ) {
                    val runtimeReport = UsbExclusiveNativeBridge.runtimeReport(current.handle)
                    rememberPlayerPcmRuntimeReport(current.handle, runtimeReport)
                    _state.value = current.copy(
                        streaming = false,
                        paused = false,
                        source = "player_pcm",
                        inputFormat = describeUsbInputFormat(
                            inputSampleRate,
                            inputChannelCount,
                            inputEncoding
                        ),
                        bufferDurationMs = resolvedOutput.bufferDurationMs,
                        lastError = null,
                        completedAudioFrames = 0L,
                        queuedAudioFrames = 0L
                    ).withRuntimeReport(runtimeReport)
                    NPLogger.d(
                        TAG,
                        "openPlayerPcm(): reused native handle=${current.handle} " +
                            "output=${resolvedOutput.description}"
                    )
                    openedHandle = current.handle
                    return@withLock
                }
                if (
                    freshOpenReason != null &&
                    current.handle != 0L &&
                    current.source == "player_pcm"
                ) {
                    NPLogger.i(
                        TAG,
                        "openPlayerPcm(): skip native handle reuse because fresh open is " +
                            "required reason=$freshOpenReason handle=${current.handle}"
                    )
                }

                openGateErrorLocked(SystemClock.elapsedRealtime())?.let { gateError ->
                    val closeRequest = if (current.handle != 0L) {
                        stopInternalLocked("open_gate:$gateError")
                    } else {
                        null
                    }
                    _state.value = _state.value.copy(
                        opened = false,
                        streaming = false,
                        paused = false,
                        source = "idle",
                        handle = 0L,
                        inputFormat = describeUsbInputFormat(
                            inputSampleRate,
                            inputChannelCount,
                            inputEncoding
                        ),
                        outputFormat = "deferred",
                        runtimeReport = gateError,
                        lastError = gateError
                    )
                    NPLogger.w(TAG, "openPlayerPcm(): deferred by native open gate: $gateError")
                    scheduleNativeClose(closeRequest)
                    return 0L
                }

                val closeRequest = stopInternalLocked("open_player_pcm_reconfigure")
                if (closeRequest != null) {
                    scheduleNativeClose(closeRequest)
                    val gateError = "native_open_deferred:native_close_in_flight"
                    blockNativeOpenLocked(
                        reason = "native_close_in_flight",
                        delayMs = PLAYER_PCM_RECONFIGURE_CLOSE_GATE_MS,
                        minimumDelayMs = PLAYER_PCM_RECONFIGURE_CLOSE_GATE_MS
                    )
                    _state.value = _state.value.copy(
                        opened = false,
                        streaming = false,
                        paused = false,
                        source = "idle",
                        handle = 0L,
                        inputFormat = describeUsbInputFormat(
                            inputSampleRate,
                            inputChannelCount,
                            inputEncoding
                        ),
                        outputFormat = "deferred",
                        runtimeReport = gateError,
                        lastError = gateError
                    )
                    NPLogger.w(TAG, "openPlayerPcm(): deferred while previous native session closes")
                    return 0L
                }
                val openedDevice = openPermittedUsbAudioDevice(
                    context = appContext,
                    selectedDeviceKey = selectedDeviceKey(appContext)
                )
                if (openedDevice == null) {
                    NPLogger.w(TAG, "openPlayerPcm(): no permitted USB audio streaming device")
                    _state.value = _state.value.copy(
                        available = UsbExclusiveNativeBridge.ensureLoaded(),
                        source = "idle",
                        runtimeReport = "No permitted USB audio streaming device",
                        lastError = "No permitted USB audio streaming device"
                    )
                    return 0L
                }
                val (targetDevice, connection) = openedDevice
                beginDeviceSession(targetDevice)
                NPLogger.i(
                    TAG,
                    "openPlayerPcm(): opening device=" +
                        "${targetDevice.productName ?: targetDevice.deviceName} " +
                        "fd=${connection.fileDescriptor} output=${resolvedOutput.description}"
                )
                if (!PlayerManager.allowMixedPlaybackEnabled) {
                    UsbExclusiveSystemSoundGuard.activate(appContext, "player_pcm_open_start")
                }
                val handle = runCatching {
                    UsbExclusiveNativeBridge.open(
                        connection = connection,
                        sampleRate = resolvedOutput.sampleRate,
                        channelCount = resolvedOutput.channelCount,
                        bitsPerSample = resolvedOutput.bitDepth,
                        subslotBytes = resolvedOutput.subslotBytes
                    )
                }.getOrElse { error ->
                    NPLogger.e(TAG, "Failed to open player USB exclusive session", error)
                    0L
                }
                if (handle == 0L) {
                    val openError = runCatching {
                        UsbExclusiveNativeBridge.lastOpenError()
                    }.getOrDefault("nativeOpen failed")
                    NPLogger.e(
                        TAG,
                        "openPlayerPcm(): native open failed device=" +
                            "${targetDevice.productName ?: targetDevice.deviceName} error=$openError"
                    )
                    runCatching { connection.close() }
                    endDeviceSession()
                    UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(
                        appContext,
                        "player_pcm_open_failed"
                    )
                    _state.value = _state.value.copy(
                        available = UsbExclusiveNativeBridge.ensureLoaded(),
                        source = "idle",
                        selectedDeviceName = targetDevice.productName?.toString(),
                        runtimeReport = openError,
                        lastError = openError
                    )
                    recordNativeOpenFailureLocked(openError)
                    return 0L
                }
                if (!ioGate.isOpen()) {
                    closeHandleAndConnection(
                        handle = handle,
                        connection = connection,
                        reason = "player_pcm_detached_during_open"
                    )
                    return 0L
                }
                val bufferConfigured = UsbExclusiveNativeBridge.configurePlayerBufferDuration(
                    handle,
                    resolvedOutput.bufferDurationMs
                )
                val prepared = bufferConfigured && UsbExclusiveNativeBridge.preparePlayerPcm(
                    handle = handle,
                    inputSampleRate = inputSampleRate,
                    inputChannelCount = inputChannelCount,
                    inputEncoding = inputEncoding
                )
                if (!prepared || !ioGate.isOpen()) {
                    val prepareError = if (prepared) {
                        "deviceOnline=false lastError=usb_device_detached"
                    } else {
                        UsbExclusiveNativeBridge.runtimeReport(handle)
                    }
                    NPLogger.e(
                        TAG,
                        "openPlayerPcm(): native prepare failed handle=$handle error=$prepareError"
                    )
                    closeHandleAndConnection(handle, connection, "player_pcm_prepare_failed")
                    UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(
                        appContext,
                        "player_pcm_prepare_failed"
                    )
                    _state.value = _state.value.copy(
                        available = UsbExclusiveNativeBridge.ensureLoaded(),
                        source = "idle",
                        selectedDeviceName = targetDevice.productName?.toString(),
                        runtimeReport = prepareError,
                        lastError = prepareError
                    )
                    recordNativeOpenFailureLocked(prepareError)
                    return 0L
                }
                activeConnection = connection
                UsbExclusiveNativeBridge.setPlayerFocusMuted(handle, focusSuppressed.get())
                UsbExclusiveWakeLock.acquire(appContext, "player_pcm_opened")
                lastPlayerPcmNativeOpenAtMs = SystemClock.elapsedRealtime()
                playerPcmOpenBlockedUntilMs = 0L
                playerPcmOpenBlockReason = ""
                playerPcmFreshOpenRequiredReason = null
                val runtimeReport = UsbExclusiveNativeBridge.runtimeReport(handle)
                rememberPlayerPcmRuntimeReport(handle, runtimeReport)
                _state.value = UsbExclusiveNativeState(
                    available = true,
                    opened = true,
                    streaming = false,
                    paused = false,
                    transitioning = false,
                    source = "player_pcm",
                    handle = handle,
                    selectedDeviceName = targetDevice.productName?.toString() ?: targetDevice.deviceName,
                    inputFormat = describeUsbInputFormat(
                        inputSampleRate,
                        inputChannelCount,
                        inputEncoding
                    ),
                    outputFormat = resolvedOutput.description,
                    outputSampleRate = resolvedOutput.sampleRate,
                    bufferDurationMs = resolvedOutput.bufferDurationMs,
                    lastError = null
                ).withRuntimeReport(runtimeReport)
                NPLogger.i(
                    TAG,
                    "openPlayerPcm(): opened handle=$handle device=" +
                        "${targetDevice.productName ?: targetDevice.deviceName} " +
                        "runtime=${_state.value.runtimeReport}"
                )
                openedHandle = handle
            }
        } finally {
            drainPendingPlayerPcmStopIfNeeded()
            drainPendingPlayerPcmOpenBlockIfNeeded()
            transitionInFlight.set(false)
            val current = _state.value
            if (current.transitioning) {
                _state.value = current.copy(transitioning = false)
            }
        }
        val current = _state.value
        return if (
            openedHandle != 0L &&
            ioGate.isOpen() &&
            current.handle == openedHandle &&
            current.opened
        ) {
            openedHandle
        } else {
            0L
        }
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
            if (current.handle == 0L || current.source == "idle") {
                val error = openGateErrorLocked(SystemClock.elapsedRealtime())
                    ?: "native_open_deferred:$reason"
                _state.value = current.copy(
                    transitioning = false,
                    runtimeReport = error,
                    lastError = error
                )
            }
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
            if (current.handle == 0L || current.source != "player_pcm") return
            playerPcmFreshOpenRequiredReason = reason
            NPLogger.i(
                TAG,
                "requireFreshPlayerPcmOpen(): reason=$reason handle=${current.handle} " +
                    "streaming=${current.streaming} paused=${current.paused}"
            )
        }
    }

    fun clearRecoverablePlayerPcmOpenBlock(reason: String) {
        if (transitionInFlight.get()) {
            markNativeTransitionInFlight("clear_open_block_deferred:$reason")
            return
        }
        sessionLock.withLock {
            if (!playerPcmOpenBlockReason.isRecoverableUserActionBlock()) {
                return
            }
            NPLogger.d(
                TAG,
                "clearRecoverablePlayerPcmOpenBlock(): reason=$reason block=$playerPcmOpenBlockReason"
            )
            playerPcmOpenBlockedUntilMs = 0L
            playerPcmOpenBlockReason = ""
            val current = _state.value
            val normalizedError = current.lastError.orEmpty()
            if (
                current.handle == 0L &&
                (
                    normalizedError.startsWith("native_open_deferred") ||
                        current.runtimeReport.startsWith("native_open_deferred")
                    )
            ) {
                _state.value = current.copy(
                    runtimeReport = "native_idle",
                    lastError = null
                )
            }
        }
    }

    fun configureActivePlayerBufferDuration(
        durationMs: Int,
        appInForeground: Boolean
    ): Boolean {
        val normalizedDurationMs = if (appInForeground) {
            normalizeUsbExclusiveForegroundBufferMs(durationMs)
        } else {
            normalizeUsbExclusiveBackgroundBufferMs(durationMs)
        }
        if (transitionInFlight.get()) {
            NPLogger.w(
                TAG,
                "configureActivePlayerBufferDuration(): deferred by transition durationMs=$normalizedDurationMs"
            )
            return false
        }
        sessionLock.withLock {
            val current = _state.value
            if (current.handle == 0L || current.source != "player_pcm" || !current.opened) {
                NPLogger.w(
                    TAG,
                    "configureActivePlayerBufferDuration(): no active player pcm " +
                        "durationMs=$normalizedDurationMs source=${current.source} handle=${current.handle}"
                )
                return false
            }
            val configured = UsbExclusiveNativeBridge.configurePlayerBufferDuration(
                current.handle,
                normalizedDurationMs
            )
            if (!configured) {
                val runtimeReport = UsbExclusiveNativeBridge.runtimeReport(current.handle)
                rememberPlayerPcmRuntimeReport(current.handle, runtimeReport)
                NPLogger.w(
                    TAG,
                    "configureActivePlayerBufferDuration(): native rejected durationMs=$normalizedDurationMs " +
                        "handle=${current.handle} report=$runtimeReport"
                )
                _state.value = current.copy(lastError = runtimeReport)
                    .withRuntimeReport(runtimeReport)
                return false
            }
            val runtimeReport = UsbExclusiveNativeBridge.runtimeReport(current.handle)
            rememberPlayerPcmRuntimeReport(current.handle, runtimeReport)
            _state.value = current.copy(
                bufferDurationMs = normalizedDurationMs,
                lastError = null
            ).withRuntimeReport(runtimeReport)
            NPLogger.d(
                TAG,
                "configureActivePlayerBufferDuration(): applied durationMs=$normalizedDurationMs " +
                    "handle=${current.handle}"
            )
            return true
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
                val report = UsbExclusiveNativeBridge.runtimeReport(handle)
                rememberPlayerPcmRuntimeReport(handle, report)
                if (nowMs - lastPlayerPcmStateEmitAtMs >= PCM_STATE_EMIT_INTERVAL_MS) {
                    lastPlayerPcmStateEmitAtMs = nowMs
                    _state.value = current.copy(
                        completedAudioFrames = UsbExclusiveNativeBridge.completedAudioFrames(handle),
                        queuedAudioFrames = UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
                    ).withRuntimeReport(report)
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
    }

    private fun clearPlayerPcmRuntimeReport() {
        latestPlayerPcmRuntime.set(PlayerPcmRuntimeCache())
    }

    fun playPlayerPcm(handle: Long): Boolean {
        sessionLock.withLock {
            val current = _state.value
            if (
                current.handle != handle ||
                current.source != "player_pcm" ||
                !current.opened ||
                !ioGate.isOpen()
            ) {
                NPLogger.w(
                    TAG,
                    "playPlayerPcm(): ignored stale handle=$handle currentHandle=${current.handle} " +
                        "source=${current.source} opened=${current.opened}"
                )
                return false
            }
            val started = UsbExclusiveNativeBridge.playPlayerPcm(handle)
            val report = UsbExclusiveNativeBridge.runtimeReport(handle)
            rememberPlayerPcmRuntimeReport(handle, report)
            _state.value = current.copy(
                streaming = started,
                paused = false,
                lastError = if (started) null else report,
                completedAudioFrames = UsbExclusiveNativeBridge.completedAudioFrames(handle),
                queuedAudioFrames = UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
            ).withRuntimeReport(report)
            if (started) {
                NPLogger.d(TAG, "playPlayerPcm(): started handle=$handle report=$report")
            } else {
                NPLogger.w(TAG, "playPlayerPcm(): failed handle=$handle report=$report")
            }
            return started
        }
    }

    fun pausePlayerPcm(handle: Long): Boolean {
        sessionLock.withLock {
            val current = _state.value
            if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
                return false
            }
            val paused = UsbExclusiveNativeBridge.pausePlayerPcm(handle)
            val report = UsbExclusiveNativeBridge.runtimeReport(handle)
            rememberPlayerPcmRuntimeReport(handle, report)
            _state.value = current.copy(
                streaming = report.booleanField("running") ?: current.streaming,
                paused = paused,
                lastError = if (paused) null else report,
                completedAudioFrames = UsbExclusiveNativeBridge.completedAudioFrames(handle),
                queuedAudioFrames = UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
            ).withRuntimeReport(report)
            if (paused) {
                NPLogger.d(TAG, "pausePlayerPcm(): paused handle=$handle report=$report")
            } else {
                NPLogger.w(TAG, "pausePlayerPcm(): failed handle=$handle report=$report")
            }
            return paused
        }
    }

    fun flushPlayerPcm(handle: Long): Boolean {
        sessionLock.withLock {
            val current = _state.value
            if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
                return false
            }
            val flushed = UsbExclusiveNativeBridge.flushPlayerPcm(handle)
            val report = UsbExclusiveNativeBridge.runtimeReport(handle)
            rememberPlayerPcmRuntimeReport(handle, report)
            _state.value = current.copy(
                streaming = report.booleanField("running") ?: current.streaming,
                paused = false,
                lastError = if (flushed) null else report,
                completedAudioFrames = 0L,
                queuedAudioFrames = 0L
            ).withRuntimeReport(report)
            if (flushed) {
                NPLogger.d(TAG, "flushPlayerPcm(): flushed handle=$handle report=$report")
            } else {
                NPLogger.w(TAG, "flushPlayerPcm(): failed handle=$handle report=$report")
            }
            return flushed
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
            pendingPlayerPcmStopReason = reason
            pendingPlayerPcmStopShouldBlockOpen = blockOpen
            if (blockOpen) {
                queuePendingPlayerPcmOpenBlock(reason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
            } else {
                pendingPlayerPcmOpenBlock = null
            }
            NPLogger.w(
                TAG,
                "forceStopAllSessions(): deferred while transition is active, reason=$reason blockOpen=$blockOpen"
            )
            return
        }
        val closeRequest = sessionLock.withLock {
            pendingPlayerPcmStopReason = null
            pendingPlayerPcmStopShouldBlockOpen = true
            NPLogger.w(
                TAG,
                "forceStopAllSessions(): reason=$reason source=${_state.value.source} " +
                    "handle=${_state.value.handle} opened=${_state.value.opened} blockOpen=$blockOpen"
            )
            val request = stopInternalLocked("force_stop_all:$reason")
            if (blockOpen) {
                blockNativeOpenLocked(reason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
            }
            request
        }
        scheduleNativeClose(closeRequest)
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

    private fun stopInternalLocked(reason: String = "stop_internal"): NativeCloseRequest? {
        return stopInternalLocked(reason, terminalError = null)
    }

    private fun stopInternalLocked(
        reason: String,
        terminalError: String?
    ): NativeCloseRequest? {
        val current = _state.value
        val connection = activeConnection
        ioGate.close()
        focusSuppressed.set(false)
        if (current.handle != 0L) {
            NPLogger.d(
                TAG,
                "stopInternalLocked(): queue close handle=${current.handle} source=${current.source} " +
                    "reason=$reason streaming=${current.streaming} runtime=${current.runtimeReport}"
            )
            runCatching { UsbExclusiveNativeBridge.stop(current.handle) }
            lastPlayerPcmNativeCloseAtMs = SystemClock.elapsedRealtime()
        }
        activeConnection = null
        clearActiveDeviceIdentity()
        _state.value = _state.value.copy(
            opened = false,
            streaming = false,
            paused = false,
            transitioning = false,
            source = "idle",
            handle = 0L,
            inputFormat = "none",
            outputFormat = "none",
            outputSampleRate = 0,
            completedAudioFrames = 0L,
            queuedAudioFrames = 0L,
            runtimeReport = terminalError ?: "idle",
            lastError = terminalError
        )
        clearPlayerPcmRuntimeReport()
        if (current.handle == 0L) {
            runCatching { connection?.close() }
            UsbExclusiveWakeLock.release(reason)
            return null
        }
        return NativeCloseRequest(
            handle = current.handle,
            connection = connection,
            source = current.source,
            reason = reason
        )
    }

    private fun scheduleNativeClose(request: NativeCloseRequest?) {
        if (request == null) return
        nativeCloseInFlight.incrementAndGet()
        nativeCloseExecutor.execute {
            try {
                NPLogger.d(
                    TAG,
                    "native close begin: handle=${request.handle} source=${request.source} " +
                        "reason=${request.reason}"
                )
                runCatching { ioGate.awaitDrained(timeoutMs = EMERGENCY_CLOSE_WAIT_MS) }
                    .onSuccess { drained ->
                        if (!drained) {
                            NPLogger.w(
                                TAG,
                                "writer drain timed out before native close: " +
                                    "handle=${request.handle} reason=${request.reason}"
                            )
                        }
                    }
                    .onFailure { error ->
                        Thread.currentThread().interrupt()
                        NPLogger.w(TAG, "writer drain interrupted for handle=${request.handle}", error)
                    }
                runCatching { UsbExclusiveNativeBridge.close(request.handle) }
                    .onFailure { error ->
                        NPLogger.w(
                            TAG,
                            "native close failed: handle=${request.handle} reason=${request.reason}",
                            error
                        )
                    }
                runCatching { request.connection?.close() }
                lastPlayerPcmNativeCloseAtMs = SystemClock.elapsedRealtime()
                NPLogger.d(
                    TAG,
                    "native close done: handle=${request.handle} source=${request.source} " +
                        "reason=${request.reason}"
                )
            } finally {
                UsbExclusiveWakeLock.release(request.reason)
                nativeCloseInFlight.decrementAndGet()
            }
        }
    }

    private fun drainPendingPlayerPcmStopIfNeeded() {
        val reason = pendingPlayerPcmStopReason ?: return
        val closeRequest = sessionLock.withLock {
            val pendingReason = pendingPlayerPcmStopReason ?: return
            val shouldBlockOpen = pendingPlayerPcmStopShouldBlockOpen
            pendingPlayerPcmStopReason = null
            pendingPlayerPcmStopShouldBlockOpen = true
            val current = _state.value
            val request = if (current.handle != 0L || activeConnection != null) {
                NPLogger.d(
                    TAG,
                    "drainPendingPlayerPcmStopIfNeeded(): reason=$pendingReason"
                )
                stopInternalLocked("pending_stop:$pendingReason")
            } else {
                null
            }
            if (shouldBlockOpen) {
                blockNativeOpenLocked(pendingReason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
            }
            _state.value = _state.value.copy(
                transitioning = false,
                runtimeReport = "stop_applied:$pendingReason"
            )
            request
        }
        scheduleNativeClose(closeRequest)
    }

    private fun drainPendingPlayerPcmOpenBlockIfNeeded() {
        val pendingBlock = pendingPlayerPcmOpenBlock ?: return
        sessionLock.withLock {
            val block = pendingPlayerPcmOpenBlock ?: return
            pendingPlayerPcmOpenBlock = null
            blockNativeOpenLocked(block.reason, block.delayMs)
            val current = _state.value
            if (current.handle == 0L || current.source == "idle") {
                val error = openGateErrorLocked(SystemClock.elapsedRealtime())
                    ?: "native_open_deferred:${block.reason}"
                _state.value = current.copy(
                    transitioning = false,
                    runtimeReport = error,
                    lastError = error
                )
            }
            NPLogger.d(
                TAG,
                "drainPendingPlayerPcmOpenBlockIfNeeded(): reason=${block.reason} delayMs=${block.delayMs}"
            )
        }
    }

    private fun queuePendingPlayerPcmOpenBlock(reason: String, delayMs: Long) {
        val currentBlock = pendingPlayerPcmOpenBlock
        if (currentBlock == null || delayMs >= currentBlock.delayMs) {
            pendingPlayerPcmOpenBlock = PendingPlayerPcmOpenBlock(reason, delayMs)
        }
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
            runtimeReport = runtimeReport
        )
    }

    private fun markNativeRefreshDeferred() {
        val current = _state.value
        _state.value = current.copy(
            runtimeReport = "native_refresh_deferred"
        )
    }

    private fun closeHandleAndConnection(
        handle: Long,
        connection: UsbDeviceConnection,
        reason: String = "open_failed_cleanup"
    ) {
        ioGate.close()
        runCatching { UsbExclusiveNativeBridge.stop(handle) }
        clearActiveDeviceIdentity()
        NPLogger.d(TAG, "closeHandleAndConnection(): queue handle=$handle fd=${connection.fileDescriptor}")
        scheduleNativeClose(
            NativeCloseRequest(
                handle = handle,
                connection = connection,
                source = "opening",
                reason = reason
            )
        )
        lastPlayerPcmNativeCloseAtMs = SystemClock.elapsedRealtime()
        sessionLock.withLock {
            if (activeConnection === connection) {
                activeConnection = null
            }
        }
    }

    private fun openGateErrorLocked(nowMs: Long): String? {
        clearExpiredPlayerPcmOpenBlockLocked(nowMs)
        val remainingBlockMs = playerPcmOpenBlockedUntilMs - nowMs
        if (remainingBlockMs > 0L) {
            return "native_open_deferred:$playerPcmOpenBlockReason remainingMs=$remainingBlockMs"
        }
        val closingCount = nativeCloseInFlight.get()
        if (closingCount > 0) {
            return "native_open_deferred:native_close_in_flight count=$closingCount"
        }
        return null
    }

    private fun clearExpiredPlayerPcmOpenBlockLocked(nowMs: Long) {
        if (playerPcmOpenBlockedUntilMs > 0L && nowMs >= playerPcmOpenBlockedUntilMs) {
            playerPcmOpenBlockedUntilMs = 0L
            playerPcmOpenBlockReason = ""
        }
    }

    private fun buildNativeIdleRuntimeReport(
        snapshot: UsbExclusiveDiagnosticsSnapshot
    ): String {
        return buildString {
            append("native_idle")
            append(" usbHostDevices=")
            append(snapshot.usbHostDevices.size)
            append(" usbOutputs=")
            append(snapshot.audioOutputs.count { it.isUsbOutput })
        }
    }

    private fun String?.isPersistentIdleNativeError(): Boolean {
        val normalized = this?.trim()?.takeUnless { it.isBlank() || it == "none" } ?: return false
        if (normalized == "idle" || normalized.startsWith("native_idle")) return false
        if (normalized.startsWith("native_open_deferred")) return false
        if (normalized.startsWith("native_reopen_cooling_down")) return false
        if (normalized.startsWith("native_refresh_deferred")) return false
        if (normalized.startsWith("native_transition_in_flight")) return false
        if (normalized.startsWith("stop_deferred")) return false
        if (normalized.startsWith("stop_applied")) return false
        if (normalized.contains("usb_exclusive_disabled", ignoreCase = true)) return false
        return true
    }

    private fun blockNativeOpenLocked(
        reason: String,
        delayMs: Long,
        minimumDelayMs: Long = PLAYER_PCM_OPEN_MIN_INTERVAL_MS
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        val normalizedDelayMs = delayMs.coerceAtLeast(minimumDelayMs).coerceAtLeast(0L)
        val untilMs = nowMs + normalizedDelayMs
        if (untilMs > playerPcmOpenBlockedUntilMs) {
            val oldReason = playerPcmOpenBlockReason.ifBlank { "none" }
            val oldRemainingMs = (playerPcmOpenBlockedUntilMs - nowMs).coerceAtLeast(0L)
            playerPcmOpenBlockedUntilMs = untilMs
            playerPcmOpenBlockReason = reason
            NPLogger.w(
                TAG,
                "blockNativeOpenLocked(): reason=$reason delayMs=$normalizedDelayMs " +
                    "oldReason=$oldReason oldRemainingMs=$oldRemainingMs"
            )
        }
    }

    private fun recordNativeOpenFailureLocked(reason: String) {
        val fuseMs = if (reason.isHighRiskNativeOpenFailure()) {
            PLAYER_PCM_FAILURE_FUSE_MS
        } else {
            PLAYER_PCM_TRANSIENT_FUSE_MS
        }
        blockNativeOpenLocked(reason, fuseMs)
    }

    private fun String.isHighRiskNativeOpenFailure(): Boolean {
        return contains("claim_interface", ignoreCase = true) ||
            contains("set_alt", ignoreCase = true) ||
            contains("nativeOpen", ignoreCase = true) ||
            contains("usb", ignoreCase = true) ||
            contains("transport", ignoreCase = true)
    }

    private fun String.isRecoverableUserActionBlock(): Boolean {
        if (startsWith("sample_rate_unsupported")) return false
        if (startsWith("bit_depth_unsupported")) return false
        if (startsWith("channel_count_unsupported")) return false
        if (contains("claim_interface", ignoreCase = true)) return false
        if (contains("set_alt", ignoreCase = true)) return false
        if (contains("nativeOpen", ignoreCase = true)) return false
        return contains("usb_exclusive_disabled", ignoreCase = true) ||
            contains("release", ignoreCase = true) ||
            contains("failover", ignoreCase = true) ||
            contains("native_failure", ignoreCase = true) ||
            contains("transport", ignoreCase = true) ||
            contains("foreground", ignoreCase = true) ||
            contains("stalled", ignoreCase = true)
    }

    private fun beginDeviceSession(device: UsbDevice) {
        activeDeviceName.set(device.deviceName)
        activeDeviceId.set(device.deviceId)
        focusSuppressed.set(false)
        ioGate.open()
    }

    private fun endDeviceSession() {
        ioGate.close()
        focusSuppressed.set(false)
        clearActiveDeviceIdentity()
    }

    private fun clearActiveDeviceIdentity() {
        activeDeviceId.set(NO_ACTIVE_USB_DEVICE_ID)
        activeDeviceName.set(null)
    }

    private fun blockWritesImmediately() {
        ioGate.close()
        val handle = _state.value.handle
        if (handle != 0L) {
            runCatching { UsbExclusiveNativeBridge.stop(handle) }
        }
    }

    private fun matchesActiveDevice(device: UsbDevice?): Boolean {
        val currentId = activeDeviceId.get()
        val currentName = activeDeviceName.get()
        if (currentId == NO_ACTIVE_USB_DEVICE_ID && currentName == null) return false
        if (device == null) return true
        return device.deviceId == currentId || device.deviceName == currentName
    }

    private fun UsbExclusiveNativeState.withRuntimeReport(
        runtimeReport: String
    ): UsbExclusiveNativeState {
        val metrics = runtimeReport.usbRuntimeMetrics()
        return copy(
            runtimeReport = runtimeReport,
            pcmLevelBytes = metrics.pcmLevelBytes ?: pcmLevelBytes,
            pcmCapacityBytes = metrics.pcmCapacityBytes ?: pcmCapacityBytes,
            pcmFreeBytes = metrics.pcmFreeBytes ?: pcmFreeBytes,
            pcmBackpressureEvents = metrics.pcmBackpressureEvents ?: pcmBackpressureEvents,
            pcmBackpressureTotalMs = metrics.pcmBackpressureTotalMs ?: pcmBackpressureTotalMs,
            pcmBackpressureCurrentMs = metrics.pcmBackpressureCurrentMs
                ?: pcmBackpressureCurrentMs,
            pcmBackpressureMaxMs = metrics.pcmBackpressureMaxMs ?: pcmBackpressureMaxMs,
            playerSignalFrames = metrics.playerSignalFrames ?: playerSignalFrames,
            playerSilentFrames = metrics.playerSilentFrames ?: playerSilentFrames,
            playerSignalBytes = metrics.playerSignalBytes ?: playerSignalBytes,
            playerZeroFillBytes = metrics.playerZeroFillBytes ?: playerZeroFillBytes,
            outputPeak = metrics.outputPeak ?: outputPeak,
            lastOutputPeak = metrics.lastOutputPeak ?: lastOutputPeak
        )
    }

    private fun selectedDeviceKey(context: Context): String {
        return if (PlayerManager.isPlayerInitialized()) {
            PlayerManager.usbExclusivePreferences.selectedDeviceKey
        } else {
            readPlaybackPreferenceSnapshotSync(context).toUsbExclusivePreferences().selectedDeviceKey
        }.ifBlank { DEFAULT_USB_EXCLUSIVE_DEVICE_KEY }
    }

}
