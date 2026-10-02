package moe.ouom.neriplayer.data.sync.merge.playlist

import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.data.model.sync.SyncConflict
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion

internal enum class SyncPlaylistChange { ADDED, UPDATED, DELETED, UNCHANGED }

internal data class SyncPlaylistMergeEntry(
    val playlist: SyncPlaylist,
    val change: SyncPlaylistChange,
    val songsAdded: Int = 0,
    val songsRemoved: Int = 0,
    val conflict: SyncConflict? = null,
    val mapKey: Long = playlist.id
)

internal class SyncPlaylistCollectionResult(
    val playlists: List<SyncPlaylist>,
    private val entries: List<SyncPlaylistMergeEntry>
) {
    val playlistsAdded: Int get() = entries.count { it.change == SyncPlaylistChange.ADDED }
    val playlistsUpdated: Int get() = entries.count { it.change == SyncPlaylistChange.UPDATED }
    val playlistsDeleted: Int get() = entries.count { it.change == SyncPlaylistChange.DELETED }
    val songsAdded: Int get() = entries.sumOf { it.songsAdded }
    val songsRemoved: Int get() = entries.sumOf { it.songsRemoved }
    val conflicts: List<SyncConflict> get() = entries.mapNotNull { it.conflict }
}

internal class SyncPlaylistCollectionMerger(private val host: SyncMergeHost) {
    private val playlistMerger = SyncPlaylistMerger(host)

    fun merge(
        local: List<SyncPlaylist>,
        remote: List<SyncPlaylist>,
        lastSyncTime: Long,
        deletions: List<SyncPlaylistSongDeletion>
    ): SyncPlaylistCollectionResult {
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }
        val deletionsByPlaylist = deletions.groupBy(SyncPlaylistSongDeletion::playlistId)
        val entries = (localById.keys + remoteById.keys).mapNotNull { id ->
            val left = localById[id]
            val right = remoteById[id]
            val descriptor = left?.let { host.systemPlaylist(it.id, it.name) }
                ?: right?.let { host.systemPlaylist(it.id, it.name) }
            mergePair(left, right, lastSyncTime, deletionsByPlaylist[descriptor?.id ?: id].orEmpty())
        }
        val mergedById = entries.associateBy({ it.mapKey }, { it.playlist })
        return SyncPlaylistCollectionResult(
            SyncPlaylistOrder.orderMergedPlaylists(local, remote, mergedById, lastSyncTime), entries
        )
    }

    private fun mergePair(
        local: SyncPlaylist?,
        remote: SyncPlaylist?,
        lastSyncTime: Long,
        deletions: List<SyncPlaylistSongDeletion>
    ): SyncPlaylistMergeEntry? = when {
        local == null -> remote?.let { unpaired(it, deletions) }
        remote == null -> unpaired(local, deletions)
        local.isDeleted || remote.isDeleted -> mergeDeletedPair(local, remote, lastSyncTime, deletions)
        else -> mergedEntry(playlistMerger.mergePlaylist(local, remote, lastSyncTime, deletions))
    }

    private fun unpaired(playlist: SyncPlaylist, deletions: List<SyncPlaylistSongDeletion>): SyncPlaylistMergeEntry {
        val id = host.systemPlaylist(playlist.id, playlist.name)?.id ?: playlist.id
        val songs = SyncPlaylistDeletionPolicy.applyDeletions(id, playlist.songs, deletions)
        return SyncPlaylistMergeEntry(
            if (songs == playlist.songs) playlist else playlist.copy(songs = songs),
            if (playlist.isDeleted) SyncPlaylistChange.DELETED else SyncPlaylistChange.ADDED
        )
    }

    private fun mergeDeletedPair(
        local: SyncPlaylist,
        remote: SyncPlaylist,
        lastSyncTime: Long,
        deletions: List<SyncPlaylistSongDeletion>
    ): SyncPlaylistMergeEntry {
        if (SyncPlaylistDeletionPolicy.shouldKeepPlaylistDeleted(local, remote)) {
            return SyncPlaylistMergeEntry(
                playlistMerger.mergeDeletedPlaylist(local, remote), SyncPlaylistChange.DELETED,
                mapKey = local.id
            )
        }
        val active = if (local.isDeleted) remote else local
        val merged = playlistMerger.mergePlaylist(active, active, lastSyncTime, deletions)
        return SyncPlaylistMergeEntry(
            merged.playlist, SyncPlaylistChange.UPDATED, merged.songsAdded, merged.songsRemoved
        )
    }

    private fun mergedEntry(merged: PlaylistMergeResult) = SyncPlaylistMergeEntry(
        merged.playlist,
        if (merged.isUpdated) SyncPlaylistChange.UPDATED else SyncPlaylistChange.UNCHANGED,
        merged.songsAdded,
        merged.songsRemoved,
        merged.conflict
    )
}
