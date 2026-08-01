package moe.ouom.neriplayer.ui.viewmodel.tab

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal sealed class ExploreLinkTarget {
    data class NeteaseSong(val id: Long) : ExploreLinkTarget()
    data class NeteasePlaylist(val id: Long) : ExploreLinkTarget()
    data class NeteaseArtist(val id: Long) : ExploreLinkTarget()
    data class BiliVideo(val avid: Long? = null, val bvid: String? = null) : ExploreLinkTarget()
    data class BiliShortLink(val url: String) : ExploreLinkTarget()
    data class YouTubeVideo(val videoId: String, val playlistId: String? = null) : ExploreLinkTarget()
    data class YouTubePlaylist(val playlistId: String) : ExploreLinkTarget()
    data class Unsupported(val platform: String, val type: String) : ExploreLinkTarget()
}

internal fun recognizeExploreLink(input: String): ExploreLinkTarget? {
    val normalized = extractExploreHttpUrl(input) ?: return null
    val uri = parseUri(normalized) ?: return null
    val host = uri.host?.lowercase(Locale.US) ?: return null

    return when {
        host.endsWith("music.163.com") -> recognizeNeteaseLink(uri)
        host.endsWith("bilibili.com") || host == "b23.tv" -> recognizeBiliLink(uri, normalized)
        host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> {
            recognizeYouTubeLink(uri)
        }
        else -> null
    }
}

private fun recognizeNeteaseLink(uri: URI): ExploreLinkTarget? {
    val path = uri.path.orEmpty()
    val fragmentPath = uri.rawFragment
        ?.substringBefore('?')
        .orEmpty()
    val targetPath = "$path/$fragmentPath".lowercase(Locale.US)
    val fragmentQuery = uri.rawFragment
        ?.takeIf { it.contains('?') }
        ?.substringAfter('?')
    val params = queryParameters(uri.rawQuery) + queryParameters(
        fragmentQuery
    )
    val id = params["id"]?.toLongOrNull() ?: return null

    return when {
        targetPath.contains("/song") -> ExploreLinkTarget.NeteaseSong(id)
        targetPath.contains("/playlist") -> ExploreLinkTarget.NeteasePlaylist(id)
        targetPath.contains("/artist") -> ExploreLinkTarget.NeteaseArtist(id)
        else -> null
    }
}

private fun recognizeBiliLink(uri: URI, raw: String): ExploreLinkTarget? {
    val bvid = BILI_BVID_REGEX.find(raw)?.value
    if (!bvid.isNullOrBlank()) {
        return ExploreLinkTarget.BiliVideo(bvid = bvid)
    }

    val params = queryParameters(uri.rawQuery)
    val aid = params["aid"]?.toLongOrNull()
        ?: BILI_AVID_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (aid != null && aid > 0L) {
        return ExploreLinkTarget.BiliVideo(avid = aid)
    }

    return if (uri.host?.lowercase(Locale.US) == "b23.tv") {
        ExploreLinkTarget.BiliShortLink(raw)
    } else {
        null
    }
}

private fun extractExploreHttpUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null
    return HTTP_URL_REGEX.find(trimmed)
        ?.value
        ?.trimEnd('。', '，', ',', '.', '）', ')', '】', ']', '}', '》', '>')
        ?.takeIf { it.isNotBlank() }
        ?: trimmed.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

private fun recognizeYouTubeLink(uri: URI): ExploreLinkTarget? {
    val host = uri.host?.lowercase(Locale.US)
    val path = uri.path.orEmpty().trim('/')
    val params = queryParameters(uri.rawQuery)
    val playlistId = params["list"]?.takeIf { it.isNotBlank() }
    val videoId = when {
        host == "youtu.be" -> path.takeIf { it.isNotBlank() }?.substringBefore('/')
        path == "embed" || path.startsWith("embed/") -> path.substringAfter("embed/").takeIf { it.isNotBlank() }
        path == "shorts" || path.startsWith("shorts/") -> path.substringAfter("shorts/").takeIf { it.isNotBlank() }
        else -> params["v"]?.takeIf { it.isNotBlank() }
    }

    if (!videoId.isNullOrBlank()) {
        return ExploreLinkTarget.YouTubeVideo(
            videoId = videoId,
            playlistId = playlistId
        )
    }
    if (!playlistId.isNullOrBlank()) {
        return ExploreLinkTarget.YouTubePlaylist(playlistId)
    }
    if (
        path.startsWith("channel/") ||
        path.startsWith("@") ||
        path.startsWith("c/") ||
        path.startsWith("browse/")
    ) {
        return ExploreLinkTarget.Unsupported(platform = "YouTube", type = "artist")
    }
    return null
}

private fun parseUri(raw: String): URI? {
    val candidate = if (raw.contains("://")) raw else "https://$raw"
    return runCatching { URI(candidate) }.getOrNull()
}

private fun queryParameters(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery
        .split('&')
        .mapNotNull { part ->
            val key = part.substringBefore('=').urlDecode().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val value = part.substringAfter('=', missingDelimiterValue = "").urlDecode()
            key to value
        }
        .toMap()
}

private fun String.urlDecode(): String {
    return runCatching {
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())
    }.getOrDefault(this)
}

private val BILI_BVID_REGEX = Regex("""BV[0-9A-Za-z]{10}""")
private val BILI_AVID_REGEX = Regex("""(?:/video/av|[?&]aid=)(\d+)""")
private val HTTP_URL_REGEX = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
