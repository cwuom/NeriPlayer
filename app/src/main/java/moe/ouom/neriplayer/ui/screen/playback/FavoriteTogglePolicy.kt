package moe.ouom.neriplayer.ui.screen.playback

import android.content.Context
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem

internal fun nextFavoriteStateAfterTap(displayedIsFavorite: Boolean): Boolean {
    return !displayedIsFavorite
}

internal fun shouldConfirmFavoriteSync(song: SongItem?, adding: Boolean, context: Context? = null): Boolean =
    adding && song != null && FavoritesPlaylist.toSyncableSongOrNull(song, context) == null

internal fun shouldConfirmFavoriteSync(songs: List<SongItem>, context: Context? = null): Boolean =
    songs.any { shouldConfirmFavoriteSync(it, adding = true, context = context) }
