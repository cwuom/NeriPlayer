package moe.ouom.neriplayer.data.sync.sanitize

import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist

interface SyncSanitizationHost {
    val localFilesPlaylistId: Long
    fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist?
    fun isLocalSong(album: String?, mediaUri: String?, albumId: Long): Boolean
    fun sanitizeMediaUri(mediaUri: String?): String?
}
