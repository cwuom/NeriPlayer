package moe.ouom.neriplayer.listentogether.mapping

import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun trustedListenTogetherStreamUrl(
    channelId: String,
    streamUrl: String?
): String? {
    val candidate = streamUrl?.trim().orEmpty()
    if (candidate.isBlank()) return null
    val url = candidate.toHttpUrlOrNull() ?: return null
    val scheme = url.scheme.lowercase()
    if (scheme != "https" && scheme != "http") return null
    val host = url.host.lowercase()
    if (host.isBlank()) return null
    val trusted = when (channelId) {
        ListenTogetherChannels.NETEASE -> host == "music.126.net" || host.endsWith(".music.126.net")
        ListenTogetherChannels.BILIBILI -> {
            host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn") ||
                host == "hdslb.com" ||
                host.endsWith(".hdslb.com")
        }
        ListenTogetherChannels.YOUTUBE_MUSIC -> {
            host == "googlevideo.com" ||
                host.endsWith(".googlevideo.com") ||
                host == "youtube.com" ||
                host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" ||
                host.endsWith(".youtube-nocookie.com")
        }
        else -> false
    }
    if (!trusted) {
        NPLogger.w(
            "NERI-ListenTogether",
            "Blocked non-whitelisted streamUrl for listen together: channelId=$channelId, host=$host"
        )
        return null
    }
    return candidate
}
