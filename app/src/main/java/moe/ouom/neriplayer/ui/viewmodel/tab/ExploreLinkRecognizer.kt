package moe.ouom.neriplayer.ui.viewmodel.tab

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import moe.ouom.neriplayer.util.network.awaitResponse
import okhttp3.OkHttpClient
import okhttp3.Request

internal sealed class ExploreLinkTarget {
    data class NeteaseSong(val id: Long) : ExploreLinkTarget()
    data class NeteasePlaylist(val id: Long) : ExploreLinkTarget()
    data class NeteaseArtist(val id: Long) : ExploreLinkTarget()
    data class NeteaseShortLink(val url: String) : ExploreLinkTarget()
    data class BiliVideo(
        val avid: Long? = null,
        val bvid: String? = null,
        val page: Int? = null,
        val cid: Long? = null,
        val seasonId: Long? = null,
        val isCollectionShare: Boolean = false
    ) : ExploreLinkTarget()
    data class BiliFavoriteFolder(val mediaId: Long) : ExploreLinkTarget()
    data class BiliFavoriteFolderByOwner(
        val ownerMid: Long,
        val folderId: Long
    ) : ExploreLinkTarget()
    data class BiliCollection(val ownerMid: Long, val seasonId: Long) : ExploreLinkTarget()
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
        host == "163cn.tv" -> ExploreLinkTarget.NeteaseShortLink(normalized)
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
    val params = queryParameters(uri.rawQuery)
    val page = params["p"]?.toIntOrNull()?.takeIf { it > 0 }
    val cid = params["cid"]?.toLongOrNull()?.takeIf { it > 0L }
    val seasonId = params["season_id"]?.toLongOrNull()?.takeIf { it > 0L }
    val isCollectionShare = params["share_from"].equals("season", ignoreCase = true) ||
        seasonId != null
    val bvid = BILI_BVID_REGEX.find(raw)?.value
    if (!bvid.isNullOrBlank()) {
        return ExploreLinkTarget.BiliVideo(
            bvid = bvid,
            page = page,
            cid = cid,
            seasonId = seasonId,
            isCollectionShare = isCollectionShare
        )
    }

    val aid = params["aid"]?.toLongOrNull()
        ?: BILI_AVID_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (aid != null && aid > 0L) {
        return ExploreLinkTarget.BiliVideo(
            avid = aid,
            page = page,
            cid = cid,
            seasonId = seasonId,
            isCollectionShare = isCollectionShare
        )
    }

    if (uri.host?.lowercase(Locale.US) == "b23.tv") {
        return ExploreLinkTarget.BiliShortLink(raw)
    }

    return recognizeBiliCollectionLink(uri)
        ?: recognizeBiliFavoriteFolderLink(uri)
        ?: recognizeBiliArtistLink(uri)
}

private fun recognizeBiliCollectionLink(uri: URI): ExploreLinkTarget? {
    if (uri.host?.lowercase(Locale.US) != "space.bilibili.com") return null
    val segments = uri.pathSegments()
    val ownerMid = segments.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    if (segments.getOrNull(1) != "lists") return null
    val seasonId = segments.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val listType = queryParameters(uri.rawQuery)["type"]?.lowercase(Locale.US)
    return if (listType == "series") {
        ExploreLinkTarget.Unsupported(platform = "Bilibili", type = "series playlist")
    } else {
        ExploreLinkTarget.BiliCollection(ownerMid = ownerMid, seasonId = seasonId)
    }
}

private fun recognizeBiliFavoriteFolderLink(uri: URI): ExploreLinkTarget? {
    val host = uri.host?.lowercase(Locale.US)
    val path = uri.path.orEmpty()
    BILI_MEDIA_LIST_REGEX.find(path)?.groupValues?.getOrNull(1)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?.let { return ExploreLinkTarget.BiliFavoriteFolder(it) }

    if (host != "space.bilibili.com") return null
    val segments = uri.pathSegments()
    val ownerMid = segments.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    if (segments.getOrNull(1) != "favlist") return null
    val folderId = queryParameters(uri.rawQuery)["fid"]
        ?.removePrefix("ml")
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?: return null
    return ExploreLinkTarget.BiliFavoriteFolderByOwner(
        ownerMid = ownerMid,
        folderId = folderId
    )
}

private fun recognizeBiliArtistLink(uri: URI): ExploreLinkTarget? {
    val host = uri.host?.lowercase(Locale.US)
    val segments = uri.pathSegments()
    val artistId = when {
        host == "space.bilibili.com" -> segments.firstOrNull()?.toLongOrNull()
        segments.firstOrNull() == "space" -> segments.getOrNull(1)?.toLongOrNull()
        else -> null
    }?.takeIf { it > 0L } ?: return null
    return ExploreLinkTarget.Unsupported(
        platform = "Bilibili",
        type = "artist/UP $artistId"
    )
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
        path == "live" || path.startsWith("live/") -> path.substringAfter("live/").takeIf { it.isNotBlank() }
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

internal suspend fun expandExploreRedirectUrl(
    url: String,
    client: OkHttpClient
): String {
    val request = Request.Builder()
        .url(url)
        .get()
        .header("User-Agent", "Mozilla/5.0")
        .build()
    return client.newCall(request).awaitResponse { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        response.request.url.toString()
    }
}

private fun URI.pathSegments(): List<String> {
    return path.orEmpty()
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }
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
private val BILI_MEDIA_LIST_REGEX = Regex(
    """/medialist/(?:detail|play)/(?:ml)?(\d+)""",
    RegexOption.IGNORE_CASE
)
private val HTTP_URL_REGEX = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
