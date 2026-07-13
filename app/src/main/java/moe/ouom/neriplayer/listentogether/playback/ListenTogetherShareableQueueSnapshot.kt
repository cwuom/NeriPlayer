package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.listentogether.mapping.toListenTogetherTrackOrNull
import moe.ouom.neriplayer.listentogether.mapping.withStreamUrl
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomSettings
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import moe.ouom.neriplayer.listentogether.session.normalized
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal fun List<SongItem>.toShareableQueueSnapshot(
    currentIndex: Int,
    roomSettings: ListenTogetherRoomSettings? = null,
    includeResolvedStreamUrl: Boolean = true
): Pair<List<ListenTogetherTrack>, Int> {
    if (isEmpty()) return emptyList<ListenTogetherTrack>() to 0

    val targetSong = getOrNull(currentIndex.coerceIn(0, lastIndex))
    val targetStableKey = targetSong?.toListenTogetherTrackOrNull()?.stableKey
    val currentStreamUrl = currentResolvedStreamUrl().takeIf { includeResolvedStreamUrl }
    val shareableQueue = mapNotNull { song ->
        song.toListenTogetherTrackOrNull()?.let { track ->
            if (roomSettings.normalized().shareAudioLinks && track.stableKey == targetStableKey) {
                track.withStreamUrl(currentStreamUrl)
            } else {
                track
            }
        }
    }.boundedAroundStableKey(targetStableKey)
    if (shareableQueue.isEmpty()) return shareableQueue to 0

    val resolvedCurrentIndex = targetStableKey?.let { stableKey ->
        shareableQueue.indexOfFirst { it.stableKey == stableKey }.takeIf { it >= 0 }
    } ?: 0

    return shareableQueue to resolvedCurrentIndex
}

internal fun List<ListenTogetherTrack>.mergeCurrentTrack(
    currentIndex: Int,
    currentTrack: ListenTogetherTrack?
): List<ListenTogetherTrack> {
    val replacement = currentTrack ?: return this
    if (currentIndex !in indices) return this
    if (this[currentIndex] == replacement) return this
    return toMutableList().also { it[currentIndex] = replacement }
}

private fun currentResolvedStreamUrl(): String? {
    val candidate = PlayerManager.currentMediaUrlFlow.value?.trim().orEmpty()
    if (candidate.isBlank()) return null
    if (candidate.startsWith("https://", ignoreCase = true) || candidate.startsWith("http://", ignoreCase = true)) {
        return candidate
    }
    return null
}
