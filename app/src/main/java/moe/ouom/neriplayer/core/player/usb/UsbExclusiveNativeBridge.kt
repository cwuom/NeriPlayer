package moe.ouom.neriplayer.core.player.usb

import android.hardware.usb.UsbDeviceConnection
import java.nio.ByteBuffer
import moe.ouom.neriplayer.util.NPLogger

internal object UsbExclusiveNativeBridge {
    private const val TAG = "NERI-UsbExclusiveNative"

    @Volatile
    private var libraryReady = false

    @Volatile
    private var loadAttempted = false

    fun ensureLoaded(): Boolean {
        if (libraryReady) return true
        if (loadAttempted) return false
        synchronized(this) {
            if (libraryReady) return true
            if (loadAttempted) return false
            loadAttempted = true
            return runCatching {
                System.loadLibrary("_neri")
                libraryReady = true
                true
            }.getOrElse { error ->
                NPLogger.e(TAG, "Failed to load native USB exclusive bridge", error)
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
        return nativeOpen(
            fd = connection.fileDescriptor,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            subslotBytes = subslotBytes
        )
    }

    fun startGeneratedTone(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return nativeStartGeneratedTone(handle)
    }

    fun preparePlayerPcm(
        handle: Long,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return nativePreparePlayerPcm(
            handle = handle,
            inputSampleRate = inputSampleRate,
            inputChannelCount = inputChannelCount,
            inputEncoding = inputEncoding
        )
    }

    fun writePlayerPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        volume: Float
    ): Int {
        if (handle == 0L || size <= 0 || !buffer.isDirect || !ensureLoaded()) return 0
        return nativeWritePlayerPcm(
            handle = handle,
            buffer = buffer,
            offset = offset,
            size = size,
            volume = volume
        )
    }

    fun startPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return nativeStartPlayerPcm(handle)
    }

    fun playPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return nativePlayPlayerPcm(handle)
    }

    fun pausePlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return nativePausePlayerPcm(handle)
    }

    fun flushPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return nativeFlushPlayerPcm(handle)
    }

    fun setPlayerVolume(handle: Long, volume: Float): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return nativeSetPlayerVolume(handle, volume.coerceIn(0f, 1f))
    }

    fun configurePlayerBufferDuration(handle: Long, durationMs: Int): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return nativeConfigurePlayerBufferDuration(handle, durationMs)
    }

    fun completedAudioFrames(handle: Long): Long {
        if (handle == 0L || !ensureLoaded()) return 0L
        return nativeGetCompletedAudioFrames(handle).coerceAtLeast(0L)
    }

    fun queuedPlayerFrames(handle: Long): Long {
        if (handle == 0L || !ensureLoaded()) return 0L
        return nativeGetQueuedPlayerFrames(handle).coerceAtLeast(0L)
    }

    fun stop(handle: Long) {
        if (handle == 0L || !ensureLoaded()) return
        nativeStop(handle)
    }

    fun close(handle: Long) {
        if (handle == 0L || !ensureLoaded()) return
        nativeClose(handle)
    }

    fun runtimeReport(handle: Long): String {
        if (!ensureLoaded()) return "native_unavailable"
        return nativeRuntimeReport(handle)
    }

    fun lastOpenError(): String {
        if (!ensureLoaded()) return "native_unavailable"
        return nativeLastOpenError()
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
    private external fun nativeConfigurePlayerBufferDuration(handle: Long, durationMs: Int): Boolean

    @JvmStatic
    private external fun nativeGetCompletedAudioFrames(handle: Long): Long

    @JvmStatic
    private external fun nativeGetQueuedPlayerFrames(handle: Long): Long

    @JvmStatic
    private external fun nativeStop(handle: Long)

    @JvmStatic
    private external fun nativeClose(handle: Long)

    @JvmStatic
    private external fun nativeRuntimeReport(handle: Long): String

    @JvmStatic
    private external fun nativeLastOpenError(): String
}
