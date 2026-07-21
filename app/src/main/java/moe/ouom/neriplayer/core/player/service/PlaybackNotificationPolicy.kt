package moe.ouom.neriplayer.core.player.service

internal fun hasCurrentSongFavoriteStateChanged(
    currentSongKey: String?,
    previousFavoriteSongKeys: Set<String>,
    updatedFavoriteSongKeys: Set<String>,
): Boolean {
    val songKey = currentSongKey ?: return false
    return (songKey in previousFavoriteSongKeys) != (songKey in updatedFavoriteSongKeys)
}

internal fun shouldUseInteractiveFavoriteIntent(
    localPlaylistsReady: Boolean,
    hasCurrentSong: Boolean,
    isFavorite: Boolean,
    isLocalSong: Boolean,
): Boolean {
    if (!hasCurrentSong) return false
    return !localPlaylistsReady || (!isFavorite && isLocalSong)
}

internal fun shouldAllowExternalFavoriteToggle(
    localPlaylistsReady: Boolean,
    hasCurrentSong: Boolean,
    requiresInteractiveConfirmation: Boolean,
): Boolean {
    return localPlaylistsReady && hasCurrentSong && !requiresInteractiveConfirmation
}
