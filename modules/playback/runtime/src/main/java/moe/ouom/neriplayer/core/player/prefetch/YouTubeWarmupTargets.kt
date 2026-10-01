package moe.ouom.neriplayer.core.player.prefetch

import moe.ouom.neriplayer.api.youtube.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.core.player.runtime.prefetch.YouTubeWarmupTargets
import moe.ouom.neriplayer.core.player.runtime.prefetch.resolveYouTubeImmediatePrefetchTargets
import moe.ouom.neriplayer.core.player.runtime.prefetch.resolveYouTubePrefetchTargets
import moe.ouom.neriplayer.data.model.SongItem

internal fun resolveYouTubeWarmupTargets(
    playlist: List<SongItem>,
    currentSongIndex: Int,
    preferredQuality: String
): YouTubeWarmupTargets {
    return resolveYouTubePrefetchTargets(playlist, currentSongIndex, preferredQuality) { song ->
        extractYouTubeMusicVideoId(song.mediaUri)
    }
}

internal fun resolveYouTubeImmediatePlaybackWarmupTargets(
    playlist: List<SongItem>,
    currentSongIndex: Int,
    preferredQuality: String
): YouTubeWarmupTargets {
    return resolveYouTubeImmediatePrefetchTargets(playlist, currentSongIndex, preferredQuality) { song ->
        extractYouTubeMusicVideoId(song.mediaUri)
    }
}
