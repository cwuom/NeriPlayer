package moe.ouom.neriplayer.data.local.playlist.sort

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.local.playlist.sort/PlaylistSongSortPolicy
 * Updated: 2026/11/21
 */

import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.policy.isLocalMediaUri

/** 歌单排序方式。新增取值时同步维护排序 key 与界面文案。 */
enum class PlaylistSongSortMode {
    TITLE,
    FILE_NAME
}

enum class PlaylistSongSortDirection {
    ASCENDING,
    DESCENDING
}

data class PlaylistSongSortOrder(
    val mode: PlaylistSongSortMode,
    val direction: PlaylistSongSortDirection = PlaylistSongSortDirection.ASCENDING
)

/**
 * 按 [order] 生成新的歌曲顺序。
 *
 * 只决定展示顺序，不修改 [SongItem] 的任何字段；落盘由
 * `LocalPlaylistRepository.reorderSongs` 统一 stamp `addedAt` 并写 `songOrderVersion`。
 * 排序是稳定的：key 相同的歌曲按标题、再按稳定身份决定先后，结果与输入顺序无关。
 */
fun sortPlaylistSongs(songs: List<SongItem>, order: PlaylistSongSortOrder): List<SongItem> {
    if (songs.size < 2) return songs.toList()
    val comparator = playlistSongSortComparator(order.mode)
    return songs.sortedWith(
        if (order.direction == PlaylistSongSortDirection.DESCENDING) {
            comparator.reversed()
        } else {
            comparator
        }
    )
}

internal fun playlistSongSortComparator(mode: PlaylistSongSortMode): Comparator<SongItem> =
    Comparator { first, second ->
        val keyResult = comparePlaylistSortKeys(
            playlistSongSortKey(first, mode),
            playlistSongSortKey(second, mode)
        )
        if (keyResult != 0) return@Comparator keyResult
        // key 相同时先比标题，最后用歌曲身份兜底，避免相同 key 的歌曲随机换位
        val titleResult = comparePlaylistSortKeys(first.displayName(), second.displayName())
        if (titleResult != 0) return@Comparator titleResult
        val idResult = first.id.compareTo(second.id)
        if (idResult != 0) return@Comparator idResult
        first.album.compareTo(second.album)
    }

internal fun playlistSongSortKey(song: SongItem, mode: PlaylistSongSortMode): String =
    when (mode) {
        PlaylistSongSortMode.TITLE -> song.displayName().ifBlank { song.name }
        PlaylistSongSortMode.FILE_NAME -> playlistSongFileName(song) ?: song.displayName().ifBlank { song.name }
    }

/**
 * 本地文件的文件名：优先持久化的 `localFileName`，其次从本地路径 / 本地 URI 尾部取。
 *
 * 复用全局的本地媒体判定：`content://`、`file:`、`/storage/...` 这类绝对路径和
 * `android.resource://` 都算本地；`ytmusic://`、`http(s)://` 等远端地址不是文件名，
 * 直接返回 null，由调用方回退到标题。否则自定义歌单里的远端歌曲会按视频 ID 排序，
 * 和界面上「仅对本地文件生效」的说明不符。
 */
internal fun playlistSongFileName(song: SongItem): String? {
    song.localFileName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    song.localFilePath?.let(::playlistSortFileNameFromPath)?.let { return it }
    val uri = song.mediaUri?.trim().orEmpty()
    if (!isLocalMediaUri(uri)) return null
    return playlistSortFileNameFromPath(uri)
}

private fun playlistSortFileNameFromPath(value: String): String? {
    val withoutQuery = value.substringBefore('?').substringBefore('#')
    val name = withoutQuery.substringAfterLast('/').substringAfterLast('\\').trim()
    return name.takeIf { it.isNotEmpty() }
}

/**
 * 大小写不敏感的自然排序：数字段按数值比较，`第2首` 排在 `第10首` 之前，
 * 前缀关系的短串排在前面。非 ASCII 数字（全角等）按字符本身处理。
 */
internal fun comparePlaylistSortKeys(first: String, second: String): Int {
    val left = first.trim()
    val right = second.trim()
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftChar = left[leftIndex]
        val rightChar = right[rightIndex]
        if (leftChar.isDigit() && rightChar.isDigit()) {
            val leftStart = leftIndex
            while (leftIndex < left.length && left[leftIndex].isDigit()) leftIndex++
            val rightStart = rightIndex
            while (rightIndex < right.length && right[rightIndex].isDigit()) rightIndex++
            val numericResult = compareNumericRun(
                left.substring(leftStart, leftIndex),
                right.substring(rightStart, rightIndex)
            )
            if (numericResult != 0) return numericResult
            continue
        }
        val lowerLeft = leftChar.lowercaseChar()
        val lowerRight = rightChar.lowercaseChar()
        if (lowerLeft != lowerRight) return lowerLeft.compareTo(lowerRight)
        leftIndex++
        rightIndex++
    }
    return (left.length - leftIndex) - (right.length - rightIndex)
}

private fun compareNumericRun(first: String, second: String): Int {
    val left = first.trimStart('0').ifEmpty { "0" }
    val right = second.trimStart('0').ifEmpty { "0" }
    if (left.length != right.length) return left.length - right.length
    return left.compareTo(right)
}
