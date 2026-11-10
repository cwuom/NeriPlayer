package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.cloud.NeteaseCloudPage
import org.json.JSONObject

/**
 * 解析云盘分页响应
 * @param raw 接口原始响应
 * @param offset 本次请求的偏移量，用于计算是否还有下一页
 */
fun parseNeteaseCloudPage(raw: String, offset: Int): NeteaseCloudPage {
    val root = JSONObject(raw)
    val code = root.optInt("code", -1)
    require(code == 200) { "NetEase cloud request failed with code $code" }

    val items = root.optJSONArray("data")
    val consumed = items?.length() ?: 0
    val songs = buildList {
        for (index in 0 until consumed) {
            val item = items?.optJSONObject(index) ?: continue
            parseNeteaseCloudSong(item)?.let(::add)
        }
    }
    val totalCount = root.optInt("count", 0).coerceAtLeast(0)
    val hasMore = if (root.has("hasMore")) {
        root.optBoolean("hasMore", false)
    } else {
        consumed > 0 && offset + consumed < totalCount
    }
    return NeteaseCloudPage(
        songs = songs,
        consumed = consumed,
        totalCount = totalCount,
        usedBytes = root.optLong("size", 0L).coerceAtLeast(0L),
        maxBytes = root.optLong("maxSize", 0L).coerceAtLeast(0L),
        hasMore = hasMore
    )
}

private fun parseNeteaseCloudSong(item: JSONObject): SongItem? {
    val simpleSong = item.optJSONObject("simpleSong")
    val songId = simpleSong?.optLong("id", 0L)?.takeIf { it > 0L }
        ?: item.optLong("songId", 0L).takeIf { it > 0L }
        ?: return null
    val name = sequenceOf(
        simpleSong?.optString("name", ""),
        item.optString("songName", ""),
        item.optString("fileName", "").substringBeforeLast('.')
    ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: return null

    val artists = parseNeteaseArtistsFromSongJson(simpleSong)
    val album = simpleSong?.optJSONObject("al") ?: simpleSong?.optJSONObject("album")
    val albumName = sequenceOf(
        album?.optString("name", ""),
        item.optString("album", "")
    ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        .orEmpty()
    val coverBase = sequenceOf(
        album?.optString("picUrl", ""),
        item.optString("cover", "")
    ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .map { it.replaceFirst(Regex("^http://"), "https://") }
        .firstOrNull()
    val coverUrl = coverBase?.let { withNeteaseImageSize(it, 300) }
    val originalCoverUrl = coverBase?.let { withNeteaseImageSize(it, 1024) }

    return SongItem(
        id = songId,
        name = name,
        artist = artists.joinToString(" / ") { it.name }
            .ifBlank { item.optString("artist", "").trim() },
        album = if (albumName.isBlank()) "Netease" else "Netease$albumName",
        albumId = album?.optLong("id", 0L) ?: 0L,
        durationMs = simpleSong?.optLong("dt", 0L)?.takeIf { it > 0L }
            ?: item.optLong("duration", 0L).takeIf { it > 0L }
            ?: 0L,
        coverUrl = coverUrl,
        originalCoverUrl = originalCoverUrl,
        channelId = "netease",
        audioId = songId.toString(),
        neteaseArtists = artists
    )
}

private fun withNeteaseImageSize(url: String, size: Int): String {
    if (url.contains("param=")) return url
    val separator = if (url.contains('?')) '&' else '?'
    return "$url${separator}param=${size}y$size"
}
