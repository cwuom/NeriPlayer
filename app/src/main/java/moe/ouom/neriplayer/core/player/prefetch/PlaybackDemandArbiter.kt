package moe.ouom.neriplayer.core.player.prefetch

import java.util.concurrent.ConcurrentHashMap

internal class PlaybackDemandArbiter {
    private val activePlaybackKeys = ConcurrentHashMap.newKeySet<String>()

    fun markPlaybackDemand(cacheKey: String) {
        require(cacheKey.isNotBlank()) { "cacheKey must not be blank" }
        activePlaybackKeys += cacheKey
    }

    fun shouldYieldPrefetch(cacheKey: String): Boolean {
        if (cacheKey.isBlank()) return false
        return cacheKey in activePlaybackKeys
    }

    fun clearPlaybackDemand(cacheKey: String) {
        if (cacheKey.isBlank()) return
        activePlaybackKeys.remove(cacheKey)
    }
}
