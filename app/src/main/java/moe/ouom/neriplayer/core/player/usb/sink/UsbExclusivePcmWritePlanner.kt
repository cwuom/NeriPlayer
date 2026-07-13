package moe.ouom.neriplayer.core.player.usb.sink

import kotlin.math.min
import kotlin.math.max
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRuntimeMetrics

internal object UsbExclusivePcmWritePlanner {
    private const val DEFAULT_MAX_WRITE_CHUNK_BYTES = 12 * 1024
    private const val HARD_MAX_WRITE_CHUNK_BYTES = 16 * 1024
    private const val TRANSFERS_PER_WRITE = 4L
    private const val RUNNING_TARGET_QUEUE_MS = 120L
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

        limit = min(limit, writeChunkLimit(metrics, frameBytes))
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
        frameBytes: Int
    ): Int {
        val transferBytes = metrics.transferBytes
            ?.takeIf { it > 0L }
            ?: metrics.lastTransferBytes?.takeIf { it > 0L }
        val rawLimit = transferBytes
            ?.times(TRANSFERS_PER_WRITE)
            ?.coerceAtMost(HARD_MAX_WRITE_CHUNK_BYTES.toLong())
            ?.toInt()
            ?: DEFAULT_MAX_WRITE_CHUNK_BYTES
        return alignDown(rawLimit.coerceAtLeast(frameBytes), frameBytes)
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
        val timedBytes = if (outputSampleRate > 0) {
            outputSampleRate.toLong() * outputFrameBytes * RUNNING_TARGET_QUEUE_MS / 1_000L
        } else {
            0L
        }
        val transferBytes = metrics.transferBytes
            ?.takeIf { it > 0L }
            ?: metrics.lastTransferBytes?.takeIf { it > 0L }
            ?: 0L
        val transferFloor = transferBytes * RUNNING_TARGET_TRANSFERS
        val target = max(max(timedBytes, transferFloor), outputFrameBytes.toLong())
        return target.coerceAtMost(capacity - capacity % outputFrameBytes)
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
