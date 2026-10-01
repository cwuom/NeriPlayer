package moe.ouom.neriplayer.data.auth.youtube

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle

interface YouTubeRotationHost {
    fun isInitialized(): Boolean
    suspend fun isYouTubeEnabled(): Boolean
    fun readAuth(): YouTubeAuthBundle
    fun persistRotatedCookies(cookies: Map<String, String>)
}

object YouTubeRotationHosts {
    @Volatile
    var current: YouTubeRotationHost? = null
        private set

    fun bind(host: YouTubeRotationHost) {
        current = host
    }
}
