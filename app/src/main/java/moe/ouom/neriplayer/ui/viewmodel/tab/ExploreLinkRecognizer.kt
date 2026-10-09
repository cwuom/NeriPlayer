package moe.ouom.neriplayer.ui.viewmodel.tab

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import moe.ouom.neriplayer.network.http.awaitResponse
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
    val host = uri.lowercaseHost() ?: return null
    return recognizeLinkForHost(host, uri, normalized)
}

private fun recognizeLinkForHost(host: String, uri: URI, normalized: String): ExploreLinkTarget? {
    return when {
        host.endsWith("music.163.com") -> recognizeNeteaseLink(uri)
        host == "163cn.tv" -> ExploreLinkTarget.NeteaseShortLink(normalized)
        isBiliHost(host) -> recognizeBiliLink(uri, normalized)
        isYouTubeHost(host) -> recognizeYouTubeLink(uri)
        else -> null
    }
}

private fun isBiliHost(host: String): Boolean = host.endsWith("bilibili.com") || host == "b23.tv"

private fun isYouTubeHost(host: String): Boolean {
    return host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")
}

private fun recognizeNeteaseLink(uri: URI): ExploreLinkTarget? {
    val fragment = uri.rawFragment.orEmpty()
    val targetPath = "${uri.path.orEmpty()}/${fragment.substringBefore('?')}".lowercase(Locale.US)
    val fragmentQuery = fragment.takeIf { it.contains('?') }?.substringAfter('?')
    val params = queryParameters(uri.rawQuery) + queryParameters(fragmentQuery)
    val id = params["id"]?.toLongOrNull() ?: return null
    return neteaseTargetForPath(targetPath, id)
}

private fun neteaseTargetForPath(targetPath: String, id: Long): ExploreLinkTarget? {
    return when {
        targetPath.contains("/song") -> ExploreLinkTarget.NeteaseSong(id)
        targetPath.contains("/playlist") -> ExploreLinkTarget.NeteasePlaylist(id)
        targetPath.contains("/artist") -> ExploreLinkTarget.NeteaseArtist(id)
        else -> null
    }
}

private fun recognizeBiliLink(uri: URI, raw: String): ExploreLinkTarget? {
    val params = queryParameters(uri.rawQuery)
    val bvid = BILI_BVID_REGEX.find(raw)?.value
    if (bvid != null) {
        return biliVideoTarget(params, avid = null, bvid = bvid)
    }
    val aid = biliAid(params, raw)
    if (aid != null) {
        return biliVideoTarget(params, avid = aid, bvid = null)
    }
    if (uri.lowercaseHost() == "b23.tv") {
        return ExploreLinkTarget.BiliShortLink(raw)
    }
    return recognizeBiliCollectionLink(uri)
        ?: recognizeBiliFavoriteFolderLink(uri)
        ?: recognizeBiliArtistLink(uri)
}

private fun biliVideoTarget(
    params: Map<String, String>,
    avid: Long?,
    bvid: String?
): ExploreLinkTarget.BiliVideo {
    val seasonId = params["season_id"].positiveLong()
    return ExploreLinkTarget.BiliVideo(
        avid = avid,
        bvid = bvid,
        page = params["p"]?.toIntOrNull()?.takeIf { it > 0 },
        cid = params["cid"].positiveLong(),
        seasonId = seasonId,
        isCollectionShare = params["share_from"].equals("season", ignoreCase = true) || seasonId != null
    )
}

/** 查询参数里的 aid 即使无效也优先于路径里的 av 号 */
private fun biliAid(params: Map<String, String>, raw: String): Long? {
    return (params["aid"]?.toLongOrNull() ?: biliAidFromPath(raw))?.takeIf { it > 0L }
}

private fun biliAidFromPath(raw: String): Long? {
    return BILI_AVID_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
}

private fun recognizeBiliCollectionLink(uri: URI): ExploreLinkTarget? {
    val space = uri.biliSpaceSection("lists") ?: return null
    val seasonId = space.segments.getOrNull(2).positiveLong() ?: return null
    val listType = queryParameters(uri.rawQuery)["type"]?.lowercase(Locale.US)
    return if (listType == "series") {
        ExploreLinkTarget.Unsupported(platform = "Bilibili", type = "series playlist")
    } else {
        ExploreLinkTarget.BiliCollection(ownerMid = space.ownerMid, seasonId = seasonId)
    }
}

private fun recognizeBiliFavoriteFolderLink(uri: URI): ExploreLinkTarget? {
    biliMediaListId(uri)?.let { return ExploreLinkTarget.BiliFavoriteFolder(it) }
    val space = uri.biliSpaceSection("favlist") ?: return null
    val folderId = queryParameters(uri.rawQuery)["fid"]?.removePrefix("ml").positiveLong() ?: return null
    return ExploreLinkTarget.BiliFavoriteFolderByOwner(
        ownerMid = space.ownerMid,
        folderId = folderId
    )
}

private fun biliMediaListId(uri: URI): Long? {
    return BILI_MEDIA_LIST_REGEX.find(uri.path.orEmpty())?.groupValues?.getOrNull(1).positiveLong()
}

private class BiliSpaceSection(val ownerMid: Long, val segments: List<String>)

private fun URI.biliSpaceSection(section: String): BiliSpaceSection? {
    if (lowercaseHost() != "space.bilibili.com") return null
    val segments = pathSegments()
    val ownerMid = segments.getOrNull(0).positiveLong() ?: return null
    if (segments.getOrNull(1) != section) return null
    return BiliSpaceSection(ownerMid, segments)
}

private fun recognizeBiliArtistLink(uri: URI): ExploreLinkTarget? {
    val artistId = biliArtistIdSegment(uri).positiveLong() ?: return null
    return ExploreLinkTarget.Unsupported(
        platform = "Bilibili",
        type = "artist/UP $artistId"
    )
}

private fun biliArtistIdSegment(uri: URI): String? {
    val segments = uri.pathSegments()
    return when {
        uri.lowercaseHost() == "space.bilibili.com" -> segments.firstOrNull()
        segments.firstOrNull() == "space" -> segments.getOrNull(1)
        else -> null
    }
}

private fun extractExploreHttpUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null
    return embeddedHttpUrl(trimmed)
        ?: trimmed.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

private fun embeddedHttpUrl(text: String): String? {
    return HTTP_URL_REGEX.find(text)
        ?.value
        ?.trimEnd('。', '，', ',', '.', '）', ')', '】', ']', '}', '》', '>')
        ?.takeIf { it.isNotBlank() }
}

private fun recognizeYouTubeLink(uri: URI): ExploreLinkTarget? {
    val path = uri.path.orEmpty().trim('/')
    val params = queryParameters(uri.rawQuery)
    val playlistId = params.nonBlank("list")
    val videoId = youTubeVideoId(uri.lowercaseHost(), path, params)
    return when {
        !videoId.isNullOrBlank() -> ExploreLinkTarget.YouTubeVideo(videoId = videoId, playlistId = playlistId)
        playlistId != null -> ExploreLinkTarget.YouTubePlaylist(playlistId)
        isYouTubeArtistPath(path) -> ExploreLinkTarget.Unsupported(platform = "YouTube", type = "artist")
        else -> null
    }
}

/** 命中 embed/shorts/live 路径后不再回退到 v 参数, 即使路径里的 id 为空 */
private fun youTubeVideoId(host: String?, path: String, params: Map<String, String>): String? {
    if (host == "youtu.be") return youtuBeVideoId(path)
    val section = youTubeVideoPathSection(path) ?: return params.nonBlank("v")
    return path.substringAfter("$section/").takeIf { it.isNotBlank() }
}

private fun youtuBeVideoId(path: String): String? {
    return path.takeIf { it.isNotBlank() }?.substringBefore('/')
}

private fun youTubeVideoPathSection(path: String): String? {
    return YOUTUBE_VIDEO_PATH_SECTIONS.firstOrNull { section ->
        path == section || path.startsWith("$section/")
    }
}

private fun isYouTubeArtistPath(path: String): Boolean {
    return YOUTUBE_ARTIST_PATH_PREFIXES.any { prefix -> path.startsWith(prefix) }
}

private fun Map<String, String>.nonBlank(key: String): String? = this[key]?.takeIf { it.isNotBlank() }

private fun String?.positiveLong(): Long? = this?.toLongOrNull()?.takeIf { it > 0L }

private fun URI.lowercaseHost(): String? = host?.lowercase(Locale.US)

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
private val YOUTUBE_VIDEO_PATH_SECTIONS = listOf("embed", "shorts", "live")
private val YOUTUBE_ARTIST_PATH_PREFIXES = listOf("channel/", "@", "c/", "browse/")
