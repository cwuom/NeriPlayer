package moe.ouom.neriplayer.core.player.usb.sink

import moe.ouom.neriplayer.core.player.usb.transport.outputFrameBytes

import kotlin.math.min
import kotlin.math.max
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics

internal object UsbExclusivePcmWritePlanner {
    private const val DEFAULT_MAX_WRITE_CHUNK_BYTES = 12 * 1024
    private const val HIGH_RES_MAX_WRITE_CHUNK_BYTES = 64 * 1024
    private const val RENDERER_CALLBACK_COVERAGE_MS = 20L
    private const val TRANSFERS_PER_WRITE = 4L
    private const val RECOVERY_TRANSFERS_PER_WRITE = 10L
    private const val RUNNING_TARGET_QUEUE_MIN_MS = 120L
    private const val RUNNING_TARGET_QUEUE_MAX_MS = USB_EXCLUSIVE_SCHEDULING_QUEUE_CEILING_MS
    private const val RUNNING_TARGET_QUEUE_CAPACITY_DIVISOR = 2L
    private const val RUNNING_LOW_WATERMARK_QUEUE_MS = 40L
    private const val RUNNING_TARGET_TRANSFERS = 6L

    fun chooseWriteSize(
        remainingBytes: Int,
        inputSampleRate: Int,
        inputFrameBytes: Int,
        nativeTransportStarted: Boolean,
        playing: Boolean,
        prerollMs: Long,
        metrics: UsbExclusiveRuntimeMetrics,
        runningQueueTargetMs: Long? = null
    ): Int {
        if (remainingBytes <= 0) return 0

        val frameBytes = inputFrameBytes.takeIf { it > 0 } ?: return remainingBytes
        var limit = alignDown(remainingBytes, frameBytes)
        if (limit <= 0) return 0

        if (!nativeTransportStarted && playing && inputSampleRate > 0) {
            val prerollBytes = prerollBytes(
                inputSampleRate = inputSampleRate,
                inputFrameBytes = frameBytes,
                prerollMs = prerollMs
            )
            limit = min(limit, prerollBytes)
        }

        limit = min(
            limit,
            writeChunkLimit(
                metrics = metrics,
                frameBytes = frameBytes,
                inputSampleRate = inputSampleRate,
                nativeTransportStarted = nativeTransportStarted
            )
        )
        limit = min(
            limit,
            availablePcmInputBytes(
                metrics = metrics,
                inputSampleRate = inputSampleRate,
                inputFrameBytes = frameBytes,
                nativeTransportStarted = nativeTransportStarted,
                runningQueueTargetMs = runningQueueTargetMs
            )
        )
        return alignDown(limit, frameBytes)
    }

    private fun prerollBytes(
        inputSampleRate: Int,
        inputFrameBytes: Int,
        prerollMs: Long
    ): Int {
        val frames = (inputSampleRate * prerollMs / 1_000L).coerceAtLeast(1L)
        val bytes = frames * inputFrameBytes
        return bytes.coerceIn(inputFrameBytes.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun writeChunkLimit(
        metrics: UsbExclusiveRuntimeMetrics,
        frameBytes: Int,
        inputSampleRate: Int,
        nativeTransportStarted: Boolean
    ): Int {
        val transferBytes = metrics.effectiveTransferBytes()
        val recoveryMode = nativeTransportStarted &&
            runningQueueNeedsRecovery(
                metrics = metrics,
                inputSampleRate = inputSampleRate,
                frameBytes = frameBytes
            )
        val transfersPerWrite = if (recoveryMode) {
            RECOVERY_TRANSFERS_PER_WRITE
        } else {
            TRANSFERS_PER_WRITE
        }
        val rawLimit = transferBytes
            ?.times(transfersPerWrite)
            ?: DEFAULT_MAX_WRITE_CHUNK_BYTES.toLong()
        val boundedLimit = max(rawLimit, rendererCoverageBytes(inputSampleRate, frameBytes))
            .coerceAtMost(HIGH_RES_MAX_WRITE_CHUNK_BYTES.toLong())
        return alignDown(boundedLimit.coerceAtLeast(frameBytes.toLong()).toInt(), frameBytes)
    }

    private fun runningQueueNeedsRecovery(
        metrics: UsbExclusiveRuntimeMetrics,
        inputSampleRate: Int,
        frameBytes: Int
    ): Boolean {
        val hadZeroFill = (metrics.playerZeroFillBytes ?: 0L) > 0L
        if (!hadZeroFill) return false
        val levelBytes = metrics.pcmLevelBytes ?: return false
        val outputSampleRate = metrics.outputSampleRateOr(inputSampleRate)
        if (outputSampleRate <= 0) return false
        val outputFrameBytes = metrics.outputFrameBytes ?: frameBytes
        val lowWatermarkBytes =
            outputSampleRate.toLong() * outputFrameBytes * RUNNING_LOW_WATERMARK_QUEUE_MS / 1_000L
        return levelBytes <= lowWatermarkBytes
    }

    private fun availablePcmInputBytes(
        metrics: UsbExclusiveRuntimeMetrics,
        inputSampleRate: Int,
        inputFrameBytes: Int,
        nativeTransportStarted: Boolean,
        runningQueueTargetMs: Long?
    ): Int {
        val freeOutputBytes = explicitFreeBytes(metrics) ?: return Int.MAX_VALUE
        if (freeOutputBytes <= 0L) {
            return 0
        }

        val outputFrameBytes = metrics.outputFrameBytes ?: inputFrameBytes
        if (outputFrameBytes <= 0) return Int.MAX_VALUE

        val usableOutputBytes = runningQueueHeadroomBytes(
            metrics = metrics,
            freeOutputBytes = freeOutputBytes,
            outputFrameBytes = outputFrameBytes,
            inputSampleRate = inputSampleRate,
            nativeTransportStarted = nativeTransportStarted,
            runningQueueTargetMs = runningQueueTargetMs
        )
        val freeOutputFrames = usableOutputBytes / outputFrameBytes
        if (freeOutputFrames <= 0L) return 0

        val conservativeFrames = conservativeInputFrames(
            freeOutputFrames = freeOutputFrames,
            inputSampleRate = inputSampleRate,
            outputSampleRate = metrics.outputSampleRateOr(inputSampleRate)
        )
        val maxFrames = Int.MAX_VALUE / inputFrameBytes
        val boundedFrames = conservativeFrames.coerceIn(0L, maxFrames.toLong())
        return (boundedFrames * inputFrameBytes).toInt()
    }

    private fun conservativeInputFrames(
        freeOutputFrames: Long,
        inputSampleRate: Int,
        outputSampleRate: Int
    ): Long {
        if (inputSampleRate <= 0 || outputSampleRate <= 0) return freeOutputFrames
        val inputFrames = freeOutputFrames * inputSampleRate / outputSampleRate
        return if (inputSampleRate != outputSampleRate && inputFrames > 2L) inputFrames - 2L else inputFrames
    }

    private fun runningQueueHeadroomBytes(
        metrics: UsbExclusiveRuntimeMetrics,
        freeOutputBytes: Long,
        outputFrameBytes: Int,
        inputSampleRate: Int,
        nativeTransportStarted: Boolean,
        runningQueueTargetMs: Long?
    ): Long {
        if (!nativeTransportStarted) return freeOutputBytes
        val capacity = metrics.pcmCapacityBytes?.takeIf { it > 0L } ?: return freeOutputBytes
        val level = metrics.pcmLevelBytes ?: return freeOutputBytes
        val target = runningQueueTargetBytes(
            metrics = metrics,
            capacity = capacity,
            outputFrameBytes = outputFrameBytes,
            inputSampleRate = inputSampleRate,
            requestedQueueMs = runningQueueTargetMs
        )
        return min(freeOutputBytes, target - level).coerceAtLeast(0L)
    }

    private fun runningQueueTargetBytes(
        metrics: UsbExclusiveRuntimeMetrics,
        capacity: Long,
        outputFrameBytes: Int,
        inputSampleRate: Int,
        requestedQueueMs: Long?
    ): Long {
        val outputSampleRate = metrics.outputSampleRateOr(inputSampleRate)
        val bytesPerSecond = outputSampleRate.toLong() * outputFrameBytes
        // 前后台水位由生命周期给出；没有时退回按环形缓冲一半估算
        val targetQueueMs = when {
            requestedQueueMs != null -> requestedQueueMs
            bytesPerSecond > 0L -> capacity * 1_000L / bytesPerSecond / RUNNING_TARGET_QUEUE_CAPACITY_DIVISOR
            else -> RUNNING_TARGET_QUEUE_MIN_MS
        }.coerceIn(RUNNING_TARGET_QUEUE_MIN_MS, RUNNING_TARGET_QUEUE_MAX_MS)
        val timedBytes = if (outputSampleRate > 0) {
            outputSampleRate.toLong() * outputFrameBytes * targetQueueMs / 1_000L
        } else {
            0L
        }
        val transferBytes = metrics.effectiveTransferBytes() ?: 0L
        val transferFloor = transferBytes * RUNNING_TARGET_TRANSFERS
        val boundedCapacityBytes = capacity - capacity / 4L
        val target = max(max(timedBytes, transferFloor), outputFrameBytes.toLong())
        return target
            .coerceAtMost(boundedCapacityBytes)
            .coerceAtMost(capacity - capacity % outputFrameBytes)
    }

    private fun explicitFreeBytes(metrics: UsbExclusiveRuntimeMetrics): Long? {
        metrics.pcmFreeBytes?.let { return it }
        val capacity = metrics.pcmCapacityBytes ?: return null
        val level = metrics.pcmLevelBytes ?: return null
        if (capacity <= 0L) return null
        return (capacity - level).coerceAtLeast(0L)
    }

    private fun rendererCoverageBytes(inputSampleRate: Int, frameBytes: Int): Long {
        if (inputSampleRate <= 0) return 0L
        return inputSampleRate.toLong() * frameBytes * RENDERER_CALLBACK_COVERAGE_MS / 1_000L
    }

    private fun UsbExclusiveRuntimeMetrics.effectiveTransferBytes(): Long? =
        transferBytes?.takeIf { it > 0L } ?: lastTransferBytes?.takeIf { it > 0L }

    private fun UsbExclusiveRuntimeMetrics.outputSampleRateOr(inputSampleRate: Int): Int =
        sampleRate?.takeIf { it > 0 } ?: inputSampleRate

    private fun alignDown(value: Int, frameBytes: Int): Int {
        if (frameBytes <= 1) return value
        return value - value % frameBytes
    }
}
