package moe.ouom.neriplayer.api.youtube.auth

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthAutoRefreshResult
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthHealth

interface YouTubeAuthProvider {
    fun getAuthOnce(): YouTubeAuthBundle
    fun getAuthHealthOnce(): YouTubeAuthHealth
}

interface YouTubeAuthRefresher {
    suspend fun refreshIfNeeded(reason: String, force: Boolean = false): YouTubeAuthAutoRefreshResult
}
