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
