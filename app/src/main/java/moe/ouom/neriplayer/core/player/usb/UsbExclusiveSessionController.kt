package moe.ouom.neriplayer.core.player.usb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.toUsbExclusivePreferences
import moe.ouom.neriplayer.util.NPLogger

object UsbExclusiveSessionController {
    private const val TAG = "NERI-UsbExclusiveNative"
    private val transitionInFlight = AtomicBoolean(false)
    private val sessionLock = Any()
    private var activeConnection: UsbDeviceConnection? = null

    private val _state = MutableStateFlow(
        UsbExclusiveNativeState(
            available = UsbExclusiveNativeBridge.ensureLoaded()
        )
    )
    val state: StateFlow<UsbExclusiveNativeState> = _state.asStateFlow()

    fun refresh(context: Context) {
        val snapshot = UsbExclusiveDiagnostics.snapshot(context)
        synchronized(sessionLock) {
            val current = _state.value
            val runtimeReport = if (current.handle != 0L) {
                UsbExclusiveNativeBridge.runtimeReport(current.handle)
            } else if (!current.lastError.isNullOrBlank() && current.lastError != "none") {
                current.lastError
            } else {
                buildString {
                    append("native_idle")
                    append(" usbHostDevices=")
                    append(snapshot.usbHostDevices.size)
                    append(" usbOutputs=")
                    append(snapshot.audioOutputs.count { it.isUsbOutput })
                }
            }
            _state.value = current.copy(
                available = UsbExclusiveNativeBridge.ensureLoaded(),
                runtimeReport = runtimeReport,
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
        synchronized(sessionLock) {
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

        synchronized(sessionLock) {
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
            synchronized(sessionLock) {
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
        if (transitionInFlight.get()) {
            NPLogger.w(TAG, "openPlayerPcm(): diagnostic tone transition is in progress")
            return 0L
        }
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
            synchronized(sessionLock) {
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
            NPLogger.w(TAG, "resolveOutputFormat(): $resolutionError")
            return 0L
        }

        synchronized(sessionLock) {
            val current = _state.value
            if (
                current.handle != 0L &&
                current.source == "player_pcm" &&
                current.opened &&
                current.outputFormat == resolvedOutput.description &&
                current.bufferDurationMs == resolvedOutput.bufferDurationMs &&
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
                    runtimeReport = UsbExclusiveNativeBridge.runtimeReport(current.handle),
                    lastError = null,
                    completedAudioFrames = 0L,
                    queuedAudioFrames = 0L
                )
                return current.handle
            }

            stopInternalLocked()
            val openedDevice = openPermittedUsbAudioDevice(
                context = appContext,
                selectedDeviceKey = selectedDeviceKey(appContext)
            )
            if (openedDevice == null) {
                _state.value = _state.value.copy(
                    available = UsbExclusiveNativeBridge.ensureLoaded(),
                    source = "idle",
                    runtimeReport = "No permitted USB audio streaming device",
                    lastError = "No permitted USB audio streaming device"
                )
                return 0L
            }
            val (targetDevice, connection) = openedDevice
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
                runCatching { connection.close() }
                _state.value = _state.value.copy(
                    available = UsbExclusiveNativeBridge.ensureLoaded(),
                    source = "idle",
                    selectedDeviceName = targetDevice.productName?.toString(),
                    runtimeReport = openError,
                    lastError = openError
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
            if (!prepared) {
                val prepareError = UsbExclusiveNativeBridge.runtimeReport(handle)
                closeHandleAndConnection(handle, connection)
                _state.value = _state.value.copy(
                    available = UsbExclusiveNativeBridge.ensureLoaded(),
                    source = "idle",
                    selectedDeviceName = targetDevice.productName?.toString(),
                    runtimeReport = prepareError,
                    lastError = prepareError
                )
                return 0L
            }
            activeConnection = connection
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
            return handle
        }
    }

    fun writePlayerPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        volume: Float
    ): Int {
        synchronized(sessionLock) {
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
            return written
        }
    }

    fun playPlayerPcm(handle: Long): Boolean {
        synchronized(sessionLock) {
            val current = _state.value
            if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
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
            return started
        }
    }

    fun pausePlayerPcm(handle: Long): Boolean {
        synchronized(sessionLock) {
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
            return paused
        }
    }

    fun flushPlayerPcm(handle: Long): Boolean {
        synchronized(sessionLock) {
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
            return flushed
        }
    }

    fun setPlayerVolume(handle: Long, volume: Float): Boolean {
        synchronized(sessionLock) {
            val current = _state.value
            if (current.handle != handle || current.source != "player_pcm" || !current.opened) {
                return false
            }
            return UsbExclusiveNativeBridge.setPlayerVolume(handle, volume)
        }
    }

    fun completedAudioFrames(handle: Long): Long {
        synchronized(sessionLock) {
            if (_state.value.handle != handle || _state.value.source != "player_pcm") return 0L
            return UsbExclusiveNativeBridge.completedAudioFrames(handle)
        }
    }

    fun queuedPlayerFrames(handle: Long): Long {
        synchronized(sessionLock) {
            if (_state.value.handle != handle || _state.value.source != "player_pcm") return 0L
            return UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
        }
    }

    fun closePlayerPcm(handle: Long) {
        synchronized(sessionLock) {
            if (_state.value.handle == handle && _state.value.source == "player_pcm") {
                stopInternalLocked()
            }
        }
    }

    fun stopPlayerPcmSession(reason: String) {
        synchronized(sessionLock) {
            val current = _state.value
            if (current.source != "player_pcm") return
            NPLogger.d(TAG, "stopPlayerPcmSession(): reason=$reason")
            stopInternalLocked()
        }
    }

    fun refreshRuntime(handle: Long) {
        if (handle == 0L) return
        synchronized(sessionLock) {
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
            runCatching { UsbExclusiveNativeBridge.stop(current.handle) }
            runCatching { UsbExclusiveNativeBridge.close(current.handle) }
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

    private fun closeHandleAndConnection(handle: Long, connection: UsbDeviceConnection) {
        runCatching { UsbExclusiveNativeBridge.close(handle) }
        runCatching { connection.close() }
        synchronized(sessionLock) {
            if (activeConnection === connection) {
                activeConnection = null
            }
        }
    }

    private fun selectedDeviceKey(context: Context): String {
        return if (PlayerManager.isPlayerInitialized()) {
            PlayerManager.usbExclusivePreferences.selectedDeviceKey
        } else {
            readPlaybackPreferenceSnapshotSync(context).toUsbExclusivePreferences().selectedDeviceKey
        }.ifBlank { DEFAULT_USB_EXCLUSIVE_DEVICE_KEY }
    }

}
