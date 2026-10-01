package moe.ouom.neriplayer.data.sync.change

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.identity.identity

internal object SyncPlaylistChangeDetector {
    fun playlistsChanged(remote: SyncData, merged: SyncData): Boolean {
        if (remote.playlists.map(SyncPlaylist::id) != merged.playlists.map(SyncPlaylist::id)) return true
        val remoteMap = remote.playlists.associateBy(SyncPlaylist::id)
        return merged.playlists.any { playlist ->
            val previous = remoteMap[playlist.id] ?: return@any true
            !samePlaylist(previous, playlist)
        }
    }

    fun favoritesChanged(remote: SyncData, merged: SyncData): Boolean {
        if (remote.favoritePlaylists.size != merged.favoritePlaylists.size) return true
        return SyncCollectionComparison.keyedChanged(
            remote.favoritePlaylists, merged.favoritePlaylists, { "${it.id}_${it.source}" }, ::sameFavorite
        )
    }

    private fun samePlaylist(a: SyncPlaylist, b: SyncPlaylist): Boolean =
        a.isDeleted == b.isDeleted && a.name == b.name && a.songOrderVersion == b.songOrderVersion &&
            sameSongs(a.songs, b.songs)

    private fun sameFavorite(a: SyncFavoritePlaylist, b: SyncFavoritePlaylist): Boolean =
        a.isDeleted == b.isDeleted && a.modifiedAt == b.modifiedAt && a.sortOrder == b.sortOrder &&
            a.trackCount == b.trackCount && sameSongs(a.songs, b.songs)

    private fun sameSongs(a: List<SyncSong>, b: List<SyncSong>): Boolean =
        !SyncCollectionComparison.orderedChanged(a, b) { first, second ->
            first.identity() == second.identity() && SyncSongMetadataComparison.same(first, second)
        }
}
