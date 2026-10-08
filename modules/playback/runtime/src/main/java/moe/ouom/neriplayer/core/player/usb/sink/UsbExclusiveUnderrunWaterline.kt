package moe.ouom.neriplayer.core.player.usb.sink

import androidx.media3.common.C

/**
 * 前台为了让音效调节尽快生效只保留约 125 ms 的 PCM，高负载下播放线程卡住更久就会补零。
 * 连续写入期间观察到补零就把生产水位翻倍（最多 8 倍），稳定一段时间后逐级回落，
 * 只在真的欠载时才用更大的延迟换稳定
 */
internal class UsbExclusiveUnderrunWaterline(
    private val maxBoostShift: Int = 3,
    private val decayIntervalMs: Long = 30_000L,
    private val continuityWindowMs: Long = 1_000L,
) {
    private var lastZeroFillBytes: Long? = null
    private var lastObservedAtMs = 0L
    private var lastChangeAtMs = 0L

    var boostShift = 0
        private set

    fun targetMs(baseTargetMs: Long?, zeroFillBytes: Long?, nowMs: Long): Long? {
        observe(zeroFillBytes, nowMs)
        return baseTargetMs?.let { it shl boostShift }
    }

    private fun observe(zeroFillBytes: Long?, nowMs: Long) {
        val previous = lastZeroFillBytes
        val continuous = previous != null && nowMs - lastObservedAtMs <= continuityWindowMs
        lastZeroFillBytes = zeroFillBytes
        lastObservedAtMs = nowMs
        if (continuous && zeroFillBytes != null && zeroFillBytes > previous!!) {
            boostShift = (boostShift + 1).coerceAtMost(maxBoostShift)
            lastChangeAtMs = nowMs
        } else if (boostShift > 0 && nowMs - lastChangeAtMs >= decayIntervalMs) {
            boostShift -= 1
            lastChangeAtMs = nowMs
        }
    }
}

/**
 * 动态调度下渲染器会按上报缓冲的一半休眠；这里只报水位的一半，
 * 播放线程大约每过四分之一水位醒一次，留四分之三的余量应对高负载
 */
internal fun usbExclusiveSchedulingBufferUs(queueTargetMs: Long): Long =
    if (queueTargetMs > 0L) queueTargetMs * 1_000L / 2L else C.TIME_UNSET
