package moe.ouom.neriplayer.core.player.runtime.prefetch

import moe.ouom.neriplayer.data.model.SongItem

data class YouTubeWarmupTargets(
    val currentVideoId: String?,
    val nextVideoId: String?,
    val prefetchVideoIds: List<String>,
    val preferredQuality: String
) {
    val hasWork: Boolean
        get() = prefetchVideoIds.isNotEmpty()
}

fun resolveYouTubePrefetchTargets(
    playlist: List<SongItem>,
    currentSongIndex: Int,
    preferredQuality: String,
    videoIdForSong: (SongItem) -> String?
): YouTubeWarmupTargets {
    val normalizedIndex = currentSongIndex.coerceAtLeast(0)
    val remaining = (playlist.size - normalizedIndex).coerceAtLeast(0)
    val videoIds = collectPrefetchVideoIds(
        playlist, normalizedIndex, resolveYouTubePrefetchWindowSize(remaining), videoIdForSong
    )
    return YouTubeWarmupTargets(
        currentVideoId = videoIds.firstOrNull(),
        nextVideoId = videoIds.getOrNull(1),
        prefetchVideoIds = videoIds,
        preferredQuality = preferredQuality
    )
}

internal fun resolveYouTubePrefetchWindowSize(remaining: Int): Int = when {
    remaining <= 0 -> 0
    remaining <= 3 -> remaining
    remaining <= 8 -> 4
    remaining <= 20 -> 5
    else -> 6
}

private fun collectPrefetchVideoIds(
    playlist: List<SongItem>,
    startIndex: Int,
    windowSize: Int,
    videoIdForSong: (SongItem) -> String?
): List<String> = buildList {
    var cursor = startIndex
    while (size < windowSize && cursor < playlist.size) {
        val videoId = videoIdForSong(playlist[cursor])
        if (!videoId.isNullOrBlank() && !contains(videoId)) add(videoId)
        cursor++
    }
}

fun resolveYouTubeImmediatePrefetchTargets(
    playlist: List<SongItem>,
    currentSongIndex: Int,
    preferredQuality: String,
    videoIdForSong: (SongItem) -> String?
): YouTubeWarmupTargets {
    val currentVideoId = if (playlist.isEmpty()) null else {
        val normalizedIndex = currentSongIndex.coerceIn(0, playlist.lastIndex)
        videoIdForSong(playlist[normalizedIndex])
    }
    val videoIds = currentVideoId?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
    return YouTubeWarmupTargets(
        currentVideoId = videoIds.firstOrNull(),
        nextVideoId = null,
        prefetchVideoIds = videoIds,
        preferredQuality = preferredQuality
    )
}
