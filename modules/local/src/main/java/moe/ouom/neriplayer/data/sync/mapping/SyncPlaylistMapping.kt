package moe.ouom.neriplayer.data.sync.mapping

import moe.ouom.neriplayer.data.sync.playlist.normalizedForDisplayOrder
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import android.content.Context
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist

fun SyncPlaylist.Companion.fromLocalPlaylist(playlist: LocalPlaylist, modifiedAt: Long = System.currentTimeMillis(), context: Context? = null, optimizeLegacyLyrics: Boolean = false): SyncPlaylist {
    val systemDescriptor = context?.let {
        SystemLocalPlaylists.resolve(playlist.id, playlist.name, it)
    }
    return mapResolvedLocalPlaylist(playlist, modifiedAt, systemDescriptor, context, optimizeLegacyLyrics)
}

internal fun mapResolvedLocalPlaylist(
    playlist: LocalPlaylist,
    modifiedAt: Long,
    systemDescriptor: SystemLocalPlaylists.Descriptor?,
    context: Context?,
    optimizeLegacyLyrics: Boolean = false
): SyncPlaylist {
    return SyncPlaylist(
        id = systemDescriptor?.id ?: playlist.id,
        name = systemDescriptor?.currentName ?: playlist.name,
        songs = playlist.songs.mapNotNull { SyncSong.fromSongItemOrNull(it, context, optimizeLegacyLyrics) },
        createdAt = playlist.id, // 使用ID作为创建时间
        modifiedAt = modifiedAt,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
    )
}

fun SyncPlaylist.toLocalPlaylist(): LocalPlaylist {
    val normalized = normalizedForDisplayOrder()
    return LocalPlaylist(
        id = normalized.id,
        name = normalized.name,
        songs = normalized.songs.map { it.toSongItem() }.toMutableList(),
        modifiedAt = normalized.modifiedAt,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
    )
}
