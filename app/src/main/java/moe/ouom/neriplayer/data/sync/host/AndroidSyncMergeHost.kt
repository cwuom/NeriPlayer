package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import androidx.annotation.StringRes
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.sync.merge.SyncMergeHost
import moe.ouom.neriplayer.data.sync.merge.SyncSystemPlaylist

internal class AndroidSyncMergeHost(
    private val localizedContext: Context,
    @param:StringRes private val successMessageRes: Int
) : SyncMergeHost {
    override val favoritesPlaylistId: Long get() = FavoritesPlaylist.SYSTEM_ID
    override val mergeSuccessMessage: String get() = localizedContext.getString(successMessageRes)
    override val initialUploadMessage: String get() = localizedContext.getString(R.string.sync_initial_uploaded)

    override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? =
        SystemLocalPlaylists.resolve(id, name, localizedContext)?.let {
            SyncSystemPlaylist(it.id, it.currentName)
        }

    override fun localRenameMessage(name: String): String =
        localizedContext.getString(R.string.github_playlist_renamed_local, name)

    override fun remoteRenameMessage(name: String): String =
        localizedContext.getString(R.string.github_playlist_renamed_remote, name)
}
