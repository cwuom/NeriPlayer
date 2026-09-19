package moe.ouom.neriplayer.core.download.execution.notification

import android.os.SystemClock

internal data class DownloadNotificationRefreshDecision(
    val refreshNow: Boolean = false,
    val delayMs: Long? = null,
    val token: Long = 0L,
    val ignored: Boolean = false
)

/** 把高频进度事件合并成有界的通知刷新，避免触发系统通知限流 */
internal class DownloadNotificationRefreshGate(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime
) {
    private var lastRefreshElapsedMs: Long? = null
    private var pendingToken: Long? = null
    private var nextToken = 0L

    init {
        require(minIntervalMs > 0L)
    }

    @Synchronized
    fun request(immediate: Boolean = false): DownloadNotificationRefreshDecision {
        val now = nowElapsedMs()
        val last = lastRefreshElapsedMs
        if (immediate || last == null || now - last >= minIntervalMs) {
            lastRefreshElapsedMs = now
            pendingToken = null
            nextToken++
            return DownloadNotificationRefreshDecision(
                refreshNow = true,
                token = nextToken
            )
        }
        val token = pendingToken ?: run {
            nextToken++
            nextToken.also { pendingToken = it }
        }
        val delayMs = (minIntervalMs - (now - last)).coerceAtLeast(1L)
        return DownloadNotificationRefreshDecision(
            delayMs = delayMs,
            token = token
        )
    }

    @Synchronized
    fun onPendingTimer(token: Long): DownloadNotificationRefreshDecision {
        if (pendingToken != token) {
            return DownloadNotificationRefreshDecision(ignored = true, token = token)
        }
        pendingToken = null
        return request()
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 750L
    }
}
