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

    /**
     * 返回 true 表示应判定渲染器失联。
     * 若期间 `DlnaMediaServer` 仍在服务渲染器的音频流拉取或收到 GENA 事件，说明设备仍在局域网内正常工作，
     * 即使息屏 Wi-Fi 省电或单线程 SOAP 暂忙导致主动轮询超时，也不能误杀正在播放的流。
     */
    @Synchronized
    fun record(reachable: Boolean, nowMs: Long, lastPeerActivityAtMs: Long = -1L): Boolean {
        val peerActiveSinceFailure = firstFailureAtMs >= 0L && lastPeerActivityAtMs >= firstFailureAtMs
        val peerRecentlyActive = lastPeerActivityAtMs > 0L &&
            nowMs >= lastPeerActivityAtMs &&
            nowMs - lastPeerActivityAtMs <= RECENT_PEER_ACTIVITY_WINDOW_MS
        if (reachable || peerActiveSinceFailure || peerRecentlyActive) {
            reset()
            return false
        }
        if (consecutiveFailures == 0) firstFailureAtMs = nowMs
        consecutiveFailures += 1
        return consecutiveFailures >= minConsecutiveFailures &&
            nowMs - firstFailureAtMs >= minUnreachableMs
    }

    companion object {
        const val DEFAULT_MIN_CONSECUTIVE_FAILURES = 8
        const val DEFAULT_MIN_UNREACHABLE_MS = 90_000L
        const val RECENT_PEER_ACTIVITY_WINDOW_MS = 15_000L
    }
}
