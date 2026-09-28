package moe.ouom.neriplayer.data.sync.merge

import moe.ouom.neriplayer.data.sync.model.ConflictResolution
import moe.ouom.neriplayer.data.sync.model.ConflictType
import moe.ouom.neriplayer.data.sync.model.SyncConflict
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist

internal data class SyncPlaylistName(
    val name: String,
    val conflict: SyncConflict? = null,
    val isUpdated: Boolean = false
)

internal class SyncPlaylistNamePolicy(private val host: SyncMergeHost) {
    fun resolve(local: SyncPlaylist, remote: SyncPlaylist, systemName: String?, lastSyncTime: Long): SyncPlaylistName {
        if (systemName != null) return SyncPlaylistName(systemName)
        if (local.name == remote.name) return SyncPlaylistName(local.name)
        val localChanged = local.modifiedAt > lastSyncTime
        val remoteChanged = remote.modifiedAt > lastSyncTime
        return when {
            remoteChanged && !localChanged -> renamed(remote, ConflictResolution.REMOTE_WINS)
            localChanged && !remoteChanged -> renamed(local, ConflictResolution.LOCAL_WINS)
            else -> renamed(local, ConflictResolution.MANUAL_REQUIRED)
        }
    }

    private fun renamed(playlist: SyncPlaylist, resolution: ConflictResolution): SyncPlaylistName {
        val remoteWins = resolution == ConflictResolution.REMOTE_WINS
        return SyncPlaylistName(
            name = playlist.name,
            conflict = SyncConflict(
                type = ConflictType.PLAYLIST_RENAMED_BOTH_SIDES,
                playlistId = playlist.id,
                playlistName = playlist.name,
                description = if (remoteWins) host.remoteRenameMessage(playlist.name) else host.localRenameMessage(playlist.name),
                resolution = resolution
            ),
            isUpdated = remoteWins
        )
    }
}
