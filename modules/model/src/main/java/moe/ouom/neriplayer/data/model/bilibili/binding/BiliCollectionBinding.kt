package moe.ouom.neriplayer.data.model.bilibili.binding

import kotlinx.serialization.Serializable
import moe.ouom.neriplayer.data.model.SongItem

/** 绑定目标类型：单曲 / 专辑 / 歌单 */
const val BILI_BINDING_TARGET_SONG = "song"
const val BILI_BINDING_TARGET_ALBUM = "album"
const val BILI_BINDING_TARGET_PLAYLIST = "playlist"

/**
 * 一条曲目到 B 站分 P 的对应关系。
 *
 * [songId] 为网易云曲目 id，[cid] 为分 P 的 cid；[page] / [part] / [durationSec] 用于展示与时长校正。
 */
@Serializable
data class BiliCollectionBindingTrack(
    val songId: Long,
    val songName: String,
    val cid: Long,
    val page: Int,
    val part: String,
    val durationSec: Int = 0
)

/**
 * 用户手动绑定的「单曲 / 专辑 / 歌单 → B 站多 P 视频」映射。
 *
 * [targetKey] 是稳定标识，形如 `netease:song:<id>`、`netease:album:<id>`、
 * `netease:playlist:<id>`；取不到 id 时退回名称规范化文本，改名后自然失效而不会误放到别处。
 */
@Serializable
data class BiliCollectionBinding(
    val targetKey: String,
    val targetType: String = BILI_BINDING_TARGET_ALBUM,
    val targetId: Long = 0L,
    val targetName: String = "",
    val targetSubtitle: String = "",
    val bvid: String = "",
    val avid: Long = 0L,
    val videoTitle: String = "",
    val tracks: List<BiliCollectionBindingTrack> = emptyList(),
    val updatedAt: Long = 0L,
    /**
     * 本机解绑留下的墓碑。落库时以空 [bvid] 表示，读取、播放与 UI 都把它当成「没有绑定」，
     * 只有同步报文会带上它，让删除也能压过对面设备上的旧记录。
     */
    val isDeleted: Boolean = false
) {
    fun trackFor(song: SongItem): BiliCollectionBindingTrack? {
        if (tracks.isEmpty()) return null
        if (song.id > 0L) {
            tracks.firstOrNull { it.songId > 0L && it.songId == song.id }?.let { return it }
        }
        val songName = normalizeBiliBindingText(song.originalName ?: song.name)
        if (songName.isEmpty()) return null
        // 双方都有 id 时以 id 为准：否则同名翻唱会串到别人绑定的分 P 上
        return tracks.firstOrNull { track ->
            (song.id <= 0L || track.songId <= 0L) &&
                normalizeBiliBindingText(track.songName) == songName
        }
    }

    /**
     * 这次保存或删除的绑定是否牵涉这首歌：单曲键、所属专辑键或当前播放歌单键命中，
     * 或者这首歌本身就在这条绑定的分 P 列表里。
     *
     * 播放侧用它决定改动之后要不要立刻重解析当前音源。
     */
    fun affectsSong(song: SongItem, playlistBindingKey: String? = null): Boolean {
        if (bindingKeysForSong(song, playlistBindingKey).contains(targetKey)) return true
        return trackFor(song) != null
    }

    /** 单曲绑定只有一条轨，直接取；用于播放前判断 */
    fun singleTrack(): BiliCollectionBindingTrack? =
        tracks.singleOrNull() ?: tracks.firstOrNull()

    fun isEmpty(): Boolean = tracks.isEmpty()

    fun normalized(): BiliCollectionBinding {
        val trimmed = copy(
            targetKey = targetKey.trim(),
            targetType = targetType.trim().ifBlank { BILI_BINDING_TARGET_ALBUM },
            targetName = targetName.trim(),
            targetSubtitle = targetSubtitle.trim()
        )
        if (trimmed.isDeleted) {
            return trimmed.copy(bvid = "", avid = 0L, videoTitle = "", tracks = emptyList())
        }
        return trimmed.copy(
            bvid = trimmed.bvid.trim(),
            videoTitle = trimmed.videoTitle.trim(),
            tracks = trimmed.tracks
                .filter { it.cid > 0L }
                .distinctBy { it.cid }
                .sortedBy { it.page }
        )
    }

    fun isValid(): Boolean = if (isDeleted) {
        targetKey.isNotBlank()
    } else {
        targetKey.isNotBlank() && bvid.isNotBlank() && tracks.isNotEmpty()
    }
}

/** 单曲绑定键 */
fun biliSongBindingKey(songId: Long): String = "netease:song:$songId"

/** 专辑绑定键：有专辑 id 用 id，否则退回规范化专辑名 */
fun biliAlbumBindingKey(albumId: Long, albumName: String): String {
    if (albumId > 0L) return "netease:album:$albumId"
    return "netease:album:name:${compactBiliBindingText(albumName)}"
}

/** 歌单绑定键：有歌单 id 用 id，否则退回规范化歌单名 */
fun biliPlaylistBindingKey(playlistId: Long, playlistName: String): String {
    if (playlistId > 0L) return "netease:playlist:$playlistId"
    return "netease:playlist:name:${compactBiliBindingText(playlistName)}"
}

fun songBindingKeyForSong(song: SongItem): String = biliSongBindingKey(song.id)

/** 用歌曲信息反推所属专辑的绑定键 */
fun albumBindingKeyForSong(song: SongItem): String =
    biliAlbumBindingKey(song.albumId, song.album)

/**
 * 一首歌可能命中的绑定键，按优先级排列：单曲 → 当前播放来源歌单 → 所属专辑。
 * 单曲绑定最具体，用户既然单独指定过就以它为准。
 */
fun bindingKeysForSong(song: SongItem, playlistBindingKey: String?): List<String> {
    if (song.id <= 0L && playlistBindingKey.isNullOrBlank()) return emptyList()
    return buildList {
        if (song.id > 0L) add(biliSongBindingKey(song.id))
        playlistBindingKey?.takeIf { it.isNotBlank() }?.let(::add)
        add(albumBindingKeyForSong(song))
    }.distinct()
}

/** 仅保留字母、数字与日文假名/汉字等文字，便于跨符号差异比较 */
fun normalizeBiliBindingText(value: String): String {
    val builder = StringBuilder(value.length)
    value.lowercase().forEach { character ->
        if (character.isLetterOrDigit()) {
            builder.append(character)
        } else {
            builder.append(' ')
        }
    }
    return builder.toString().trim().replace(Regex("\\s+"), " ")
}

fun compactBiliBindingText(value: String): String =
    normalizeBiliBindingText(value).replace(" ", "")
