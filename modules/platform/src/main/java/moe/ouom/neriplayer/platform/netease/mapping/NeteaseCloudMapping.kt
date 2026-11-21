package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.cloud.NeteaseCloudPage
import org.json.JSONArray
import org.json.JSONObject

/** 云盘请求被判定为登录态失效（例如 MUSIC_U 过期）。 */
class NeteaseCloudAuthRequiredException : IllegalStateException("NetEase cloud login required")

/**
 * 解析云盘分页响应
 * @param raw 接口原始响应
 * @param offset 本次请求的偏移量，用于计算是否还有下一页
 */
fun parseNeteaseCloudPage(raw: String, offset: Int): NeteaseCloudPage {
    val root = JSONObject(raw)
    val code = root.optInt("code", -1)
    if (code == 301) throw NeteaseCloudAuthRequiredException()
    require(code == 200) { "NetEase cloud request failed with code $code" }

    val items = root.optJSONArray("data")
    val consumed = items?.length() ?: 0
    val totalCount = root.optInt("count", 0).coerceAtLeast(0)
    return NeteaseCloudPage(
        songs = parseNeteaseCloudSongs(items),
        consumed = consumed,
        totalCount = totalCount,
        usedBytes = root.optLong("size", 0L).coerceAtLeast(0L),
        maxBytes = root.optLong("maxSize", 0L).coerceAtLeast(0L),
        hasMore = resolveNeteaseCloudHasMore(root, offset, consumed, totalCount)
    )
}

private fun parseNeteaseCloudSongs(items: JSONArray?): List<SongItem> {
    if (items == null) return emptyList()
    val songs = mutableListOf<SongItem>()
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        parseNeteaseCloudSong(item)?.let(songs::add)
    }
    return songs
}

private fun resolveNeteaseCloudHasMore(
    root: JSONObject,
    offset: Int,
    consumed: Int,
    totalCount: Int
): Boolean {
    if (root.has("hasMore")) return root.optBoolean("hasMore", false)
    return consumed > 0 && offset + consumed < totalCount
}

/** 云盘条目转可播放 [SongItem]；simpleSong 缺失或字段为 null 时回退到条目元数据。 */
private fun parseNeteaseCloudSong(item: JSONObject): SongItem? {
    val simpleSong = item.optJSONObject("simpleSong")
    val songId = parseNeteaseCloudSongId(simpleSong, item) ?: return null
    val name = parseNeteaseCloudSongName(simpleSong, item) ?: return null
    val album = parseNeteaseCloudAlbum(simpleSong)
    val artists = parseNeteaseArtistsFromSongJson(simpleSong)
    val coverBase = parseNeteaseCloudCover(album, item)
    return SongItem(
        id = songId,
        name = name,
        artist = parseNeteaseCloudArtist(artists, item),
        album = neteaseCloudAlbumLabel(parseNeteaseCloudAlbumName(album, item)),
        albumId = album?.optLong("id", 0L) ?: 0L,
        durationMs = parseNeteaseCloudDurationMs(simpleSong, item),
        coverUrl = coverBase?.let { withNeteaseImageSize(it, 300) },
        originalCoverUrl = coverBase?.let { withNeteaseImageSize(it, 1024) },
        channelId = "netease",
        audioId = songId.toString(),
        neteaseArtists = artists
    )
}

private fun parseNeteaseCloudSongId(simpleSong: JSONObject?, item: JSONObject): Long? {
    val simpleId = simpleSong?.optLong("id", 0L) ?: 0L
    if (simpleId > 0L) return simpleId
    return item.optLong("songId", 0L).takeIf { it > 0L }
}

private fun parseNeteaseCloudSongName(simpleSong: JSONObject?, item: JSONObject): String? {
    val simpleName = simpleSong?.stringOrNull("name")
    if (simpleName != null) return simpleName
    return item.stringOrNull("songName") ?: parseNeteaseCloudFileName(item)
}

private fun parseNeteaseCloudFileName(item: JSONObject): String? {
    val fileName = item.stringOrNull("fileName") ?: return null
    return fileName.substringBeforeLast('.').takeIf { it.isNotBlank() }
}

private fun parseNeteaseCloudAlbum(simpleSong: JSONObject?): JSONObject? =
    simpleSong?.optJSONObject("al") ?: simpleSong?.optJSONObject("album")

private fun parseNeteaseCloudAlbumName(album: JSONObject?, item: JSONObject): String =
    album?.stringOrNull("name") ?: item.stringOrNull("album") ?: ""

private fun parseNeteaseCloudArtist(
    artists: List<NeteaseArtistSummary>,
    item: JSONObject
): String = artists.joinToString(" / ") { it.name }
    .ifBlank { item.stringOrNull("artist").orEmpty() }

private fun parseNeteaseCloudDurationMs(simpleSong: JSONObject?, item: JSONObject): Long {
    val simpleDuration = simpleSong?.optLong("dt", 0L) ?: 0L
    if (simpleDuration > 0L) return simpleDuration
    return item.optLong("duration", 0L).takeIf { it > 0L } ?: 0L
}

private fun parseNeteaseCloudCover(album: JSONObject?, item: JSONObject): String? {
    val raw = album?.stringOrNull("picUrl") ?: item.stringOrNull("cover") ?: return null
    val https = raw.replaceFirst(Regex("^http://"), "https://")
    return https.takeIf { it.startsWith("https://") }
}

private fun neteaseCloudAlbumLabel(name: String): String =
    if (name.isBlank()) "Netease" else "Netease$name"

/** Android 的 optString 会把 JSON null 变成 "null"，这里只接受真正的字符串。 */
private fun JSONObject.stringOrNull(key: String): String? {
    if (isNull(key)) return null
    val value = opt(key) as? String ?: return null
    return value.trim().ifBlank { null }
}

/** 网易云图片 CDN 支持 ?param=WxH；已有尺寸参数时保持原样。 */
private fun withNeteaseImageSize(url: String, size: Int): String {
    if (url.contains("param=")) return url
    val separator = if (url.contains('?')) '&' else '?'
    return "$url${separator}param=${size}y$size"
}
