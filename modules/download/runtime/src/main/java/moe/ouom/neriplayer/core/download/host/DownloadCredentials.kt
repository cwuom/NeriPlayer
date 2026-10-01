package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle

interface DownloadCredentials {
    fun biliCookies(): Map<String, String>
    fun youtubeAuth(): YouTubeAuthBundle
}
