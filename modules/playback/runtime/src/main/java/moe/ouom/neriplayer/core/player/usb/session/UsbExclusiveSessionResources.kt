package moe.ouom.neriplayer.core.player.usb.session

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbConstants
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.usb.device.matchesUsbExclusiveDeviceKey
import moe.ouom.neriplayer.core.player.usb.device.openPermittedUsbAudioDevice
import moe.ouom.neriplayer.core.player.usb.device.usbExclusiveDeviceKey
import moe.ouom.neriplayer.core.player.usb.sink.ResolvedUsbOutputFormat
import moe.ouom.neriplayer.core.player.usb.sink.UsbExclusiveOutputFormatResolver
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveIoGate
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeBridge
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import moe.ouom.neriplayer.core.player.usb.transport.allowsAlternativeOutputRetry
import moe.ouom.neriplayer.core.player.usb.transport.usbExclusiveErrorCode
import moe.ouom.neriplayer.core.player.policy.usb.usbExclusiveTransferWindowDurationMs
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY

internal class UsbExclusiveSessionResources(
    private val ioGate: UsbExclusiveIoGate,
    private val onCloseComplete: (CloseRequest, Int) -> Unit,
    private val selectedDeviceKeyPort: UsbExclusiveSelectedDeviceKeyPort,
    private val native: UsbExclusiveNativeSessionPort = AndroidUsbExclusiveNativeSessionPort,
    private val soundGuard: UsbExclusiveSoundGuardPort = AndroidUsbExclusiveSoundGuardPort,
    private val openDeviceConnection: (Context, String) -> Pair<UsbDevice, UsbDeviceConnection>? =
        ::openPermittedUsbAudioDevice
) {
    private val nativeCloseInFlight = AtomicInteger(0)
    private val activeDeviceId = AtomicInteger(NO_ACTIVE_USB_DEVICE_ID)
    private val activeDeviceName = AtomicReference<String?>(null)
    private val activeDeviceKey = AtomicReference<String?>(null)
    private val nativeCloseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NeriUsbExclusiveClose").apply { isDaemon = true }
    }
    private var activeConnection: UsbDeviceConnection? = null

    data class CloseRequest(
        val handle: Long,
        val connection: UsbDeviceConnection?,
        val source: String,
        val reason: String
    )

    data class OpenedDevice(
        val device: UsbDevice,
        val connection: UsbDeviceConnection
    ) {
        val displayName: String get() = device.productName ?: device.deviceName
    }

    data class ToneOpenResult(
        val handle: Long = 0L,
        val openedDevice: OpenedDevice? = null,
        val error: String? = null
    )

    data class PlayerOpenResult(
        val handle: Long = 0L,
        val openedDevice: OpenedDevice? = null,
        val outputFormat: ResolvedUsbOutputFormat? = null,
        val error: String? = null,
        val shouldFuseOpen: Boolean = false
    )

    data class ReconfigureResult(
        val outputFormat: ResolvedUsbOutputFormat? = null,
        val report: String? = null
    ) {
        val complete: Boolean get() = outputFormat != null && report != null
        fun requireOutputFormat(): ResolvedUsbOutputFormat = checkNotNull(outputFormat)
        fun requireReport(): String = checkNotNull(report)
    }

    data class RearmResult(
        val reconfigured: Boolean,
        val bufferConfigured: Boolean,
        val transferWindowConfigured: Boolean,
        val prepared: Boolean,
        val report: String,
        val completedFrames: Long,
        val queuedFrames: Long
    ) {
        val ready: Boolean get() = reconfigured && bufferConfigured && prepared
    }

    private data class PreparedPcmStages(
        val bufferConfigured: Boolean,
        val transferWindowConfigured: Boolean,
        val prepared: Boolean
    )

    val nativeCloseInFlightCount: Int get() = nativeCloseInFlight.get()
    val hasNativeCloseInFlight: Boolean get() = nativeCloseInFlight.get() > 0
    val selectedDeviceKey: String? get() = activeDeviceKey.get()
    val hasActiveConnection: Boolean get() = activeConnection != null

    fun hasCloseableSession(handle: Long): Boolean = handle != 0L || hasActiveConnection

    fun detachedDeviceDescription(device: UsbDevice?): String =
        "deviceId=${device?.deviceId} deviceName=${device?.deviceName}"

    fun isCurrentOpenHandle(handle: Long, state: UsbExclusiveNativeState): Boolean {
        return handle != 0L && ioGate.isOpen() && state.handle == handle
    }

    fun committedPlayerHandle(handle: Long, state: UsbExclusiveNativeState): Long {
        if (!isCurrentOpenHandle(handle, state)) return 0L
        return if (state.opened) handle else 0L
    }

    fun markDeviceDetached(handle: Long) {
        if (handle == 0L) return
        runCatching { native.markDeviceDetached(handle) }
    }

    fun stopNativeHandle(state: UsbExclusiveNativeState, reason: String) {
        if (state.handle == 0L) return
        NPLogger.d(
            TAG,
            "stopInternalLocked(): queue close handle=${state.handle} source=${state.source} " +
                "reason=$reason streaming=${state.streaming} runtime=${state.runtimeReport}"
        )
        runCatching { native.stop(state.handle) }
    }

    fun matchesActiveDevice(device: UsbDevice?): Boolean {
        if (!hasActiveDevice()) return false
        if (device == null) return true
        return device.deviceId == activeDeviceId.get() ||
            device.deviceName == activeDeviceName.get()
    }

    fun isAudioStreamingDevice(device: UsbDevice): Boolean {
        return (0 until device.interfaceCount).any { index ->
            val usbInterface = device.getInterface(index)
            usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                usbInterface.interfaceSubclass == 0x02
        }
    }

    fun matchesPreferredDevice(context: Context, device: UsbDevice): Boolean {
        return device.matchesUsbExclusiveDeviceKey(preferredDeviceKey(context))
    }

    fun preferredDeviceKey(context: Context): String {
        return normalizePreferredDeviceKey(selectedDeviceKeyPort.readSelectedDeviceKey(context))
    }

    private fun normalizePreferredDeviceKey(selectedKey: String): String =
        selectedKey.ifBlank { DEFAULT_USB_EXCLUSIVE_DEVICE_KEY }

    private fun hasActiveDevice(): Boolean {
        return activeDeviceId.get() != NO_ACTIVE_USB_DEVICE_ID || activeDeviceName.get() != null
    }

    fun openDevice(context: Context, selectedDeviceKey: String): OpenedDevice? {
        val opened = openDeviceConnection(context, selectedDeviceKey) ?: return null
        val (device, connection) = opened
        activeDeviceName.set(device.deviceName)
        activeDeviceId.set(device.deviceId)
        activeDeviceKey.set(device.usbExclusiveDeviceKey())
        ioGate.open()
        return OpenedDevice(device, connection)
    }

    fun openTone(context: Context, openedDevice: OpenedDevice): ToneOpenResult {
        soundGuard.activate(context, "tone_open_start")
        val handle = openToneNativeHandle(openedDevice)
        if (handle == 0L) return failedToneOpen(context, openedDevice)
        return startToneOnOpenHandle(context, openedDevice, handle)
    }

    private fun openToneNativeHandle(openedDevice: OpenedDevice): Long {
        return runCatching { native.open(openedDevice.connection) }
            .getOrElse { error ->
                NPLogger.e(TAG, "Failed to open USB exclusive native session", error)
                0L
            }
    }

    private fun failedToneOpen(context: Context, openedDevice: OpenedDevice): ToneOpenResult {
        val openError = runCatching { native.lastOpenError() }.getOrDefault("nativeOpen failed")
        NPLogger.e(
            TAG,
            "nativeOpen failed for device=${openedDevice.displayName}, " +
                "fd=${openedDevice.connection.fileDescriptor}, error=$openError"
        )
        runCatching { openedDevice.connection.close() }
        endDeviceSession()
        soundGuard.releaseWhenNativeIdle(context, "tone_open_failed")
        return ToneOpenResult(openedDevice = openedDevice, error = openError)
    }

    private fun startToneOnOpenHandle(
        context: Context,
        openedDevice: OpenedDevice,
        handle: Long
    ): ToneOpenResult {
        if (!ioGate.isOpen()) {
            closeOpeningHandle(handle, openedDevice.connection, "tone_detached_during_open")
            return ToneOpenResult(openedDevice = openedDevice)
        }
        val started = runCatching { native.startGeneratedTone(handle) }.getOrDefault(false)
        return finishToneStart(context, openedDevice, handle, started)
    }

    private fun finishToneStart(
        context: Context,
        openedDevice: OpenedDevice,
        handle: Long,
        started: Boolean
    ): ToneOpenResult {
        val startError = toneStartError(handle, started)
            ?: return ToneOpenResult(handle = handle, openedDevice = openedDevice)
        closeOpeningHandle(handle, openedDevice.connection, "tone_start_failed")
        soundGuard.releaseWhenNativeIdle(context, "tone_start_failed")
        return ToneOpenResult(openedDevice = openedDevice, error = startError)
    }

    private fun toneStartError(handle: Long, started: Boolean): String? {
        if (!started) return native.runtimeReport(handle)
        if (!ioGate.isOpen()) return "deviceOnline=false lastError=usb_device_detached"
        return null
    }

    fun openPlayerPcm(
        context: Context,
        openedDevice: OpenedDevice,
        outputCandidates: List<ResolvedUsbOutputFormat>,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int,
        appInForeground: Boolean,
        allowMixedPlayback: Boolean
    ): PlayerOpenResult {
        if (!allowMixedPlayback) {
            soundGuard.activate(context, "player_pcm_open_start")
        }
        val opened = openFirstPlayerCandidate(openedDevice, outputCandidates)
        if (opened.handle == 0L) {
            return failedPlayerOpen(context, openedDevice, opened.error)
        }
        return prepareOpenedPlayerPcm(
            context,
            openedDevice,
            opened,
            inputSampleRate,
            inputChannelCount,
            inputEncoding,
            appInForeground
        )
    }

    private data class CandidateOpen(
        val handle: Long = 0L,
        val outputFormat: ResolvedUsbOutputFormat? = null,
        val error: String = "nativeOpen failed"
    )

    private fun openFirstPlayerCandidate(
        openedDevice: OpenedDevice,
        outputCandidates: List<ResolvedUsbOutputFormat>
    ): CandidateOpen {
        var lastError = "nativeOpen failed"
        for (candidate in outputCandidates) {
            NPLogger.i(
                TAG,
                "openPlayerPcm(): opening device=${openedDevice.displayName} " +
                    "fd=${openedDevice.connection.fileDescriptor} output=${candidate.description}"
            )
            val handle = openPlayerCandidate(openedDevice.connection, candidate)
            if (handle != 0L) return CandidateOpen(handle, candidate)
            lastError = runCatching { native.lastOpenError() }.getOrDefault("nativeOpen failed")
            NPLogger.w(
                TAG,
                "openPlayerPcm(): candidate open failed output=${candidate.description} " +
                    "error=$lastError"
            )
            if (!lastError.supportsAlternativeOutputRetry()) break
        }
        return CandidateOpen(error = lastError)
    }

    private fun openPlayerCandidate(
        connection: UsbDeviceConnection,
        candidate: ResolvedUsbOutputFormat
    ): Long {
        return runCatching {
            native.open(
                connection = connection,
                sampleRate = candidate.sampleRate,
                channelCount = candidate.channelCount,
                bitsPerSample = candidate.bitDepth,
                subslotBytes = candidate.subslotBytes
            )
        }.getOrElse { error ->
            NPLogger.e(TAG, "Failed to open player USB exclusive session", error)
            0L
        }
    }

    private fun failedPlayerOpen(
        context: Context,
        openedDevice: OpenedDevice,
        openError: String
    ): PlayerOpenResult {
        NPLogger.e(TAG, "openPlayerPcm(): native open failed error=$openError")
        runCatching { openedDevice.connection.close() }
        endDeviceSession()
        soundGuard.releaseWhenNativeIdle(context, "player_pcm_open_failed")
        return PlayerOpenResult(
            openedDevice = openedDevice,
            error = openError,
            shouldFuseOpen = true
        )
    }

    private fun prepareOpenedPlayerPcm(
        context: Context,
        openedDevice: OpenedDevice,
        opened: CandidateOpen,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int,
        appInForeground: Boolean
    ): PlayerOpenResult {
        val handle = opened.handle
        if (!ioGate.isOpen()) {
            closeOpeningHandle(handle, openedDevice.connection, "player_pcm_detached_during_open")
            return PlayerOpenResult(openedDevice = openedDevice)
        }
        val output = requireNotNull(opened.outputFormat)
        val prepared = preparePlayerPcm(
            handle = handle,
            outputFormat = output,
            inputSampleRate = inputSampleRate,
            inputChannelCount = inputChannelCount,
            inputEncoding = inputEncoding,
            appInForeground = appInForeground
        )
        return finishPreparedPlayerPcm(context, openedDevice, output, handle, prepared)
    }

    private fun finishPreparedPlayerPcm(
        context: Context,
        openedDevice: OpenedDevice,
        output: ResolvedUsbOutputFormat,
        handle: Long,
        prepared: Boolean
    ): PlayerOpenResult {
        val prepareError = playerPrepareError(handle, prepared)
            ?: return PlayerOpenResult(
                handle = handle,
                openedDevice = openedDevice,
                outputFormat = output
            )
        NPLogger.e(TAG, "openPlayerPcm(): native prepare failed handle=$handle error=$prepareError")
        closeOpeningHandle(handle, openedDevice.connection, "player_pcm_prepare_failed")
        soundGuard.releaseWhenNativeIdle(context, "player_pcm_prepare_failed")
        return PlayerOpenResult(
            openedDevice = openedDevice,
            error = prepareError,
            shouldFuseOpen = true
        )
    }

    private fun playerPrepareError(handle: Long, prepared: Boolean): String? {
        if (!prepared) return native.runtimeReport(handle)
        if (!ioGate.isOpen()) return "deviceOnline=false lastError=usb_device_detached"
        return null
    }

    fun tryReconfigurePlayerPcm(
        handle: Long,
        currentOutputDescription: String,
        outputCandidates: List<ResolvedUsbOutputFormat>,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int,
        appInForeground: Boolean,
        focusSuppressed: Boolean,
        shouldRetry: (String) -> Boolean,
        onRuntimeReport: (String) -> Unit
    ): ReconfigureResult {
        val reconfigureCandidates = outputCandidates.filterNot { candidate ->
            UsbExclusiveOutputFormatResolver.canReuseEquivalentOutput(
                currentDescription = currentOutputDescription,
                preferredDescription = candidate.description
            )
        }
        var lastFailureReport: String? = null
        for (candidate in reconfigureCandidates) {
            val reconfigured = native.reconfigurePlayerPcmOutput(
                handle = handle,
                sampleRate = candidate.sampleRate,
                channelCount = candidate.channelCount,
                bitsPerSample = candidate.bitDepth,
                subslotBytes = candidate.subslotBytes
            )
            val reconfigureReport = native.runtimeReport(handle)
            onRuntimeReport(reconfigureReport)
            if (!reconfigured) {
                lastFailureReport = reconfigureReport
                NPLogger.w(
                    TAG,
                    "openPlayerPcm(): in-place output reconfigure failed " +
                        "handle=$handle output=${candidate.description} report=$reconfigureReport"
                )
                if (!shouldRetry(reconfigureReport)) break
                continue
            }
            val prepared = preparePlayerPcm(
                handle = handle,
                outputFormat = candidate,
                inputSampleRate = inputSampleRate,
                inputChannelCount = inputChannelCount,
                inputEncoding = inputEncoding,
                appInForeground = appInForeground
            )
            native.setPlayerFocusMuted(handle, focusSuppressed)
            val preparedReport = native.runtimeReport(handle)
            onRuntimeReport(preparedReport)
            if (!prepared) {
                lastFailureReport = preparedReport
                NPLogger.w(
                    TAG,
                    "openPlayerPcm(): in-place reconfigure prepare failed " +
                        "handle=$handle output=${candidate.description} report=$preparedReport"
                )
                break
            }
            return ReconfigureResult(outputFormat = candidate, report = preparedReport)
        }
        return ReconfigureResult(report = lastFailureReport)
    }

    fun preparePlayerPcm(
        handle: Long,
        outputFormat: ResolvedUsbOutputFormat,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int,
        appInForeground: Boolean
    ): Boolean {
        return preparePlayerPcmStages(
            handle,
            outputFormat,
            inputSampleRate,
            inputChannelCount,
            inputEncoding,
            appInForeground
        ).prepared
    }

    fun prepareExistingPlayerPcm(
        handle: Long,
        outputDescription: String,
        bufferDurationMs: Int,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int,
        appInForeground: Boolean
    ): String? {
        if (!ioGate.isOpen()) return null
        if (!native.configurePlayerBufferDuration(handle, bufferDurationMs)) return null
        if (!configureTransferWindow(handle, bufferDurationMs, appInForeground)) return null
        if (!native.preparePlayerPcm(
                handle,
                inputSampleRate,
                inputChannelCount,
                inputEncodingForPrepare(inputEncoding, outputDescription)
            )
        ) return null
        return native.runtimeReport(handle)
    }

    private fun preparePlayerPcmStages(
        handle: Long,
        outputFormat: ResolvedUsbOutputFormat,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int,
        appInForeground: Boolean
    ): PreparedPcmStages {
        val bufferConfigured = native.configurePlayerBufferDuration(
            handle,
            outputFormat.bufferDurationMs
        )
        if (!bufferConfigured) return PreparedPcmStages(
            bufferConfigured = false,
            transferWindowConfigured = false,
            prepared = false
        )
        val transferWindowConfigured = configureTransferWindow(
            handle,
            outputFormat.bufferDurationMs,
            appInForeground
        )
        if (!transferWindowConfigured) return PreparedPcmStages(
            bufferConfigured = true,
            transferWindowConfigured = false,
            prepared = false
        )
        val prepared = native.preparePlayerPcm(
            handle = handle,
            inputSampleRate = inputSampleRate,
            inputChannelCount = inputChannelCount,
            inputEncoding = UsbExclusiveOutputFormatResolver.preparedInputPcmFormat(
                inputEncoding = inputEncoding,
                outputFormat = outputFormat
            )?.encoding ?: inputEncoding
        )
        return PreparedPcmStages(
            bufferConfigured = true,
            transferWindowConfigured = true,
            prepared = prepared
        )
    }

    fun rearmPlayerPcm(
        handle: Long,
        outputFormat: ResolvedUsbOutputFormat,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int,
        appInForeground: Boolean,
        focusSuppressed: Boolean
    ): RearmResult {
        val reconfigured = native.reconfigurePlayerPcmOutput(
            handle = handle,
            sampleRate = outputFormat.sampleRate,
            channelCount = outputFormat.channelCount,
            bitsPerSample = outputFormat.bitDepth,
            subslotBytes = outputFormat.subslotBytes
        )
        val stages = if (reconfigured) {
            preparePlayerPcmStages(
                handle,
                outputFormat,
                inputSampleRate,
                inputChannelCount,
                inputEncoding,
                appInForeground
            )
        } else {
            PreparedPcmStages(
                bufferConfigured = false,
                transferWindowConfigured = false,
                prepared = false
            )
        }
        native.setPlayerFocusMuted(handle, focusSuppressed)
        return RearmResult(
            reconfigured = reconfigured,
            bufferConfigured = stages.bufferConfigured,
            transferWindowConfigured = stages.transferWindowConfigured,
            prepared = stages.prepared,
            report = native.runtimeReport(handle),
            completedFrames = native.completedAudioFrames(handle),
            queuedFrames = native.queuedPlayerFrames(handle)
        )
    }

    fun inputEncodingForPrepare(inputEncoding: Int, outputDescription: String): Int {
        return UsbExclusiveOutputFormatResolver.preparedInputPcmFormat(
            inputEncoding = inputEncoding,
            outputDescription = outputDescription
        )?.encoding ?: inputEncoding
    }

    fun configureTransferWindow(
        handle: Long,
        bufferDurationMs: Int,
        appInForeground: Boolean
    ): Boolean {
        return native.configurePlayerTransferWindow(
            handle = handle,
            durationMs = usbExclusiveTransferWindowDurationMs(
                bufferDurationMs = bufferDurationMs,
                appInForeground = appInForeground
            )
        )
    }

    fun configurePlayerBufferDuration(
        handle: Long,
        durationMs: Int,
        appInForeground: Boolean
    ): Boolean {
        if (!native.configurePlayerBufferDuration(handle, durationMs)) return false
        return configureTransferWindow(handle, durationMs, appInForeground)
    }

    fun commitConnection(connection: UsbDeviceConnection) {
        activeConnection = connection
    }

    fun takeActiveConnection(): UsbDeviceConnection? {
        return activeConnection.also { activeConnection = null }
    }

    fun closeIdleConnection(connection: UsbDeviceConnection?) {
        runCatching { connection?.close() }
    }

    fun endDeviceSession() {
        ioGate.close()
        clearActiveDeviceIdentity()
    }

    fun clearActiveDeviceIdentity() {
        activeDeviceId.set(NO_ACTIVE_USB_DEVICE_ID)
        activeDeviceName.set(null)
        activeDeviceKey.set(null)
    }

    fun closeOpeningHandle(handle: Long, connection: UsbDeviceConnection, reason: String) {
        ioGate.close()
        runCatching { native.stop(handle) }
        clearActiveDeviceIdentity()
        NPLogger.d(TAG, "closeOpeningHandle(): queue handle=$handle fd=${connection.fileDescriptor}")
        scheduleClose(CloseRequest(handle, connection, "opening", reason))
        if (activeConnection === connection) activeConnection = null
    }

    fun scheduleClose(request: CloseRequest?) {
        if (request == null) return
        nativeCloseInFlight.incrementAndGet()
        nativeCloseExecutor.execute { performClose(request) }
    }

    private fun performClose(request: CloseRequest) {
        try {
            NPLogger.d(
                TAG,
                "native close begin: handle=${request.handle} source=${request.source} " +
                    "reason=${request.reason}"
            )
            awaitWriterDrain(request)
            runCatching { native.close(request.handle) }
                .onFailure { error ->
                    NPLogger.w(
                        TAG,
                        "native close failed: handle=${request.handle} reason=${request.reason}",
                        error
                    )
                }
            runCatching { request.connection?.close() }
            NPLogger.d(
                TAG,
                "native close done: handle=${request.handle} source=${request.source} " +
                    "reason=${request.reason}"
            )
        } finally {
            onCloseComplete(request, nativeCloseInFlight.decrementAndGet())
        }
    }

    private fun awaitWriterDrain(request: CloseRequest) {
        runCatching { ioGate.awaitDrained(timeoutMs = EMERGENCY_CLOSE_WAIT_MS) }
            .onSuccess { drained -> reportWriterDrainResult(request, drained) }
            .onFailure { error -> reportWriterDrainInterruption(request, error) }
    }

    private fun reportWriterDrainResult(request: CloseRequest, drained: Boolean) {
        if (drained) return
        NPLogger.w(
            TAG,
            "writer drain timed out before native close: " +
                "handle=${request.handle} reason=${request.reason}"
        )
    }

    private fun reportWriterDrainInterruption(request: CloseRequest, error: Throwable) {
        Thread.currentThread().interrupt()
        NPLogger.w(TAG, "writer drain interrupted for handle=${request.handle}", error)
    }

    private fun String.supportsAlternativeOutputRetry(): Boolean {
        val code = usbExclusiveErrorCode()
        if (code.allowsAlternativeOutputRetry) return true
        if (isBlank() || NONRETRYABLE_OPEN_ERRORS.any { contains(it, ignoreCase = true) }) {
            return false
        }
        return RETRYABLE_OPEN_ERRORS.any { contains(it, ignoreCase = true) }
    }

    private companion object {
        const val TAG = "NERI-UsbExclusiveNative"
        const val NO_ACTIVE_USB_DEVICE_ID = -1
        const val EMERGENCY_CLOSE_WAIT_MS = 1_500L
        val NONRETRYABLE_OPEN_ERRORS = listOf(
            "no permitted usb audio streaming device", "permission", "feedback_scheduler",
            "wrap_sys_device_failed", "claim_audio_function_failed", "claim_interface",
            "set_alt_failed", "usb_device_detached"
        )
        val RETRYABLE_OPEN_ERRORS = listOf(
            "no_compatible_usb_audio_format", "sample_rate_negotiation_failed"
        )
    }
}

internal interface UsbExclusiveNativeSessionPort {
    fun open(
        connection: UsbDeviceConnection,
        sampleRate: Int = 48_000,
        channelCount: Int = 2,
        bitsPerSample: Int = 16,
        subslotBytes: Int = 2
    ): Long
    fun lastOpenError(): String
    fun startGeneratedTone(handle: Long): Boolean
    fun runtimeReport(handle: Long): String
    fun configurePlayerBufferDuration(handle: Long, durationMs: Int): Boolean
    fun configurePlayerTransferWindow(handle: Long, durationMs: Int): Boolean
    fun preparePlayerPcm(
        handle: Long,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): Boolean
    fun reconfigurePlayerPcmOutput(
        handle: Long,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        subslotBytes: Int
    ): Boolean
    fun setPlayerFocusMuted(handle: Long, muted: Boolean): Boolean
    fun completedAudioFrames(handle: Long): Long
    fun queuedPlayerFrames(handle: Long): Long
    fun markDeviceDetached(handle: Long)
    fun stop(handle: Long)
    fun close(handle: Long)
}

internal interface UsbExclusiveSoundGuardPort {
    fun activate(context: Context, reason: String)
    fun releaseWhenNativeIdle(context: Context, reason: String)
}

private object AndroidUsbExclusiveNativeSessionPort : UsbExclusiveNativeSessionPort {
    override fun open(
        connection: UsbDeviceConnection,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        subslotBytes: Int
    ): Long = UsbExclusiveNativeBridge.open(
        connection,
        sampleRate,
        channelCount,
        bitsPerSample,
        subslotBytes
    )

    override fun lastOpenError(): String = UsbExclusiveNativeBridge.lastOpenError()
    override fun startGeneratedTone(handle: Long): Boolean =
        UsbExclusiveNativeBridge.startGeneratedTone(handle)
    override fun runtimeReport(handle: Long): String = UsbExclusiveNativeBridge.runtimeReport(handle)
    override fun configurePlayerBufferDuration(handle: Long, durationMs: Int): Boolean =
        UsbExclusiveNativeBridge.configurePlayerBufferDuration(handle, durationMs)
    override fun configurePlayerTransferWindow(handle: Long, durationMs: Int): Boolean =
        UsbExclusiveNativeBridge.configurePlayerTransferWindow(handle, durationMs)
    override fun preparePlayerPcm(
        handle: Long,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): Boolean = UsbExclusiveNativeBridge.preparePlayerPcm(
        handle,
        inputSampleRate,
        inputChannelCount,
        inputEncoding
    )
    override fun reconfigurePlayerPcmOutput(
        handle: Long,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        subslotBytes: Int
    ): Boolean = UsbExclusiveNativeBridge.reconfigurePlayerPcmOutput(
        handle,
        sampleRate,
        channelCount,
        bitsPerSample,
        subslotBytes
    )
    override fun setPlayerFocusMuted(handle: Long, muted: Boolean): Boolean =
        UsbExclusiveNativeBridge.setPlayerFocusMuted(handle, muted)
    override fun completedAudioFrames(handle: Long): Long =
        UsbExclusiveNativeBridge.completedAudioFrames(handle)
    override fun queuedPlayerFrames(handle: Long): Long =
        UsbExclusiveNativeBridge.queuedPlayerFrames(handle)
    override fun markDeviceDetached(handle: Long) {
        UsbExclusiveNativeBridge.markDeviceDetached(handle)
    }
    override fun stop(handle: Long) = UsbExclusiveNativeBridge.stop(handle)
    override fun close(handle: Long) = UsbExclusiveNativeBridge.close(handle)
}

private object AndroidUsbExclusiveSoundGuardPort : UsbExclusiveSoundGuardPort {
    override fun activate(context: Context, reason: String) =
        UsbExclusiveSystemSoundGuard.activate(context, reason)
    override fun releaseWhenNativeIdle(context: Context, reason: String) =
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(context, reason)
}
