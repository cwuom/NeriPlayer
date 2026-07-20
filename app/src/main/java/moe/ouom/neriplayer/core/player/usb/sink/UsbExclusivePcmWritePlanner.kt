package moe.ouom.neriplayer.core.player.usb.sink

import kotlin.math.min
import kotlin.math.max
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRuntimeMetrics

internal object UsbExclusivePcmWritePlanner {
    private const val DEFAULT_MAX_WRITE_CHUNK_BYTES = 12 * 1024
    private const val HIGH_RES_MAX_WRITE_CHUNK_BYTES = 64 * 1024
    private const val RENDERER_CALLBACK_COVERAGE_MS = 20L
    private const val TRANSFERS_PER_WRITE = 4L
    private const val RECOVERY_TRANSFERS_PER_WRITE = 10L
    private const val RUNNING_TARGET_QUEUE_MIN_MS = 120L
    private const val RUNNING_TARGET_QUEUE_MAX_MS = 1_000L
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
        metrics: UsbExclusiveRuntimeMetrics
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
                nativeTransportStarted = nativeTransportStarted
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
        val transferBytes = metrics.transferBytes
            ?.takeIf { it > 0L }
            ?: metrics.lastTransferBytes?.takeIf { it > 0L }
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
        val rendererCoverageBytes = inputSampleRate
            .takeIf { it > 0 }
            ?.toLong()
            ?.times(frameBytes)
            ?.times(RENDERER_CALLBACK_COVERAGE_MS)
            ?.div(1_000L)
            ?: 0L
        val boundedLimit = max(rawLimit, rendererCoverageBytes)
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
        val outputFrameBytes = metrics.outputFrameBytes ?: frameBytes
        val outputSampleRate = metrics.sampleRate?.takeIf { it > 0 } ?: inputSampleRate
        if (outputSampleRate <= 0 || outputFrameBytes <= 0) return false
        val lowWatermarkBytes =
            outputSampleRate.toLong() * outputFrameBytes * RUNNING_LOW_WATERMARK_QUEUE_MS / 1_000L
        return levelBytes <= lowWatermarkBytes
    }

    private fun availablePcmInputBytes(
        metrics: UsbExclusiveRuntimeMetrics,
        inputSampleRate: Int,
        inputFrameBytes: Int,
        nativeTransportStarted: Boolean
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
            nativeTransportStarted = nativeTransportStarted
        )
        val freeOutputFrames = usableOutputBytes / outputFrameBytes
        if (freeOutputFrames <= 0L) return 0

        val outputSampleRate = metrics.sampleRate?.takeIf { it > 0 } ?: inputSampleRate
        val inputFrames = if (inputSampleRate > 0 && outputSampleRate > 0) {
            freeOutputFrames * inputSampleRate / outputSampleRate
        } else {
            freeOutputFrames
        }
        val conservativeFrames = if (
            inputSampleRate > 0 &&
            outputSampleRate > 0 &&
            inputSampleRate != outputSampleRate &&
            inputFrames > 2L
        ) {
            inputFrames - 2L
        } else {
            inputFrames
        }
        val maxFrames = Int.MAX_VALUE / inputFrameBytes
        val boundedFrames = conservativeFrames.coerceIn(0L, maxFrames.toLong())
        return (boundedFrames * inputFrameBytes).toInt()
    }

    private fun runningQueueHeadroomBytes(
        metrics: UsbExclusiveRuntimeMetrics,
        freeOutputBytes: Long,
        outputFrameBytes: Int,
        inputSampleRate: Int,
        nativeTransportStarted: Boolean
    ): Long {
        if (!nativeTransportStarted) return freeOutputBytes
        val capacity = metrics.pcmCapacityBytes?.takeIf { it > 0L } ?: return freeOutputBytes
        val level = metrics.pcmLevelBytes ?: return freeOutputBytes
        val target = runningQueueTargetBytes(
            metrics = metrics,
            capacity = capacity,
            outputFrameBytes = outputFrameBytes,
            inputSampleRate = inputSampleRate
        )
        return min(freeOutputBytes, target - level).coerceAtLeast(0L)
    }

    private fun runningQueueTargetBytes(
        metrics: UsbExclusiveRuntimeMetrics,
        capacity: Long,
        outputFrameBytes: Int,
        inputSampleRate: Int
    ): Long {
        val outputSampleRate = metrics.sampleRate?.takeIf { it > 0 } ?: inputSampleRate
        val bytesPerSecond = outputSampleRate.toLong() * outputFrameBytes
        // A large background ring must retain a PCM reserve, not only a few USB transfers.
        val targetQueueMs = if (bytesPerSecond > 0L) {
            (capacity * 1_000L / bytesPerSecond / RUNNING_TARGET_QUEUE_CAPACITY_DIVISOR)
                .coerceIn(RUNNING_TARGET_QUEUE_MIN_MS, RUNNING_TARGET_QUEUE_MAX_MS)
        } else {
            RUNNING_TARGET_QUEUE_MIN_MS
        }
        val timedBytes = if (outputSampleRate > 0) {
            outputSampleRate.toLong() * outputFrameBytes * targetQueueMs / 1_000L
        } else {
            0L
        }
        val transferBytes = metrics.transferBytes
            ?.takeIf { it > 0L }
            ?: metrics.lastTransferBytes?.takeIf { it > 0L }
            ?: 0L
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

    private fun alignDown(value: Int, frameBytes: Int): Int {
        if (frameBytes <= 1) return value
        return value - value % frameBytes
    }
}
