package moe.ouom.neriplayer.core.player.usb.transport

import android.hardware.usb.UsbDeviceConnection
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import moe.ouom.neriplayer.core.logging.NPLogger

internal object UsbExclusiveNativeBridge {
    private const val TAG = "NERI-UsbExclusiveNative"
    private const val BRIDGE_FAILURE_FALLBACK_REPORT =
        "running=false transportFailed=true lastError=jni_bridge_failure"

    @Volatile
    private var libraryReady = false

    @Volatile
    private var loadAttempted = false

    private val globalFailureReport = AtomicReference<String?>(null)

    private inline fun <T : Any> callNative(
        operation: String,
        context: () -> String? = { null },
        block: () -> T
    ): T? {
        return try {
            block()
        } catch (error: Exception) {
            logNativeFailure(operation, context(), error)
            null
        } catch (error: LinkageError) {
            logNativeFailure(operation, context(), error)
            null
        }
    }

    private inline fun callNativeBoolean(
        operation: String,
        context: () -> String,
        block: () -> Boolean
    ): Boolean {
        val succeeded = callNative(operation, context = context, block = block) ?: return false
        if (!succeeded) {
            NPLogger.e(TAG, "$operation returned false (${context()})")
        }
        return succeeded
    }

    private inline fun callNativeInt(
        operation: String,
        context: () -> String,
        block: () -> Int
    ): Int {
        return callNative(operation, context = context, block = block) ?: 0
    }

    private fun logNativeFailure(operation: String, context: String?, error: Throwable) {
        val contextSuffix = context?.let { " ($it)" }.orEmpty()
        NPLogger.e(TAG, "$operation threw$contextSuffix", error)
    }

    private fun recordGlobalFailure(operation: String, context: String?, error: Throwable) {
        val failureKind = if (error is LinkageError) "linkage" else "exception"
        val report =
            "running=false transportFailed=true " +
                "lastError=jni_bridge_${failureKind}_$operation"
        if (!globalFailureReport.compareAndSet(null, report)) return

        logNativeFailure(operation, context, error)
    }

    fun ensureLoaded(): Boolean {
        if (libraryReady) return true
        if (loadAttempted) return false
        synchronized(this) {
            if (libraryReady) return true
            if (loadAttempted) return false
            loadAttempted = true
            return try {
                System.loadLibrary("_neri")
                libraryReady = true
                true
            } catch (error: Exception) {
                recordGlobalFailure("loadLibrary", null, error)
                false
            } catch (error: LinkageError) {
                recordGlobalFailure("loadLibrary", null, error)
                false
            }
        }
    }

    fun open(
        connection: UsbDeviceConnection,
        sampleRate: Int = 48_000,
        channelCount: Int = 2,
        bitsPerSample: Int = 16,
        subslotBytes: Int = 2
    ): Long {
        if (!ensureLoaded()) return 0L
        val fd = connection.fileDescriptor
        val handle = callNative(
            operation = "nativeOpen",
            context = {
                "fd=$fd sampleRate=$sampleRate channelCount=$channelCount " +
                    "bitsPerSample=$bitsPerSample subslotBytes=$subslotBytes"
            }
        ) {
            nativeOpen(
                fd = fd,
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitsPerSample = bitsPerSample,
                subslotBytes = subslotBytes
            )
        } ?: return 0L
        if (handle == 0L) {
            NPLogger.e(
                TAG,
                "nativeOpen returned 0 " +
                    "(fd=$fd sampleRate=$sampleRate channelCount=$channelCount " +
                    "bitsPerSample=$bitsPerSample subslotBytes=$subslotBytes)"
            )
        }
        return handle
    }

    fun startGeneratedTone(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return callNativeBoolean("nativeStartGeneratedTone", { "handle=$handle" }) {
            nativeStartGeneratedTone(handle)
        }
    }

    fun preparePlayerPcm(
        handle: Long,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return callNativeBoolean(
            operation = "nativePreparePlayerPcm",
            context = {
                "handle=$handle inputSampleRate=$inputSampleRate " +
                    "inputChannelCount=$inputChannelCount inputEncoding=$inputEncoding"
            }
        ) {
            nativePreparePlayerPcm(
                handle = handle,
                inputSampleRate = inputSampleRate,
                inputChannelCount = inputChannelCount,
                inputEncoding = inputEncoding
            )
        }
    }

    fun reconfigurePlayerPcmOutput(
        handle: Long,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        subslotBytes: Int
    ): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return callNativeBoolean(
            operation = "nativeReconfigurePlayerPcmOutput",
            context = {
                "handle=$handle sampleRate=$sampleRate channelCount=$channelCount " +
                    "bitsPerSample=$bitsPerSample subslotBytes=$subslotBytes"
            }
        ) {
            nativeReconfigurePlayerPcmOutput(
                handle = handle,
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitsPerSample = bitsPerSample,
                subslotBytes = subslotBytes
            )
        }
    }

    fun writePlayerPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        volume: Float
    ): Int {
        if (handle == 0L || size <= 0 || !buffer.isDirect || !ensureLoaded()) return 0
        return callNativeInt(
            operation = "nativeWritePlayerPcm",
            context = { "handle=$handle offset=$offset size=$size" }
        ) {
            nativeWritePlayerPcm(
                handle = handle,
                buffer = buffer,
                offset = offset,
                size = size,
                volume = volume
            )
        }
    }

    fun startPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return callNativeBoolean("nativeStartPlayerPcm", { "handle=$handle" }) {
            nativeStartPlayerPcm(handle)
        }
    }

    fun playPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return callNativeBoolean("nativePlayPlayerPcm", { "handle=$handle" }) {
            nativePlayPlayerPcm(handle)
        }
    }

    fun pausePlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return callNativeBoolean("nativePausePlayerPcm", { "handle=$handle" }) {
            nativePausePlayerPcm(handle)
        }
    }

    fun flushPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return callNativeBoolean("nativeFlushPlayerPcm", { "handle=$handle" }) {
            nativeFlushPlayerPcm(handle)
        }
    }

    fun setPlayerVolume(handle: Long, volume: Float): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        val normalizedVolume = volume.coerceIn(0f, 1f)
        return callNativeBoolean(
            operation = "nativeSetPlayerVolume",
            context = { "handle=$handle volume=$normalizedVolume" }
        ) {
            nativeSetPlayerVolume(handle, normalizedVolume)
        }
    }

    fun setPlayerFocusMuted(handle: Long, muted: Boolean): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return callNativeBoolean(
            operation = "nativeSetPlayerFocusMuted",
            context = { "handle=$handle muted=$muted" }
        ) {
            nativeSetPlayerFocusMuted(handle, muted)
        }
    }

    fun configurePlayerBufferDuration(handle: Long, durationMs: Int): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return callNativeBoolean(
            operation = "nativeConfigurePlayerBufferDuration",
            context = { "handle=$handle durationMs=$durationMs" }
        ) {
            nativeConfigurePlayerBufferDuration(handle, durationMs)
        }
    }

    fun completedAudioFrames(handle: Long): Long {
        if (handle == 0L || !ensureLoaded()) return 0L
        return callNative("nativeGetCompletedAudioFrames", context = { "handle=$handle" }) {
            nativeGetCompletedAudioFrames(handle)
        }?.coerceAtLeast(0L) ?: 0L
    }

    fun queuedPlayerFrames(handle: Long): Long {
        if (handle == 0L || !ensureLoaded()) return 0L
        return callNative("nativeGetQueuedPlayerFrames", context = { "handle=$handle" }) {
            nativeGetQueuedPlayerFrames(handle)
        }?.coerceAtLeast(0L) ?: 0L
    }

    fun playerPcmFreeBytes(handle: Long): Long? {
        if (handle == 0L || !ensureLoaded()) return null
        return callNative("nativeGetPlayerPcmFreeBytes", context = { "handle=$handle" }) {
            nativeGetPlayerPcmFreeBytes(handle)
        }?.takeIf { it >= 0L }
    }

    fun stop(handle: Long) {
        if (handle == 0L || !ensureLoaded()) return
        callNative(
            operation = "nativeStop",
            context = { "handle=$handle" }
        ) {
            nativeStop(handle)
        }
    }

    fun markDeviceDetached(handle: Long) {
        if (handle == 0L || !ensureLoaded()) return
        callNative(
            operation = "nativeMarkDeviceDetached",
            context = { "handle=$handle" }
        ) {
            nativeMarkDeviceDetached(handle)
        }
    }

    fun close(handle: Long) {
        if (handle == 0L || !ensureLoaded()) return
        callNative(
            operation = "nativeClose",
            context = { "handle=$handle" }
        ) {
            nativeClose(handle)
        }
    }

    fun runtimeReport(handle: Long): String {
        globalFailureReport.get()?.let { return it }
        if (!ensureLoaded()) {
            return globalFailureReport.get() ?: "native_unavailable"
        }
        return callNative("nativeRuntimeReport", context = { "handle=$handle" }) {
            nativeRuntimeReport(handle)
        } ?: BRIDGE_FAILURE_FALLBACK_REPORT
    }

    fun lastOpenError(): String {
        globalFailureReport.get()?.let { return it }
        if (!ensureLoaded()) {
            return globalFailureReport.get() ?: "native_unavailable"
        }
        return callNative("nativeLastOpenError") {
            nativeLastOpenError()
        } ?: BRIDGE_FAILURE_FALLBACK_REPORT
    }

    @JvmStatic
    private external fun nativeOpen(
        fd: Int,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        subslotBytes: Int
    ): Long

    @JvmStatic
    private external fun nativeStartGeneratedTone(handle: Long): Boolean

    @JvmStatic
    private external fun nativePreparePlayerPcm(
        handle: Long,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): Boolean

    @JvmStatic
    private external fun nativeReconfigurePlayerPcmOutput(
        handle: Long,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        subslotBytes: Int
    ): Boolean

    @JvmStatic
    private external fun nativeWritePlayerPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        volume: Float
    ): Int

    @JvmStatic
    private external fun nativeStartPlayerPcm(handle: Long): Boolean

    @JvmStatic
    private external fun nativePlayPlayerPcm(handle: Long): Boolean

    @JvmStatic
    private external fun nativePausePlayerPcm(handle: Long): Boolean

    @JvmStatic
    private external fun nativeFlushPlayerPcm(handle: Long): Boolean

    @JvmStatic
    private external fun nativeSetPlayerVolume(handle: Long, volume: Float): Boolean

    @JvmStatic
    private external fun nativeSetPlayerFocusMuted(handle: Long, muted: Boolean): Boolean

    @JvmStatic
    private external fun nativeConfigurePlayerBufferDuration(handle: Long, durationMs: Int): Boolean

    @JvmStatic
    private external fun nativeGetCompletedAudioFrames(handle: Long): Long

    @JvmStatic
    private external fun nativeGetQueuedPlayerFrames(handle: Long): Long

    @JvmStatic
    private external fun nativeGetPlayerPcmFreeBytes(handle: Long): Long

    @JvmStatic
    private external fun nativeStop(handle: Long)

    @JvmStatic
    private external fun nativeMarkDeviceDetached(handle: Long)

    @JvmStatic
    private external fun nativeClose(handle: Long)

    @JvmStatic
    private external fun nativeRuntimeReport(handle: Long): String

    @JvmStatic
    private external fun nativeLastOpenError(): String
}
