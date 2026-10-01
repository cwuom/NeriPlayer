package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist
import moe.ouom.neriplayer.data.sync.sanitize.SyncSanitizationHost
import moe.ouom.neriplayer.common.locale.LanguageManager

internal class AndroidSyncSanitizationHost(private val context: Context) : SyncSanitizationHost {
    override val localFilesPlaylistId = LocalFilesPlaylist.SYSTEM_ID

    override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? =
        SystemLocalPlaylists.resolve(id, name, LanguageManager.applyLanguage(context))?.let {
            SyncSystemPlaylist(it.id, it.currentName)
        }

    override fun isLocalSong(album: String?, mediaUri: String?, albumId: Long): Boolean =
        LocalSongSupport.isLocalSong(album, mediaUri, albumId, context)

    override fun sanitizeMediaUri(mediaUri: String?): String? =
        LocalSongSupport.sanitizeMediaUriForSync(mediaUri)
}
