package moe.ouom.neriplayer.data.model.playlist

import moe.ouom.neriplayer.data.model.SongItem

data class FavoritePlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val source: String,
    val browseId: String? = null,
    val playlistId: String? = null,
    val subtitle: String? = null,
    val songs: List<SongItem>,
    val addedTime: Long = System.currentTimeMillis(),
    val sortOrder: Long = addedTime,
    val modifiedAt: Long = addedTime,
    val isDeleted: Boolean = false
)
