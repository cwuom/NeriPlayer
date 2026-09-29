package moe.ouom.neriplayer.api.youtube.auth

import moe.ouom.neriplayer.api.youtube.model.auth.YouTubeAuthAutoRefreshResult
import moe.ouom.neriplayer.api.youtube.model.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.api.youtube.model.auth.YouTubeAuthHealth

interface YouTubeAuthProvider {
    fun getAuthOnce(): YouTubeAuthBundle
    fun getAuthHealthOnce(): YouTubeAuthHealth
}

interface YouTubeAuthRefresher {
    suspend fun refreshIfNeeded(reason: String, force: Boolean = false): YouTubeAuthAutoRefreshResult
}
