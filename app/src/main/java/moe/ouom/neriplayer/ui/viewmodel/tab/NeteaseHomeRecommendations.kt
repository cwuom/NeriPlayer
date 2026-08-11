package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.artist.parseNeteaseArtistSummaries
import org.json.JSONArray
import org.json.JSONObject

internal const val NETEASE_PRIVATE_RADAR_PLAYLIST_ID = 3_136_952_023L
internal const val NETEASE_TOPLIST_SOARING_ID = 19_723_756L
internal const val NETEASE_TOPLIST_NEW_ID = 3_779_629L
internal const val NETEASE_TOPLIST_HOT_ID = 3_778_678L

internal class ApiCodeException(val code: Int) : IllegalStateException("api_code=$code")

enum class NeteaseHomeSongSource(
    val titleRes: Int,
    val requiresLogin: Boolean
) {
    TOP_SOARING(R.string.recommend_trending, requiresLogin = false),
    PERSONAL_RADAR(R.string.recommend_radar, requiresLogin = false),
    DAILY_RECOMMEND(R.string.home_netease_daily_songs, requiresLogin = true),
    PRIVATE_FM(R.string.home_netease_private_fm, requiresLogin = true),
    PERSONALIZED_NEW_SONGS(R.string.home_netease_new_songs, requiresLogin = false),
    TOP_HOT(R.string.home_netease_hot_rank, requiresLogin = false),
    TOP_NEW(R.string.home_netease_new_rank, requiresLogin = false)
}

enum class NeteaseHomePlaylistSource(
    val titleRes: Int,
    val requiresLogin: Boolean
) {
    PERSONALIZED(R.string.recommend_for_you, requiresLogin = false),
    DAILY_RESOURCE(R.string.home_netease_daily_playlists, requiresLogin = true),
    HIGH_QUALITY(R.string.home_netease_high_quality_playlists, requiresLogin = false),
    HOT_PLAYLISTS(R.string.home_netease_hot_playlists, requiresLogin = false),
    ACG_PLAYLISTS(R.string.home_netease_acg_playlists, requiresLogin = false)
}

internal data class NeteaseRadarPlaylistDefinition(
    val id: Long,
    val name: String
)

internal val NeteaseRadarPlaylistDefinitions = listOf(
    NeteaseRadarPlaylistDefinition(id = 5_320_167_908L, name = "时光雷达"),
    NeteaseRadarPlaylistDefinition(id = 5_362_359_247L, name = "宝藏雷达"),
    NeteaseRadarPlaylistDefinition(id = 5_300_458_264L, name = "新歌雷达"),
    NeteaseRadarPlaylistDefinition(id = 5_327_906_368L, name = "乐迷雷达"),
    NeteaseRadarPlaylistDefinition(id = 5_341_776_086L, name = "神秘雷达")
)

internal val NeteaseHomeTrendingSongSources = listOf(
    NeteaseHomeSongSource.TOP_SOARING,
    NeteaseHomeSongSource.PERSONALIZED_NEW_SONGS,
    NeteaseHomeSongSource.TOP_HOT,
    NeteaseHomeSongSource.TOP_NEW
)

internal val NeteaseHomeRadarSongSources = listOf(
    NeteaseHomeSongSource.PERSONAL_RADAR,
    NeteaseHomeSongSource.DAILY_RECOMMEND,
    NeteaseHomeSongSource.PRIVATE_FM
)

internal val NeteaseHomePlaylistSources = listOf(
    NeteaseHomePlaylistSource.PERSONALIZED,
    NeteaseHomePlaylistSource.DAILY_RESOURCE,
    NeteaseHomePlaylistSource.HIGH_QUALITY,
    NeteaseHomePlaylistSource.HOT_PLAYLISTS,
    NeteaseHomePlaylistSource.ACG_PLAYLISTS
)

internal fun availableNeteaseHomeSongSources(
    candidates: List<NeteaseHomeSongSource>,
    hasLogin: Boolean
): List<NeteaseHomeSongSource> {
    return candidates.filter { !it.requiresLogin || hasLogin }
}

internal fun availableNeteaseHomePlaylistSources(
    candidates: List<NeteaseHomePlaylistSource>,
    hasLogin: Boolean
): List<NeteaseHomePlaylistSource> {
    return candidates.filter { !it.requiresLogin || hasLogin }
}

internal fun appendUniqueNeteaseHomeSongs(
    current: List<SongItem>,
    next: List<SongItem>,
    limit: Int
): List<SongItem> {
    if (limit <= 0) return emptyList()
    val merged = ArrayList<SongItem>(limit)
    val seen = LinkedHashSet<String>()
    fun keyOf(song: SongItem): String {
        return song.audioId
            ?.takeIf { it.isNotBlank() }
            ?: "${song.channelId}:${song.id}:${song.name}"
    }
    (current.asSequence() + next.asSequence()).forEach { song ->
        if (merged.size >= limit) return@forEach
        if (seen.add(keyOf(song))) {
            merged.add(song)
        }
    }
    return merged
}

internal fun parseNeteaseHomeSongs(raw: String, limit: Int = Int.MAX_VALUE): List<SongItem> {
    val root = JSONObject(raw)
    val code = root.optInt("code", -1)
    if (code != 200) {
        throw ApiCodeException(code)
    }
    val songs = firstSongArray(root) ?: return emptyList()
    return buildList(songs.length()) {
        for (index in 0 until songs.length()) {
            val container = songs.optJSONObject(index) ?: continue
            val song = container.optJSONObject("song") ?: container
            parseNeteaseHomeSong(song)?.let(::add)
            if (size >= limit) break
        }
    }
}

internal fun parseNeteaseHomePlaylists(
    raw: String,
    limit: Int = Int.MAX_VALUE
): List<PlaylistSummary> {
    val root = JSONObject(raw)
    val code = root.optInt("code", -1)
    if (code != 200) {
        throw ApiCodeException(code)
    }
    val playlists = firstPlaylistArray(root) ?: return emptyList()
    return buildList(playlists.length()) {
        for (index in 0 until playlists.length()) {
            val playlist = playlists.optJSONObject(index) ?: continue
            parseNeteasePlaylistSummary(playlist)?.let(::add)
            if (size >= limit) break
        }
    }
}

internal fun parseNeteasePlaylistDetailSummary(
    raw: String,
    fallback: NeteaseRadarPlaylistDefinition
): PlaylistSummary {
    val root = JSONObject(raw)
    val code = root.optInt("code", 200)
    if (code != 200) {
        throw ApiCodeException(code)
    }
    val playlist = root.optJSONObject("playlist")
    return playlist?.let(::parseNeteasePlaylistSummary)
        ?: fallback.toPlaylistSummary()
}

internal fun NeteaseRadarPlaylistDefinition.toPlaylistSummary(): PlaylistSummary {
    return PlaylistSummary(
        id = id,
        name = name,
        picUrl = "",
        playCount = 0L,
        trackCount = 0
    )
}

private fun firstSongArray(root: JSONObject): JSONArray? {
    return root.optJSONObject("data")?.optJSONArray("dailySongs")
        ?: root.optJSONObject("data")?.optJSONArray("songs")
        ?: root.optJSONArray("data")
        ?: root.optJSONArray("result")
        ?: root.optJSONArray("songs")
        ?: root.optJSONObject("playlist")?.optJSONArray("tracks")
}

private fun firstPlaylistArray(root: JSONObject): JSONArray? {
    return root.optJSONArray("result")
        ?: root.optJSONArray("recommend")
        ?: root.optJSONArray("playlists")
        ?: root.optJSONObject("data")?.optJSONArray("playlists")
        ?: root.optJSONObject("data")?.optJSONArray("list")
}

private fun parseNeteaseHomeSong(song: JSONObject): SongItem? {
    val songId = song.optLong("id", 0L)
    val name = song.optString("name", "")
    if (songId <= 0L || name.isBlank()) return null

    val artistItems = parseNeteaseArtistSummaries(song.optJSONArray("ar"))
        .ifEmpty { parseNeteaseArtistSummaries(song.optJSONArray("artists")) }
    val album = song.optJSONObject("al") ?: song.optJSONObject("album")
    return SongItem(
        id = songId,
        name = name,
        artist = artistItems.joinToString(" / ") { it.name },
        album = album?.optString("name", "").orEmpty(),
        albumId = album?.optLong("id", 0L) ?: 0L,
        durationMs = song.optLong("dt", song.optLong("duration", 0L)),
        coverUrl = toHttps(
            album?.optString("picUrl", "")
                ?.ifBlank { album.optString("picUrl_str", "") }
        ).takeIf { it.isNotBlank() },
        channelId = "netease",
        audioId = songId.toString(),
        neteaseArtists = artistItems
    )
}

private fun parseNeteasePlaylistSummary(playlist: JSONObject): PlaylistSummary? {
    val id = playlist.optLong("id", 0L)
    val name = playlist.optString("name", "")
    if (id <= 0L || name.isBlank()) return null
    return PlaylistSummary(
        id = id,
        name = name,
        picUrl = toHttps(
            playlist.optString("picUrl", "")
                .ifBlank { playlist.optString("coverImgUrl", "") }
                .ifBlank { playlist.optString("coverUrl", "") }
        ),
        playCount = playlist.optLong("playCount", playlist.optLong("playcount", 0L)),
        trackCount = playlist.optInt("trackCount", playlist.optInt("songCount", 0))
    )
}

private fun toHttps(url: String?): String {
    return url.orEmpty().replaceFirst("http://", "https://")
}
