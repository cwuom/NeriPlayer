package moe.ouom.neriplayer.data

import android.content.Context

object SystemLocalPlaylists {
    data class Descriptor(
        val id: Long,
        val currentName: String
    )

    fun matchesReservedName(name: String?, context: Context? = null): Boolean {
        return FavoritesPlaylist.matches(name, context) || LocalFilesPlaylist.matches(name, context)
    }

    fun isSystemPlaylist(playlist: LocalPlaylist, context: Context): Boolean {
        return resolve(playlist.id, playlist.name, context) != null
    }

    fun resolve(playlistId: Long, playlistName: String?, context: Context): Descriptor? {
        return when {
            playlistId == FavoritesPlaylist.SYSTEM_ID || FavoritesPlaylist.matches(playlistName, context) -> {
                Descriptor(FavoritesPlaylist.SYSTEM_ID, FavoritesPlaylist.currentName(context))
            }

            playlistId == LocalFilesPlaylist.SYSTEM_ID || LocalFilesPlaylist.matches(playlistName, context) -> {
                Descriptor(LocalFilesPlaylist.SYSTEM_ID, LocalFilesPlaylist.currentName(context))
            }

            else -> null
        }
    }

    fun normalize(playlists: List<LocalPlaylist>, context: Context): List<LocalPlaylist> {
        val favorites = FavoritesPlaylist.merge(
            playlists.filter { FavoritesPlaylist.isSystemPlaylist(it, context) },
            context
        )
        val localFiles = LocalFilesPlaylist.merge(
            playlists.filter { LocalFilesPlaylist.isSystemPlaylist(it, context) },
            context
        )
        val others = playlists.filterNot { isSystemPlaylist(it, context) }

        return buildList {
            add(favorites)
            addAll(others)
            add(localFiles)
        }
    }
}
