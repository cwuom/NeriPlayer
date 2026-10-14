package moe.ouom.neriplayer.core.player.policy.wake

import androidx.media3.common.C

private const val OFFLINE_CACHE_HOST = "offline.cache"

const val DEFAULT_PLAYBACK_WAKE_MODE = C.WAKE_MODE_NETWORK

fun resolvePlaybackWakeMode(url: String?): Int {
    val normalized = url?.trim().orEmpty()
    if (normalized.isBlank()) return C.WAKE_MODE_NONE

    val lower = normalized.lowercase()
    return when {
        lower.startsWith("http://$OFFLINE_CACHE_HOST/") -> C.WAKE_MODE_LOCAL
        lower.startsWith("https://") || lower.startsWith("http://") -> C.WAKE_MODE_NETWORK
        else -> C.WAKE_MODE_LOCAL
    }
}

/** Wi-Fi 锁只在实际下载时需要；缓冲达到上限后保留 CPU 唤醒，让网卡在两轮批量加载之间省电 */
fun resolveLoadingAwareWakeMode(mediaWakeMode: Int, loading: Boolean): Int =
    if (mediaWakeMode == C.WAKE_MODE_NETWORK && !loading) C.WAKE_MODE_LOCAL else mediaWakeMode
