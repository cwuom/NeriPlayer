package moe.ouom.neriplayer.data.sync.merge.playlist

import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.sync.identity.identity

internal object SyncFavoritePlaylistMergePolicy {
    fun merge(left: SyncFavoritePlaylist, right: SyncFavoritePlaylist): SyncFavoritePlaylist {
        val newer = if (right.modifiedAt > left.modifiedAt) right else left
        val older = if (newer === left) right else left
        if (left.isDeleted != right.isDeleted) return mergeDifferentState(left, right, newer, older)
        if (newer.isDeleted) return newer.copy(
            songs = emptyList(), trackCount = 0, addedTime = maxOf(left.addedTime, right.addedTime),
            sortOrder = maxOf(left.sortOrder, right.sortOrder)
        )
        return mergeActive(left, right, newer, older)
    }

    private fun mergeDifferentState(
        left: SyncFavoritePlaylist, right: SyncFavoritePlaylist,
        newer: SyncFavoritePlaylist, older: SyncFavoritePlaylist
    ): SyncFavoritePlaylist {
        if (left.modifiedAt == right.modifiedAt) {
            val deleted = if (left.isDeleted) left else right
            return deleted.copy(
                songs = emptyList(), trackCount = 0, addedTime = maxOf(left.addedTime, right.addedTime),
                modifiedAt = maxOf(left.modifiedAt, right.modifiedAt), sortOrder = maxOf(left.sortOrder, right.sortOrder)
            )
        }
        if (newer.isDeleted) return newer.copy(songs = emptyList(), trackCount = 0, sortOrder = maxOf(left.sortOrder, right.sortOrder))
        return newer.copy(
            songs = (left.songs + right.songs).distinctBy { it.identity() },
            trackCount = maxOf(left.trackCount, right.trackCount, left.songs.size, right.songs.size),
            sortOrder = effectiveSortOrder(newer, older)
        )
    }

    private fun mergeActive(
        left: SyncFavoritePlaylist, right: SyncFavoritePlaylist,
        newer: SyncFavoritePlaylist, older: SyncFavoritePlaylist
    ): SyncFavoritePlaylist {
        val songs = (left.songs + right.songs).distinctBy { it.identity() }
        return newer.copy(
            coverUrl = newer.coverUrl ?: older.coverUrl, songs = songs,
            trackCount = maxOf(left.trackCount, right.trackCount, songs.size),
            addedTime = maxOf(left.addedTime, right.addedTime), modifiedAt = maxOf(left.modifiedAt, right.modifiedAt),
            sortOrder = effectiveSortOrder(newer, older), isDeleted = false
        )
    }

    private fun effectiveSortOrder(newer: SyncFavoritePlaylist, older: SyncFavoritePlaylist): Long =
        if (newer.sortOrder > 0L) newer.sortOrder else older.sortOrder
}
