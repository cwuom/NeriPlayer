package moe.ouom.neriplayer.core.player.url

import java.security.MessageDigest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.PlaybackUrlCandidate
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.listentogether.mapping.MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.listentogether.playback.sameTrackAs
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate

internal const val LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX = "listen-together-stream"

internal fun PlayerManager.currentListenTogetherShareableStreamUrls(): List<String> {
    return buildList {
        _currentMediaUrl.value?.let(::add)
        activePlaybackCandidates.forEach { candidate ->
            addAll(candidate.playbackUrls())
        }
    }.map { it.trim() }
        .filter(::isDirectStreamUrl)
        .distinct()
        .take(MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES)
}

internal fun PlayerManager.listenTogetherFallbackStreamUrls(song: SongItem): List<String> {
    if (!isListenTogetherActive() || isCurrentUserControllerInListenTogether()) return emptyList()
    val room = activeListenTogetherRoomState() ?: return emptyList()
    if (!room.settings.shareAudioLinks || room.roomStatus != ListenTogetherRoomStatuses.ACTIVE) {
        return emptyList()
    }
    val targetTrack = room.track ?: room.queue.getOrNull(room.currentIndex) ?: return emptyList()
    if (!song.sameTrackAs(targetTrack.toSongItem())) return emptyList()
    return trustedListenTogetherStreamUrls(
        channelId = targetTrack.channelId,
        streamUrls = targetTrack.streamUrls,
        legacyStreamUrl = targetTrack.streamUrl
    )
}

internal fun PlayerManager.listenTogetherFallbackResult(song: SongItem): SongUrlResult.Success? {
    val candidates = listenTogetherFallbackStreamUrls(song).map { streamUrl ->
        PlaybackUrlCandidate(
            url = streamUrl,
            cacheKeyOverride = listenTogetherStreamCacheKey(song.stableKey(), streamUrl)
        )
    }
    val primary = candidates.firstOrNull() ?: return null
    return SongUrlResult.Success(
        url = primary.url,
        cacheKeyOverride = primary.cacheKeyOverride,
        fallbackCandidates = candidates.drop(1)
    )
}

internal fun PlayerManager.isCurrentListenTogetherFallbackMediaUrl(): Boolean {
    val currentUrl = _currentMediaUrl.value ?: return false
    val candidate = currentPlaybackCandidate() ?: return false
    return candidate.url == currentUrl &&
        candidate.cacheKeyOverride?.startsWith(LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX) == true
}

internal fun listenTogetherStreamCacheKey(stableKey: String, streamUrl: String): String {
    return "$LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX-${sha256Hex(stableKey)}" +
        "-${sha256Hex(streamUrl)}"
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(24)
}
