package moe.ouom.neriplayer.data.sync.playlist

import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.policy.copyWithNormalizedMembershipTokens

fun SyncPlaylist.normalizedForDisplayOrder(): SyncPlaylist {
    if (isDeleted) {
        return copy(
            songs = emptyList(),
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
        )
    }

    val displaySongs = if (songOrderVersion >= DISPLAY_ORDER_SONG_ORDER_VERSION) {
        songs.sortedByAddedAtForDisplay()
    } else {
        songs.migrateLegacySongsToDisplayOrder(modifiedAt)
    }
    return if (
        songOrderVersion >= DISPLAY_ORDER_SONG_ORDER_VERSION &&
        displaySongs == songs
    ) {
        this
    } else {
        copy(
            songs = displaySongs,
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
        )
    }
}

private fun List<SyncSong>.migrateLegacySongsToDisplayOrder(
    playlistModifiedAt: Long
): List<SyncSong> {
    if (isEmpty()) return emptyList()
    // 锚点必须与设备墙钟无关: 只用歌单自身 modifiedAt (快照产生时刻) 而非 now
    // 否则被抬高的 addedAt 恒大于任何历史 deletedAt, 使 identity 删除墓碑永久失效并被
    // pruneResolvedDeletions 裁剪, 导致已删歌曲复活 (P1-1)
    val newestAddedAt = legacyOrderAnchor(this, playlistModifiedAt)
    return asReversed().mapIndexed { index, song ->
        song.copyWithNormalizedMembershipTokens(
            addedAt = (newestAddedAt - index).coerceAtLeast(1L),
            legacyAddedAt = song.legacyAddedAt ?: song.addedAt
        )
    }
}

private fun legacyOrderAnchor(songs: List<SyncSong>, playlistModifiedAt: Long): Long =
    maxOf(playlistModifiedAt, songs.maxOf { it.addedAt }).coerceAtLeast(1L)

private fun List<SyncSong>.sortedByAddedAtForDisplay(): List<SyncSong> {
    if (size < 2) return this
    var previousAddedAt = Long.MAX_VALUE
    for (song in this) {
        if (song.addedAt > previousAddedAt) return sortedByDescending { it.addedAt }
        previousAddedAt = song.addedAt
    }
    return this
}
