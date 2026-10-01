package moe.ouom.neriplayer.data.ltw.mapping

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MAX_NETEASE_STREAM_URL_CANDIDATES = 3
private const val MAX_BILI_STREAM_URL_CANDIDATES = 2
private const val MAX_YOUTUBE_STREAM_URL_CANDIDATES = 1

private val STREAM_URL_SCHEMES = setOf("https", "http")
private val TRUSTED_STREAM_DOMAINS = mapOf(
    ListenTogetherChannels.NETEASE to listOf("music.126.net"),
    ListenTogetherChannels.BILIBILI to listOf("bilivideo.com", "bilivideo.cn", "hdslb.com", "mountaintoys.cn"),
    ListenTogetherChannels.YOUTUBE_MUSIC to listOf("googlevideo.com", "youtube.com", "youtube-nocookie.com")
)

fun trustedListenTogetherStreamUrl(
    channelId: String,
    streamUrl: String?
): String? {
    val candidate = streamUrl?.trim().orEmpty()
    val url = parsedStreamUrlOrNull(candidate) ?: return null
    val host = url.host.lowercase()
    if (!isTrustedStreamHost(channelId, host)) {
        NPLogger.w(
            "NERI-ListenTogether",
            "Blocked non-whitelisted streamUrl for listen together: channelId=$channelId, host=$host"
        )
        return null
    }
    return candidate
}

private fun parsedStreamUrlOrNull(candidate: String): HttpUrl? {
    if (candidate.isBlank()) return null
    val url = candidate.toHttpUrlOrNull() ?: return null
    if (url.scheme.lowercase() !in STREAM_URL_SCHEMES) return null
    if (url.host.isBlank()) return null
    return url
}

private fun isTrustedStreamHost(channelId: String, host: String): Boolean =
    TRUSTED_STREAM_DOMAINS[channelId].orEmpty().any { domain ->
        host == domain || host.endsWith(".$domain")
    }

fun trustedListenTogetherStreamUrls(
    channelId: String,
    streamUrls: List<String>?,
    legacyStreamUrl: String? = null,
    maxCount: Int = MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
): List<String> {
    if (maxCount <= 0) return emptyList()
    val platformMax = when (channelId) {
        ListenTogetherChannels.BILIBILI -> MAX_BILI_STREAM_URL_CANDIDATES
        ListenTogetherChannels.YOUTUBE_MUSIC -> MAX_YOUTUBE_STREAM_URL_CANDIDATES
        ListenTogetherChannels.NETEASE -> MAX_NETEASE_STREAM_URL_CANDIDATES
        else -> maxCount
    }
    return buildList {
        streamUrls.orEmpty().forEach { candidate ->
            trustedListenTogetherStreamUrl(channelId, candidate)?.let(::add)
        }
        trustedListenTogetherStreamUrl(channelId, legacyStreamUrl)?.let(::add)
    }.distinct().take(minOf(maxCount, platformMax))
}

const val MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES = 3
