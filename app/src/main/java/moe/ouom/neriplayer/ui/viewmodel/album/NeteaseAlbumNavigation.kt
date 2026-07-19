package moe.ouom.neriplayer.ui.viewmodel.album

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import org.json.JSONObject

internal fun isNeteaseAlbumNavigationSource(song: SongItem): Boolean {
    return song.channelId.equals("netease", ignoreCase = true) ||
        song.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG) ||
        song.mediaUri?.contains("music.163.com", ignoreCase = true) == true
}

internal fun neteaseAlbumDisplayName(song: SongItem): String {
    return song.album.removePrefix(PlayerManager.NETEASE_SOURCE_TAG).trim()
}

internal fun buildKnownNeteaseAlbumSummary(song: SongItem): AlbumSummary? {
    if (!isNeteaseAlbumNavigationSource(song)) return null
    if (song.albumId <= 0L) return null

    return AlbumSummary(
        id = song.albumId,
        name = neteaseAlbumDisplayName(song),
        picUrl = song.coverUrl.orEmpty(),
        size = 0
    )
}

internal fun resolveNeteaseSongDetailId(song: SongItem): Long? {
    if (!isNeteaseAlbumNavigationSource(song)) return null
    return song.audioId?.toLongOrNull()?.takeIf { it > 0L }
        ?: song.id.takeIf { it > 0L }
        ?: song.matchedSongId?.toLongOrNull()?.takeIf { it > 0L }
}

internal suspend fun resolveNeteaseAlbum(song: SongItem): AlbumSummary? {
    buildKnownNeteaseAlbumSummary(song)?.let { return it }
    val songId = resolveNeteaseSongDetailId(song) ?: return null

    return withContext(Dispatchers.IO) {
        val raw = AppContainer.neteaseClient.getSongDetail(listOf(songId))
        parseNeteaseAlbumSummaryFromSongDetail(
            raw = raw,
            fallbackName = song.album,
            fallbackCoverUrl = song.coverUrl
        )
    }
}

internal fun parseNeteaseAlbumSummaryFromSongDetail(
    raw: String,
    fallbackName: String,
    fallbackCoverUrl: String?
): AlbumSummary? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null

    val song = root.optJSONArray("songs")?.optJSONObject(0) ?: return null
    val album = song.optJSONObject("al") ?: song.optJSONObject("album") ?: return null
    val albumId = album.optLong("id", 0L)
    if (albumId <= 0L) return null

    val name = album.optString("name", "").trim().ifBlank {
        fallbackName.removePrefix(PlayerManager.NETEASE_SOURCE_TAG).trim()
    }
    val coverUrl = album.optString("picUrl", "").trim()
        .ifBlank { fallbackCoverUrl.orEmpty() }
        .replaceFirst("http://", "https://")

    return AlbumSummary(
        id = albumId,
        name = name,
        picUrl = coverUrl,
        size = album.optInt("size", 0).coerceAtLeast(0)
    )
}
