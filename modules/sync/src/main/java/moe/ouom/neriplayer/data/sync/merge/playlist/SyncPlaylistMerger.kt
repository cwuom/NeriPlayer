package moe.ouom.neriplayer.data.sync.merge.playlist

import moe.ouom.neriplayer.data.sync.identity.identity

import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.data.sync.merge.song.SyncPlaylistSongMergePolicy
import moe.ouom.neriplayer.data.model.sync.SyncConflict
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.policy.mergePositiveTimestamp

internal data class PlaylistMergeResult(
    val playlist: SyncPlaylist,
    val conflict: SyncConflict?,
    val songsAdded: Int,
    val songsRemoved: Int,
    val isUpdated: Boolean
)

internal class SyncPlaylistMerger(private val host: SyncMergeHost) {
    fun mergePlaylist(
        local: SyncPlaylist,
        remote: SyncPlaylist,
        lastSyncTime: Long,
        playlistSongDeletions: List<SyncPlaylistSongDeletion>
    ): PlaylistMergeResult {
        val systemDescriptor = host.systemPlaylist(local.id, local.name)
            ?: host.systemPlaylist(remote.id, remote.name)
        val resolvedPlaylistId = systemDescriptor?.id ?: local.id
        val isFavorites = resolvedPlaylistId == host.favoritesPlaylistId
        val localChangedAfterSync = local.modifiedAt > lastSyncTime
        val remoteChangedAfterSync = remote.modifiedAt > lastSyncTime

        val name = SyncPlaylistNamePolicy(host).resolve(local, remote, systemDescriptor?.currentName, lastSyncTime)
        var isUpdated = name.isUpdated

        val songMergeResult = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = local.songs,
            remoteSongs = remote.songs,
            localModifiedAt = local.modifiedAt,
            remoteModifiedAt = remote.modifiedAt,
            localChangedAfterSync = localChangedAfterSync,
            remoteChangedAfterSync = remoteChangedAfterSync,
            lastSyncTime = lastSyncTime,
            isFavorites = isFavorites
        )
        val mergedSongs = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = resolvedPlaylistId,
            songs = songMergeResult.songs,
            deletions = playlistSongDeletions
        )
        if (songMergeResult.isUpdated || mergedSongs.size != songMergeResult.songs.size) {
            isUpdated = true
        }

        val identityChanges = countIdentityChanges(local.songs, mergedSongs)

        return PlaylistMergeResult(
            playlist = SyncPlaylist(
                id = resolvedPlaylistId,
                name = name.name,
                songs = mergedSongs,
                createdAt = mergePositiveTimestamp(local.createdAt, remote.createdAt),
                modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt),
                songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
            ),
            conflict = name.conflict,
            songsAdded = identityChanges.added,
            songsRemoved = identityChanges.removed,
            isUpdated = isUpdated
        )
    }

    private fun countIdentityChanges(localSongs: List<SyncSong>, mergedSongs: List<SyncSong>): SongIdentityChanges {
        val localIdentities = localSongs.mapTo(HashSet()) { it.identity() }
        val mergedIdentities = mergedSongs.mapTo(HashSet()) { it.identity() }
        return SongIdentityChanges(
            added = mergedIdentities.count { it !in localIdentities },
            removed = localIdentities.count { it !in mergedIdentities }
        )
    }

    private data class SongIdentityChanges(val added: Int, val removed: Int)

    fun mergeDeletedPlaylist(
        local: SyncPlaylist,
        remote: SyncPlaylist
    ): SyncPlaylist {
        val systemDescriptor = host.systemPlaylist(local.id, local.name)
            ?: host.systemPlaylist(remote.id, remote.name)
        val resolvedName = systemDescriptor?.currentName
            ?: local.name.takeIf { it.isNotBlank() }
            ?: remote.name
        return SyncPlaylist(
            id = systemDescriptor?.id ?: local.id,
            name = resolvedName,
            songs = emptyList(),
            createdAt = mergePositiveTimestamp(local.createdAt, remote.createdAt),
            modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt),
            isDeleted = true,
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
        )
    }
}
