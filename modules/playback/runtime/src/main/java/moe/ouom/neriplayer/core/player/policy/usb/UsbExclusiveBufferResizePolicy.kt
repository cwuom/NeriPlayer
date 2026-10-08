package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.data.model.settings.usb.normalizeUsbExclusiveBackgroundBufferMs
import moe.ouom.neriplayer.data.model.settings.usb.normalizeUsbExclusiveForegroundBufferMs

/**
 * PCM 环形缓冲始终保持前后台中较大的容量，前后台切换只改变写入水位。
 * 播放中缩放缓冲会锁住填充路径并清掉余量，后台降频时就会断续，
 * 所以只在空闲且容量不足时恢复到预留大小，永远不在切换前后台时缩小
 */
internal fun shouldRestoreReservedUsbBuffer(
    streaming: Boolean,
    currentBufferMs: Int,
    reservedBufferMs: Int
): Boolean = !streaming && currentBufferMs < reservedBufferMs

/** 播放中保持的 PCM 余量：前台约一半前台缓冲以保证音效调节跟手，后台约一半后台缓冲以扛住降频 */
internal fun usbExclusiveRunningQueueTargetMs(lifecycleBufferMs: Int): Long =
    (lifecycleBufferMs / 2).toLong()

internal fun usbExclusiveTransferWindowDurationMs(
    bufferDurationMs: Int,
    appInForeground: Boolean
): Int {
    return if (appInForeground) {
        normalizeUsbExclusiveForegroundBufferMs(bufferDurationMs)
    } else {
        normalizeUsbExclusiveBackgroundBufferMs(bufferDurationMs)
    }
}
