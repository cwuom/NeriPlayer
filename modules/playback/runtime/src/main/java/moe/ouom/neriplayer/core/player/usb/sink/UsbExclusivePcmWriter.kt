package moe.ouom.neriplayer.core.player.usb.sink

import moe.ouom.neriplayer.core.player.usb.transport.hasHealthyTransport
import moe.ouom.neriplayer.core.player.usb.transport.hasPcmQueue
import moe.ouom.neriplayer.core.player.usb.transport.isBenignBackpressure

import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.max
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.system.usbExclusiveFloatSampleForNativePipeline
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import moe.ouom.neriplayer.core.player.usb.transport.booleanField
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics
import moe.ouom.neriplayer.core.player.usb.transport.valueAfter
import moe.ouom.neriplayer.core.player.usb.transport.withLivePcmFreeBytes

internal data class UsbExclusiveNativeWriteSnapshot(
    val handle: Long,
    val sampleRate: Int,
    val frameBytes: Int,
    val channelCount: Int,
    val pcmEncoding: Int,
    val transportStarted: Boolean,
    val playing: Boolean,
    val usingNative: Boolean,
    val hasQueuedPcm: Boolean,
    val prerollMs: Long,
)

internal data class UsbExclusiveBackpressureObservation(
    val heldMs: Long,
    val completedTransfers: Long,
    val madeProgress: Boolean,
    val shouldRecover: Boolean,
)

internal interface UsbExclusivePcmWritePort {
    fun write(handle: Long, buffer: ByteBuffer, offset: Int, size: Int, volume: Float): Int
    fun runtimeReport(handle: Long): String
    fun freeBytes(handle: Long): Long?
    fun refreshRuntime(handle: Long)
    fun outputFormat(): String
    fun elapsedRealtimeMs(): Long
    fun mayParkCurrentThread(): Boolean
    fun parkNanos(nanos: Long)
}

internal object AndroidUsbExclusivePcmWritePort : UsbExclusivePcmWritePort {
    override fun write(handle: Long, buffer: ByteBuffer, offset: Int, size: Int, volume: Float): Int =
        UsbExclusiveSessionController.writePlayerPcm(handle, buffer, offset, size, volume)

    override fun runtimeReport(handle: Long): String =
        UsbExclusiveSessionController.runtimeReportForWritePlanning(handle)

    override fun freeBytes(handle: Long): Long? = UsbExclusiveSessionController.playerPcmFreeBytes(handle)

    override fun refreshRuntime(handle: Long) = UsbExclusiveSessionController.refreshRuntime(handle)

    override fun outputFormat(): String = UsbExclusiveSessionController.state.value.outputFormat

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun mayParkCurrentThread(): Boolean = Thread.currentThread() !== Looper.getMainLooper()?.thread

    override fun parkNanos(nanos: Long) = LockSupport.parkNanos(nanos)
}

internal class UsbExclusivePcmWriter(
    private val port: UsbExclusivePcmWritePort,
    private val publishNativeVolume: (Float) -> Unit,
) {
    private companion object {
        const val DIRECT_SCRATCH_CAPACITY_BYTES = 256 * 1024
        const val BACKPRESSURE_REFRESH_INTERVAL_MS = 250L
        const val BACKPRESSURE_PARK_MAX_US = 4_000L
        const val BACKPRESSURE_LOG_INTERVAL_MS = 2_000L
        const val BACKPRESSURE_STALL_RECOVERY_MS = 3_000L
        val audioThreadPriorityConfigured = ThreadLocal<Boolean>()
    }

    private var directScratch: ByteBuffer? = null
    private var softwareFloatInputFormat: PreparedUsbInputPcmFormat? = null
    private var softwareFloatConversionLogged = false
    private var lastNativeBackpressureRefreshAtMs = 0L
    private var lastNativeBackpressureLogAtMs = 0L
    private var nativeBackpressureStartedAtMs = 0L
    private var nativeBackpressureCompletedTransfersBaseline = -1L

    fun prepareDirectScratch() {
        if (directScratch?.capacity() == DIRECT_SCRATCH_CAPACITY_BYTES) return
        directScratch = try {
            ByteBuffer.allocateDirect(DIRECT_SCRATCH_CAPACITY_BYTES)
        } catch (error: Throwable) {
            NPLogger.w("NERI-UsbExclusive", "direct scratch allocation failed", error)
            null
        }
    }

    fun release() {
        directScratch = null
        clearSoftwareFloatConversionState()
        resetBackpressureObservation()
    }

    fun configureSoftwareFloatInput(usingNative: Boolean, pcmEncoding: Int) {
        if (!usingNative || pcmEncoding != C.ENCODING_PCM_FLOAT) {
            clearSoftwareFloatConversionState()
            return
        }
        softwareFloatInputFormat = UsbExclusiveOutputFormatResolver.preparedInputPcmFormat(
            inputEncoding = pcmEncoding,
            outputDescription = port.outputFormat(),
        )
        softwareFloatConversionLogged = false
    }

    fun clearSoftwareFloatConversionState() {
        softwareFloatInputFormat = null
        softwareFloatConversionLogged = false
    }

    fun writeNative(buffer: ByteBuffer, size: Int, nativeVolume: Float, state: UsbExclusiveNativeWriteSnapshot): Int {
        if (state.handle == 0L || size <= 0) return 0
        val floatFormat = softwareFloatInputFormat
        if (state.usingNative && state.pcmEncoding == C.ENCODING_PCM_FLOAT && floatFormat != null) {
            return writeConvertedFloat(buffer, size, nativeVolume, state, floatFormat)
        }
        publishNativeVolume(nativeVolume)
        return if (buffer.isDirect) {
            port.write(state.handle, buffer, buffer.position(), size, nativeVolume)
        } else {
            writeCopied(buffer, size, nativeVolume, state.handle)
        }
    }

    private fun writeConvertedFloat(
        buffer: ByteBuffer,
        size: Int,
        nativeVolume: Float,
        state: UsbExclusiveNativeWriteSnapshot,
        format: PreparedUsbInputPcmFormat,
    ): Int {
        val layout = floatFrameLayout(size, state, format) ?: return 0
        val scratch = scratchForBytes(layout.convertedSize) ?: return 0
        val summary = convertFloatSamples(buffer, size, layout.sourceFrames, state.channelCount, format, scratch)
            ?: return 0
        logFloatConversionIfNeeded(format, summary, nativeVolume)
        publishNativeVolume(nativeVolume)
        val writtenConverted = port.write(state.handle, scratch, 0, layout.convertedSize, nativeVolume)
        if (writtenConverted <= 0) return 0
        return (writtenConverted / layout.targetFrameBytes) * state.frameBytes
    }

    private fun floatFrameLayout(
        size: Int,
        state: UsbExclusiveNativeWriteSnapshot,
        format: PreparedUsbInputPcmFormat,
    ): FloatFrameLayout? {
        if (state.frameBytes <= 0 || format.bytesPerSample <= 0) return null
        val sourceFrames = size / state.frameBytes
        if (sourceFrames <= 0) return null
        val targetFrameBytes = state.channelCount * format.bytesPerSample
        return FloatFrameLayout(sourceFrames, targetFrameBytes, sourceFrames * targetFrameBytes)
    }

    private fun convertFloatSamples(
        buffer: ByteBuffer,
        size: Int,
        sourceFrames: Int,
        channelCount: Int,
        format: PreparedUsbInputPcmFormat,
        scratch: ByteBuffer,
    ): FloatConversionSummary? {
        val duplicate = buffer.duplicate()
        duplicate.limit(duplicate.position() + size)
        duplicate.order(ByteOrder.LITTLE_ENDIAN)
        scratch.clear()
        scratch.order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0f
        var firstSample: Float? = null
        repeat(sourceFrames) {
            repeat(channelCount) {
                val scaled = usbExclusiveFloatSampleForNativePipeline(duplicate.float)
                if (firstSample == null) firstSample = scaled
                peak = max(peak, abs(scaled))
                if (!putConvertedFloat(scratch, scaled, format.encoding)) return null
            }
        }
        scratch.flip()
        return FloatConversionSummary(peak, firstSample ?: 0f)
    }

    private fun putConvertedFloat(output: ByteBuffer, sample: Float, encoding: Int): Boolean = when (encoding) {
        C.ENCODING_PCM_16BIT -> {
            output.putShort((sample * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            true
        }
        C.ENCODING_PCM_24BIT -> {
            val value = (sample * 8_388_607f).toInt()
            output.put((value and 0xFF).toByte())
            output.put(((value shr 8) and 0xFF).toByte())
            output.put(((value shr 16) and 0xFF).toByte())
            true
        }
        C.ENCODING_PCM_32BIT -> {
            output.putInt((sample * Int.MAX_VALUE.toFloat()).toInt())
            true
        }
        else -> false
    }

    private fun logFloatConversionIfNeeded(
        format: PreparedUsbInputPcmFormat,
        summary: FloatConversionSummary,
        nativeVolume: Float,
    ) {
        if (softwareFloatConversionLogged) return
        softwareFloatConversionLogged = true
        NPLogger.i("NERI-UsbExclusive", "software float usb conversion armed: preparedEncoding=" +
            "${format.encoding} preparedBytes=${format.bytesPerSample} output=${port.outputFormat()} " +
            "inputPeak=${summary.peak} firstSample=${summary.firstSample} nativeVolume=$nativeVolume")
    }

    private fun writeCopied(buffer: ByteBuffer, size: Int, volume: Float, handle: Long): Int {
        val scratch = scratchForBytes(size) ?: return 0
        val duplicate = buffer.duplicate()
        duplicate.limit(duplicate.position() + size)
        scratch.clear()
        scratch.put(duplicate)
        scratch.flip()
        return port.write(handle, scratch, 0, size, volume)
    }

    private fun scratchForBytes(requiredBytes: Int): ByteBuffer? =
        directScratch?.takeIf { it.capacity() >= requiredBytes }

    fun writeSize(remaining: Int, directBuffer: Boolean, state: UsbExclusiveNativeWriteSnapshot): Int {
        val cachedMetrics = currentWritePlanningMetrics(state.handle)
        var size = planWriteSize(remaining, cachedMetrics, state)
        if (size <= 0 && cachedMetrics.hasPcmQueue && cachedMetrics.hasHealthyTransport) {
            size = refreshedWriteSize(remaining, state)
        }
        if (!directBuffer) size = size.coerceAtMost(directScratch?.capacity() ?: 0)
        return alignToInputFrame(size, state.frameBytes)
    }

    private fun refreshedWriteSize(remaining: Int, state: UsbExclusiveNativeWriteSnapshot): Int {
        val nowMs = port.elapsedRealtimeMs()
        if (nowMs - lastNativeBackpressureRefreshAtMs < BACKPRESSURE_REFRESH_INTERVAL_MS) return 0
        port.refreshRuntime(state.handle)
        lastNativeBackpressureRefreshAtMs = nowMs
        return planWriteSize(remaining, currentWritePlanningMetrics(state.handle), state)
    }

    private fun currentWritePlanningMetrics(handle: Long): UsbExclusiveRuntimeMetrics {
        val metrics = port.runtimeReport(handle).usbRuntimeMetrics()
        val liveFreeBytes = port.freeBytes(handle) ?: return metrics
        return metrics.withLivePcmFreeBytes(liveFreeBytes)
    }

    private fun planWriteSize(
        remaining: Int,
        metrics: UsbExclusiveRuntimeMetrics,
        state: UsbExclusiveNativeWriteSnapshot,
    ): Int = UsbExclusivePcmWritePlanner.chooseWriteSize(
        remainingBytes = remaining,
        inputSampleRate = state.sampleRate,
        inputFrameBytes = state.frameBytes,
        nativeTransportStarted = state.transportStarted,
        playing = state.playing,
        prerollMs = state.prerollMs,
        metrics = metrics,
    )

    private fun alignToInputFrame(size: Int, frameBytes: Int): Int {
        if (size <= 0 || frameBytes <= 1) return size.coerceAtLeast(0)
        return size - size % frameBytes
    }

    fun refreshRuntimeAfterStalledWrite(handle: Long, nowMs: Long): String {
        val cachedReport = port.runtimeReport(handle)
        val cachedMetrics = cachedReport.usbRuntimeMetrics()
        val shouldRefresh = !cachedMetrics.isBenignBackpressure ||
            nowMs - lastNativeBackpressureRefreshAtMs >= BACKPRESSURE_REFRESH_INTERVAL_MS
        if (!shouldRefresh) return cachedReport
        port.refreshRuntime(handle)
        lastNativeBackpressureRefreshAtMs = nowMs
        return port.runtimeReport(handle)
    }

    fun resetBackpressureRefresh() {
        lastNativeBackpressureRefreshAtMs = 0L
    }

    fun resetBackpressureObservation() {
        nativeBackpressureStartedAtMs = 0L
        nativeBackpressureCompletedTransfersBaseline = -1L
        resetBackpressureRefresh()
    }

    fun observeBenignBackpressure(
        nowMs: Long,
        pendingBytes: Int,
        attemptedBytes: Int,
        runtimeReport: String,
        state: UsbExclusiveNativeWriteSnapshot,
    ): UsbExclusiveBackpressureObservation {
        val completedTransfers = runtimeReport.valueAfter("completedTransfers")?.toLongOrNull() ?: -1L
        val madeProgress = updateBackpressureBaseline(nowMs, completedTransfers)
        val heldMs = nowMs - nativeBackpressureStartedAtMs
        val shouldRecover = shouldRecoverFromSustainedBackpressure(runtimeReport, heldMs, completedTransfers, state)
        if (!shouldRecover) {
            logBenignBackpressure(nowMs, pendingBytes, attemptedBytes, heldMs, runtimeReport, state)
            parkForBackpressure(runtimeReport, attemptedBytes == 0, state.sampleRate, state.frameBytes)
        }
        return UsbExclusiveBackpressureObservation(heldMs, completedTransfers, madeProgress, shouldRecover)
    }

    private fun updateBackpressureBaseline(nowMs: Long, completedTransfers: Long): Boolean {
        if (nativeBackpressureStartedAtMs == 0L) {
            nativeBackpressureStartedAtMs = nowMs
            nativeBackpressureCompletedTransfersBaseline = completedTransfers
            return false
        }
        if (completedTransfers < 0L || nativeBackpressureCompletedTransfersBaseline < 0L ||
            completedTransfers <= nativeBackpressureCompletedTransfersBaseline) return false
        nativeBackpressureStartedAtMs = nowMs
        nativeBackpressureCompletedTransfersBaseline = completedTransfers
        return true
    }

    private fun shouldRecoverFromSustainedBackpressure(
        runtimeReport: String,
        heldMs: Long,
        completedTransfers: Long,
        state: UsbExclusiveNativeWriteSnapshot,
    ): Boolean {
        if (!activeNativePlayer(state)) return false
        if (heldMs < BACKPRESSURE_STALL_RECOVERY_MS) return false
        if (!healthyPlayerTransfer(runtimeReport)) return false
        return completedTransfers >= 0L &&
            nativeBackpressureCompletedTransfersBaseline >= 0L &&
            completedTransfers <= nativeBackpressureCompletedTransfersBaseline
    }

    private fun activeNativePlayer(state: UsbExclusiveNativeWriteSnapshot): Boolean =
        state.playing && state.usingNative && state.handle != 0L

    private fun healthyPlayerTransfer(runtimeReport: String): Boolean {
        if (!runtimeReport.contains("source=player_pcm")) return false
        if (runtimeReport.booleanField("running") != true) return false
        if (runtimeReport.booleanField("transportFailed") == true) return false
        if (runtimeReport.valueAfter("inFlight")?.toIntOrNull() == 0) return false
        return true
    }

    private fun logBenignBackpressure(
        nowMs: Long,
        pendingBytes: Int,
        attemptedBytes: Int,
        heldMs: Long,
        runtimeReport: String,
        state: UsbExclusiveNativeWriteSnapshot,
    ) {
        if (nowMs - lastNativeBackpressureLogAtMs < BACKPRESSURE_LOG_INTERVAL_MS) return
        lastNativeBackpressureLogAtMs = nowMs
        NPLogger.i("NERI-UsbExclusive", "native PCM queue applying backpressure: pending=$pendingBytes " +
            "requested=$attemptedBytes heldMs=$heldMs playing=${state.playing} " +
            "transportStarted=${state.transportStarted} hasQueued=${state.hasQueuedPcm} runtime=$runtimeReport")
    }

    fun parkForBackpressure(runtimeReport: String, forceYield: Boolean, sampleRate: Int, frameBytes: Int) {
        if (!port.mayParkCurrentThread()) return
        val metrics = runtimeReport.usbRuntimeMetrics()
        val freeBytes = metrics.pcmFreeBytes ?: return
        if (!shouldParkForBackpressure(freeBytes, forceYield, sampleRate, frameBytes)) return
        val backpressureUs = metrics.pcmBackpressureCurrentMs?.coerceAtLeast(0L)?.times(1_000L) ?: 0L
        val oneFrameUs = 1_000_000L / sampleRate.coerceAtLeast(1)
        val parkUs = max(oneFrameUs, backpressureUs / 8L).coerceIn(500L, BACKPRESSURE_PARK_MAX_US)
        port.parkNanos(parkUs * 1_000L)
    }

    private fun shouldParkForBackpressure(
        freeBytes: Long,
        forceYield: Boolean,
        sampleRate: Int,
        frameBytes: Int,
    ): Boolean = (freeBytes <= 0L || forceYield) && sampleRate > 0 && frameBytes > 0

    fun ensureUrgentAudioThreadPriority() {
        if (audioThreadPriorityConfigured.get() == true) return
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            .onSuccess { NPLogger.d("NERI-UsbExclusive", "USB writer thread priority configured tid=${Process.myTid()}") }
            .onFailure { NPLogger.w("NERI-UsbExclusive", "USB writer thread priority setup failed", it) }
        audioThreadPriorityConfigured.set(true)
    }

    private data class FloatConversionSummary(val peak: Float, val firstSample: Float)

    private data class FloatFrameLayout(
        val sourceFrames: Int,
        val targetFrameBytes: Int,
        val convertedSize: Int,
    )
}
