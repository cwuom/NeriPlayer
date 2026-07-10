package moe.ouom.neriplayer.core.player.usb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnosticsSnapshot
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
import moe.ouom.neriplayer.data.settings.normalizeUsbExclusiveBufferMs
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.toUsbExclusivePreferences
import moe.ouom.neriplayer.util.NPLogger

object UsbExclusiveSessionController {
    private const val TAG = "NERI-UsbExclusiveNative"
    private const val PLAYER_PCM_OPEN_MIN_INTERVAL_MS = 3_500L
    private const val PLAYER_PCM_FOCUS_COOLDOWN_MS = 8_000L
    private const val PLAYER_PCM_FAILURE_FUSE_MS = 18_000L
    private const val PLAYER_PCM_TRANSIENT_FUSE_MS = 5_000L
    private val transitionInFlight = AtomicBoolean(false)
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
    private var lastPlayerPcmWriteIssueLogAtMs = 0L

    private data class PendingPlayerPcmOpenBlock(
        val reason: String,
        val delayMs: Long
    )

    private val _state = MutableStateFlow(
        UsbExclusiveNativeState(
            available = UsbExclusiveNativeBridge.ensureLoaded()
        )
    )
    val state: StateFlow<UsbExclusiveNativeState> = _state.asStateFlow()

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
            val lastError = if (current.handle != 0L) {
                current.lastError
            } else {
                runtimeReport.takeIf { it.isPersistentIdleNativeError() }
            }
            _state.value = current.copy(
                available = UsbExclusiveNativeBridge.ensureLoaded(),
                transitioning = false,
                runtimeReport = runtimeReport,
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
            )
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
        sessionLock.withLock {
            val current = _state.value
            if (current.source == "player_pcm" && current.opened) {
                _state.value = current.copy(
                    transitioning = false,
                    runtimeReport = "player_pcm_session_active",
                    lastError = "player_pcm_session_active"
                )
                transitionInFlight.set(false)
                return false
            }
            stopInternalLocked()
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
            transitionInFlight.set(false)
            return false
        }
        val (targetDevice, connection) = openedDevice

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
            _state.value = _state.value.copy(
                available = UsbExclusiveNativeBridge.ensureLoaded(),
                transitioning = false,
                selectedDeviceName = targetDevice.productName?.toString(),
                runtimeReport = openError,
                lastError = openError
            )
            transitionInFlight.set(false)
            return false
        }

        val started = runCatching {
            UsbExclusiveNativeBridge.startGeneratedTone(handle)
        }.getOrDefault(false)

        if (!started) {
            val startError = UsbExclusiveNativeBridge.runtimeReport(handle)
            closeHandleAndConnection(handle, connection)
            _state.value = _state.value.copy(
                available = UsbExclusiveNativeBridge.ensureLoaded(),
                transitioning = false,
                selectedDeviceName = targetDevice.productName?.toString(),
                runtimeReport = startError,
                lastError = startError
            )
            transitionInFlight.set(false)
            return false
        }

        sessionLock.withLock {
            activeConnection = connection
            _state.value = UsbExclusiveNativeState(
                available = true,
                opened = true,
                streaming = true,
                transitioning = false,
                source = "tone",
                handle = handle,
                selectedDeviceName = targetDevice.productName?.toString() ?: targetDevice.deviceName,
                runtimeReport = UsbExclusiveNativeBridge.runtimeReport(handle),
                lastError = null
            )
        }
        transitionInFlight.set(false)
        return true
    }

    fun stopGeneratedTone() {
        if (!transitionInFlight.compareAndSet(false, true)) {
            return
        }
        try {
            val current = _state.value
            if (current.source != "tone") {
                return
            }
            _state.value = current.copy(transitioning = true)
            sessionLock.withLock {
                stopInternalLocked()
                _state.value = _state.value.copy(
                    transitioning = false
                )
            }
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
                sessionLock.withLock {
                    stopInternalLocked()
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
                }
                NPLogger.w(TAG, "resolveOutputFormat(): $resolutionError input=$inputFormat")
                return 0L
            }
            NPLogger.d(TAG, "openPlayerPcm(): resolved output=${resolvedOutput.description}")

            sessionLock.withLock {
                val current = _state.value
                if (
                    current.handle != 0L &&
                    current.source == "player_pcm" &&
                    current.opened &&
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
                        runtimeReport = UsbExclusiveNativeBridge.runtimeReport(current.handle),
                        lastError = null,
                        completedAudioFrames = 0L,
                        queuedAudioFrames = 0L
                    )
                    NPLogger.d(
                        TAG,
                        "openPlayerPcm(): reused native handle=${current.handle} " +
                            "output=${resolvedOutput.description}"
                    )
                    return current.handle
                }

                openGateErrorLocked(SystemClock.elapsedRealtime())?.let { gateError ->
                    if (current.handle != 0L) {
                        stopInternalLocked()
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
                    return 0L
                }

                stopInternalLocked()
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
                NPLogger.i(
                    TAG,
                    "openPlayerPcm(): opening device=" +
                        "${targetDevice.productName ?: targetDevice.deviceName} " +
                        "fd=${connection.fileDescriptor} output=${resolvedOutput.description}"
                )
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
                if (!prepared) {
                    val prepareError = UsbExclusiveNativeBridge.runtimeReport(handle)
                    NPLogger.e(
                        TAG,
                        "openPlayerPcm(): native prepare failed handle=$handle error=$prepareError"
                    )
                    closeHandleAndConnection(handle, connection)
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
                lastPlayerPcmNativeOpenAtMs = SystemClock.elapsedRealtime()
                playerPcmOpenBlockedUntilMs = 0L
                playerPcmOpenBlockReason = ""
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
                    runtimeReport = UsbExclusiveNativeBridge.runtimeReport(handle),
                    lastError = null
                )
                NPLogger.i(
                    TAG,
                    "openPlayerPcm(): opened handle=$handle device=" +
                        "${targetDevice.productName ?: targetDevice.deviceName} " +
                        "runtime=${_state.value.runtimeReport}"
                )
                return handle
            }
        } finally {
            transitionInFlight.set(false)
            drainPendingPlayerPcmStopIfNeeded()
            drainPendingPlayerPcmOpenBlockIfNeeded()
            val current = _state.value
            if (current.transitioning) {
                _state.value = current.copy(transitioning = false)
            }
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

    fun configureActivePlayerBufferDuration(durationMs: Int): Boolean {
        val normalizedDurationMs = normalizeUsbExclusiveBufferMs(durationMs)
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
                NPLogger.w(
                    TAG,
                    "configureActivePlayerBufferDuration(): native rejected durationMs=$normalizedDurationMs " +
                        "handle=${current.handle} report=$runtimeReport"
                )
                _state.value = current.copy(
                    runtimeReport = runtimeReport,
                    lastError = runtimeReport
                )
                return false
            }
            _state.value = current.copy(
                bufferDurationMs = normalizedDurationMs,
                runtimeReport = UsbExclusiveNativeBridge.runtimeReport(current.handle),
                lastError = null
            )
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
        sessionLock.withLock {
            val current = _state.value
            if (current.handle != handle || current.source != "player_pcm") {
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
                if (nowMs - lastPlayerPcmWriteIssueLogAtMs >= 1_000L) {
                    lastPlayerPcmWriteIssueLogAtMs = nowMs
                    val report = UsbExclusiveNativeBridge.runtimeReport(handle)
                    NPLogger.w(
                        TAG,
                        "writePlayerPcm(): short write handle=$handle requested=$size " +
                            "written=$written report=$report"
                    )
                }
            }
            return written
        }
    }

    fun playPlayerPcm(handle: Long): Boolean {
        sessionLock.withLock {
            val current = _state.value
            if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
                NPLogger.w(
                    TAG,
                    "playPlayerPcm(): ignored stale handle=$handle currentHandle=${current.handle} " +
                        "source=${current.source} opened=${current.opened}"
                )
                return false
            }
            val started = UsbExclusiveNativeBridge.playPlayerPcm(handle)
            val report = UsbExclusiveNativeBridge.runtimeReport(handle)
            _state.value = current.copy(
                streaming = started,
                paused = false,
                runtimeReport = report,
                lastError = if (started) null else report,
                completedAudioFrames = UsbExclusiveNativeBridge.completedAudioFrames(handle),
                queuedAudioFrames = UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
            )
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
            _state.value = current.copy(
                streaming = false,
                paused = paused,
                runtimeReport = report,
                lastError = if (paused) null else report,
                completedAudioFrames = UsbExclusiveNativeBridge.completedAudioFrames(handle),
                queuedAudioFrames = UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
            )
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
            _state.value = current.copy(
                streaming = false,
                paused = false,
                runtimeReport = report,
                lastError = if (flushed) null else report,
                completedAudioFrames = 0L,
                queuedAudioFrames = 0L
            )
            if (flushed) {
                NPLogger.d(TAG, "flushPlayerPcm(): flushed handle=$handle report=$report")
            } else {
                NPLogger.w(TAG, "flushPlayerPcm(): failed handle=$handle report=$report")
            }
            return flushed
        }
    }

    fun setPlayerVolume(handle: Long, volume: Float): Boolean {
        sessionLock.withLock {
            val current = _state.value
            if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
                return false
            }
            return UsbExclusiveNativeBridge.setPlayerVolume(handle, volume)
        }
    }

    fun completedAudioFrames(handle: Long): Long {
        sessionLock.withLock {
            if (_state.value.handle != handle || _state.value.source != "player_pcm") return 0L
            return UsbExclusiveNativeBridge.completedAudioFrames(handle)
        }
    }

    fun queuedPlayerFrames(handle: Long): Long {
        sessionLock.withLock {
            if (_state.value.handle != handle || _state.value.source != "player_pcm") return 0L
            return UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
        }
    }

    fun closePlayerPcm(handle: Long) {
        sessionLock.withLock {
            if (_state.value.handle == handle && _state.value.source == "player_pcm") {
                stopInternalLocked()
            }
        }
    }

    fun stopPlayerPcmSession(reason: String) {
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
        sessionLock.withLock {
            val current = _state.value
            if (current.source != "player_pcm") {
                pendingPlayerPcmStopReason = null
                return
            }
            pendingPlayerPcmStopReason = null
            NPLogger.d(TAG, "stopPlayerPcmSession(): reason=$reason")
            stopInternalLocked()
            blockNativeOpenLocked(reason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
        }
    }

    fun forceStopAllSessions(reason: String, blockOpen: Boolean = true) {
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
        sessionLock.withLock {
            pendingPlayerPcmStopReason = null
            pendingPlayerPcmStopShouldBlockOpen = true
            NPLogger.w(
                TAG,
                "forceStopAllSessions(): reason=$reason source=${_state.value.source} " +
                    "handle=${_state.value.handle} opened=${_state.value.opened} blockOpen=$blockOpen"
            )
            stopInternalLocked()
            if (blockOpen) {
                blockNativeOpenLocked(reason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
            }
        }
    }

    fun refreshRuntime(handle: Long) {
        if (handle == 0L) return
        sessionLock.withLock {
            val current = _state.value
            if (current.handle != handle) return
            _state.value = current.copy(
                runtimeReport = UsbExclusiveNativeBridge.runtimeReport(handle),
                completedAudioFrames = UsbExclusiveNativeBridge.completedAudioFrames(handle),
                queuedAudioFrames = UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
            )
        }
    }

    private fun stopInternalLocked() {
        val current = _state.value
        if (current.handle != 0L) {
            NPLogger.d(
                TAG,
                "stopInternalLocked(): closing handle=${current.handle} source=${current.source} " +
                    "streaming=${current.streaming} runtime=${current.runtimeReport}"
            )
            runCatching { UsbExclusiveNativeBridge.stop(current.handle) }
            runCatching { UsbExclusiveNativeBridge.close(current.handle) }
            lastPlayerPcmNativeCloseAtMs = SystemClock.elapsedRealtime()
        }
        runCatching { activeConnection?.close() }
        activeConnection = null
        _state.value = _state.value.copy(
            opened = false,
            streaming = false,
            paused = false,
            source = "idle",
            handle = 0L,
            inputFormat = "none",
            outputFormat = "none",
            outputSampleRate = 0,
            completedAudioFrames = 0L,
            queuedAudioFrames = 0L,
            runtimeReport = "idle",
            lastError = null
        )
    }

    private fun drainPendingPlayerPcmStopIfNeeded() {
        val reason = pendingPlayerPcmStopReason ?: return
        sessionLock.withLock {
            val pendingReason = pendingPlayerPcmStopReason ?: return
            val shouldBlockOpen = pendingPlayerPcmStopShouldBlockOpen
            pendingPlayerPcmStopReason = null
            pendingPlayerPcmStopShouldBlockOpen = true
            val current = _state.value
            if (current.handle != 0L || activeConnection != null) {
                NPLogger.d(
                    TAG,
                    "drainPendingPlayerPcmStopIfNeeded(): reason=$pendingReason"
                )
                stopInternalLocked()
            }
            if (shouldBlockOpen) {
                blockNativeOpenLocked(pendingReason, PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
            }
            _state.value = _state.value.copy(
                transitioning = false,
                runtimeReport = "stop_applied:$pendingReason"
            )
        }
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

    private fun closeHandleAndConnection(handle: Long, connection: UsbDeviceConnection) {
        NPLogger.d(TAG, "closeHandleAndConnection(): handle=$handle fd=${connection.fileDescriptor}")
        runCatching { UsbExclusiveNativeBridge.close(handle) }
        runCatching { connection.close() }
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

    private fun blockNativeOpenLocked(reason: String, delayMs: Long) {
        val nowMs = SystemClock.elapsedRealtime()
        val untilMs = nowMs + delayMs.coerceAtLeast(PLAYER_PCM_OPEN_MIN_INTERVAL_MS)
        if (untilMs > playerPcmOpenBlockedUntilMs) {
            val oldReason = playerPcmOpenBlockReason.ifBlank { "none" }
            val oldRemainingMs = (playerPcmOpenBlockedUntilMs - nowMs).coerceAtLeast(0L)
            playerPcmOpenBlockedUntilMs = untilMs
            playerPcmOpenBlockReason = reason
            NPLogger.w(
                TAG,
                "blockNativeOpenLocked(): reason=$reason delayMs=$delayMs " +
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

    private fun selectedDeviceKey(context: Context): String {
        return if (PlayerManager.isPlayerInitialized()) {
            PlayerManager.usbExclusivePreferences.selectedDeviceKey
        } else {
            readPlaybackPreferenceSnapshotSync(context).toUsbExclusivePreferences().selectedDeviceKey
        }.ifBlank { DEFAULT_USB_EXCLUSIVE_DEVICE_KEY }
    }

}
