package moe.ouom.neriplayer.core.player.dlna.session

/**
 * 判断渲染器是否已经失联。
 *
 * 渲染器断电或局域网断开时 SOAP 查询只会持续失败，没有任何事件通知；
 * 不做判断的话会话会一直保持"投送中"，并持有 WakeLock / WifiLock 和前台服务。
 * 同时要求连续失败次数和失败时长，避免 Wi-Fi 短暂抖动就断开投送。
 */
internal class DlnaRendererReachability(
    private val minConsecutiveFailures: Int = DEFAULT_MIN_CONSECUTIVE_FAILURES,
    private val minUnreachableMs: Long = DEFAULT_MIN_UNREACHABLE_MS
) {
    private var consecutiveFailures = 0
    private var firstFailureAtMs = -1L

    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        firstFailureAtMs = -1L
    }

    /** 返回 true 表示应判定渲染器失联 */
    @Synchronized
    fun record(reachable: Boolean, nowMs: Long): Boolean {
        if (reachable) {
            reset()
            return false
        }
        if (consecutiveFailures == 0) firstFailureAtMs = nowMs
        consecutiveFailures += 1
        return consecutiveFailures >= minConsecutiveFailures &&
            nowMs - firstFailureAtMs >= minUnreachableMs
    }

    companion object {
        const val DEFAULT_MIN_CONSECUTIVE_FAILURES = 3
        const val DEFAULT_MIN_UNREACHABLE_MS = 30_000L
    }
}
