package moe.ouom.neriplayer.ui

import android.net.Uri
import androidx.navigation.NavHostController
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.navigation.biliUploaderSourceRoute
import moe.ouom.neriplayer.ui.navigation.navigationGson

internal fun NavHostController.navigateToNeteaseArtist(artist: NeteaseArtistSummary) {
    val json = Uri.encode(navigationGson.toJson(artist))
    val currentEntry = currentBackStackEntry
    val currentIsArtist =
        currentEntry?.destination?.route == Destinations.NeteaseArtistDetail.route
    val currentArtist = currentEntry
        ?.arguments
        ?.getString("artistJson")
        ?.let {
            runCatching {
                navigationGson.fromJson(it, NeteaseArtistSummary::class.java)
            }.getOrNull()
        }
    if (currentArtist?.id == artist.id) {
        return
    }
    if (currentIsArtist) {
        popBackStack()
    }
    navigate("netease_artist_detail/$json") {
        launchSingleTop = true
    }
}
internal fun NavHostController.navigateToBiliUploader(uploader: BiliUploaderSummary) {
    if (uploader.mid <= 0L) return
    val currentEntry = currentBackStackEntry
    val currentIsUploader =
        currentEntry?.destination?.route == Destinations.BiliUploaderDetail.route
    val currentUploader = currentEntry
        ?.arguments
        ?.getString("uploaderJson")
        ?.let {
            runCatching {
                navigationGson.fromJson(it, BiliUploaderSummary::class.java)
            }.getOrNull()
        }
    if (currentUploader?.mid == uploader.mid) {
        return
    }
    if (currentIsUploader) {
        popBackStack()
    }
    navigate(biliUploaderSourceRoute(uploader)) {
        launchSingleTop = true
    }
}
internal fun NavHostController.navigateToYouTubeMusicCreator(creator: YouTubeMusicCreatorSummary) {
    if (creator.browseId.isBlank()) return
    val currentEntry = currentBackStackEntry
    val currentCreator = currentEntry
        ?.takeIf {
            it.destination.route == Destinations.YouTubeMusicCreatorDetail.route
        }
        ?.arguments
        ?.getString("creatorJson")
        ?.let { creatorJson ->
            runCatching {
                navigationGson.fromJson(
                    creatorJson,
                    YouTubeMusicCreatorSummary::class.java
                )
            }.getOrNull()
        }
    if (currentCreator?.browseId == creator.browseId) {
        return
    }
    val json = Uri.encode(navigationGson.toJson(creator))
    navigate("youtube_music_creator_detail/$json") {
        launchSingleTop = true
    }
}
internal fun NavHostController.navigateToYouTubeMusicPlaylist(playlist: YouTubeMusicPlaylist) {
    if (playlist.browseId.isBlank()) return
    val currentEntry = currentBackStackEntry
    val currentPlaylist = currentEntry
        ?.takeIf {
            it.destination.route == Destinations.YouTubeMusicPlaylistDetail.route
        }
        ?.arguments
        ?.getString("playlistJson")
        ?.let { playlistJson ->
            runCatching {
                navigationGson.fromJson(
                    playlistJson,
                    YouTubeMusicPlaylist::class.java
                )
            }.getOrNull()
        }
    if (currentPlaylist?.browseId == playlist.browseId) {
        return
    }
    val json = Uri.encode(navigationGson.toJson(playlist))
    navigate("youtube_music_playlist_detail/$json") {
        launchSingleTop = true
    }
}
