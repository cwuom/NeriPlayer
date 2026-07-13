package moe.ouom.neriplayer.core.player.service

internal fun hasCurrentSongFavoriteStateChanged(
    currentSongKey: String?,
    previousFavoriteSongKeys: Set<String>,
    updatedFavoriteSongKeys: Set<String>,
): Boolean {
    val songKey = currentSongKey ?: return false
    return (songKey in previousFavoriteSongKeys) != (songKey in updatedFavoriteSongKeys)
}
