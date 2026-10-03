package moe.ouom.neriplayer.ui.screen.tab.library

import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_BILI_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST

internal fun FavoritePlaylist.toFavoriteBiliUploader(): BiliUploaderSummary? {
    if (source != FAVORITE_SOURCE_BILI_ARTIST || id <= 0 || name.isBlank()) return null
    return BiliUploaderSummary(id, name, coverUrl.orEmpty())
}

internal fun FavoritePlaylist.toFavoriteYouTubeCreator(): YouTubeMusicCreatorSummary? {
    if (source != FAVORITE_SOURCE_YOUTUBE_ARTIST || name.isBlank()) return null
    val creatorId = browseId?.takeIf { it.isNotBlank() } ?: return null
    return YouTubeMusicCreatorSummary(creatorId, name, subtitle.orEmpty(), coverUrl.orEmpty())
}
