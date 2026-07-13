package moe.ouom.neriplayer.core.player.policy.wake

import androidx.media3.common.C

private const val OFFLINE_CACHE_HOST = "offline.cache"

fun resolvePlaybackWakeMode(url: String?): Int {
    val normalized = url?.trim().orEmpty()
    if (normalized.isBlank()) return C.WAKE_MODE_NONE

    val lower = normalized.lowercase()
    return when {
        lower.startsWith("http://$OFFLINE_CACHE_HOST/") -> C.WAKE_MODE_LOCAL
        lower.startsWith("https://") || lower.startsWith("http://") -> C.WAKE_MODE_NETWORK
        lower.startsWith("file://") -> C.WAKE_MODE_LOCAL
        lower.startsWith("content://") -> C.WAKE_MODE_LOCAL
        lower.startsWith("android.resource://") -> C.WAKE_MODE_LOCAL
        lower.startsWith("/") -> C.WAKE_MODE_LOCAL
        else -> C.WAKE_MODE_LOCAL
    }
}
