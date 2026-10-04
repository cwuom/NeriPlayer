package moe.ouom.neriplayer.core.player.service.car

import android.content.Context
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.os.Bundle
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.service.car.artwork.CarArtworkProvider
import moe.ouom.neriplayer.core.player.service.car.library.CarLibraryItem
import moe.ouom.neriplayer.core.player.service.car.library.CarLibrarySnapshot
import moe.ouom.neriplayer.core.player.service.car.library.CarLibraryLabels
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.R as CoreCommonR

internal fun androidCarMediaLibrary(context: Context): CarMediaLibrary {
    if (!PlayerDependencies.isInitialized()) return CarMediaLibrary(CarLibrarySnapshot())
    val playlists = PlayerManager.playlistsFlow.value
    val offline = LocalFilesPlaylist.firstOrNull(playlists, context)?.songs.orEmpty() +
        PlayerDependencies.downloads.downloadedSongs()
    return CarMediaLibrary(CarLibrarySnapshot(
        queue = PlayerManager.currentQueueFlow.value,
        playlists = playlists,
        history = PlayerDependencies.repositories.playHistoryRepo.historyFlow.value.map { it.toSongItem() },
        offlineSongs = offline.distinctBy { it.stableKey() },
    ), CarLibraryLabels(
        root = context.getString(CoreCommonR.string.app_name),
        queue = context.getString(CoreCommonR.string.playlist_queue),
        playlists = context.getString(CoreCommonR.string.library_favorite_tab_playlists),
        history = context.getString(CoreCommonR.string.recent_title),
        offline = context.getString(CoreCommonR.string.local_files),
    ))
}

internal fun CarLibraryItem.toAndroidMediaItem(context: Context): MediaBrowser.MediaItem {
    val builder = MediaDescription.Builder()
        .setMediaId(mediaId)
        .setTitle(title.take(CAR_MEDIA_TEXT_LIMIT))
        .setSubtitle(subtitle?.take(CAR_MEDIA_TEXT_LIMIT))
        .setDescription((song?.displayAlbum(context) ?: description)?.take(CAR_MEDIA_TEXT_LIMIT))
        .setExtras(Bundle().apply {
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
        })
    song?.let { builder.setIconUri(CarArtworkProvider.uriFor(context, it)) }
    return MediaBrowser.MediaItem(
        builder.build(),
        if (isPlayable) MediaBrowser.MediaItem.FLAG_PLAYABLE else MediaBrowser.MediaItem.FLAG_BROWSABLE,
    )
}

internal fun carMediaSessionQueue(
    context: Context,
    songs: List<SongItem>,
    selectedIndex: Int,
): List<MediaSession.QueueItem> {
    return carQueueWindow(carQueueEntries(songs), selectedIndex).map { entry ->
        val song = entry.song
        val item = CarLibraryItem(
            mediaId = CarMediaIds.song(CarMediaIds.QUEUE, entry.index, song.stableKey()),
            title = song.displayName(),
            subtitle = song.displayArtist(),
            description = song.album,
            song = song,
        )
        MediaSession.QueueItem(item.toAndroidMediaItem(context).description, entry.id)
    }
}

internal fun carBrowserRootId(rootHints: Bundle?, supportedFlags: Int?): String {
    val flags = supportedFlags ?: 0
    if (flags and MediaBrowser.MediaItem.FLAG_BROWSABLE != 0 && flags and MediaBrowser.MediaItem.FLAG_PLAYABLE == 0) {
        return CarMediaIds.ROOT
    }
    return carRequestedBrowserRootId(rootHints)
}

internal fun carRequestedBrowserRootId(rootHints: Bundle?): String = when {
    rootHints?.getBoolean("android.service.media.extra.OFFLINE", false) == true -> CarMediaIds.OFFLINE
    rootHints?.getBoolean("android.service.media.extra.RECENT", false) == true -> CarMediaIds.HISTORY
    else -> CarMediaIds.ROOT
}

private const val CAR_MEDIA_TEXT_LIMIT = 512
