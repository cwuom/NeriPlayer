package moe.ouom.neriplayer.core.player.download.runtime

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.platform.bilibili.api.stream.isBiliStreamHost
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

internal fun buildBiliAudioDownloadRequest(url: String, cookies: Map<String, String>): Request {
    val target = url.toHttpUrl()
    val cookieHeader = cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    return Request.Builder()
        .url(target)
        .header("User-Agent", AudioDownloadManager.BILI_UA)
        .header("Referer", AudioDownloadManager.BILI_REFERER)
        .apply {
            if (target.isHttps && isBiliStreamHost(target.host) && cookieHeader.isNotBlank()) {
                header("Cookie", cookieHeader)
            }
        }
        .build()
}
