package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.listentogether.mapping.resolvedAudioId
import moe.ouom.neriplayer.listentogether.mapping.resolvedChannelId
import moe.ouom.neriplayer.listentogether.mapping.resolvedPlaylistContextId
import moe.ouom.neriplayer.listentogether.mapping.resolvedSubAudioId
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.SongItem

internal fun ListenTogetherRoomState.currentStableKey(): String? {
    return track?.stableKey ?: queue.getOrNull(currentIndex)?.stableKey
}

internal fun ListenTogetherEvent.requestedStableKey(): String? {
    return requestTrackStableKey
        ?: track?.stableKey
        ?: queue?.getOrNull(currentIndex ?: -1)?.stableKey
}

internal fun ListenTogetherRoomState.targetSongItem(): SongItem? {
    return (track ?: queue.getOrNull(currentIndex))?.toSongItem()
}

internal fun SongItem.sameTrackAs(other: SongItem): Boolean {
    return resolvedChannelId() == other.resolvedChannelId() &&
        resolvedAudioId() == other.resolvedAudioId() &&
        resolvedSubAudioId() == other.resolvedSubAudioId() &&
        resolvedPlaylistContextId() == other.resolvedPlaylistContextId()
}

internal fun List<SongItem>.hasSameTrackSequenceAs(other: List<SongItem>): Boolean {
    if (size != other.size) return false
    return indices.all { index -> this[index].sameTrackAs(other[index]) }
}

private data class ListenTogetherPlaybackIdentity(
    val channelId: String?,
    val audioId: String?,
    val subAudioId: String?,
    val playlistContextId: String?
)

private fun SongItem.listenTogetherPlaybackIdentity(): ListenTogetherPlaybackIdentity {
    return ListenTogetherPlaybackIdentity(
        channelId = resolvedChannelId(),
        audioId = resolvedAudioId(),
        subAudioId = resolvedSubAudioId(),
        playlistContextId = resolvedPlaylistContextId()
    )
}

internal fun List<SongItem>.hasSameTrackMultisetAs(other: List<SongItem>): Boolean {
    if (size != other.size) return false
    return groupingBy { it.listenTogetherPlaybackIdentity() }.eachCount() ==
        other.groupingBy { it.listenTogetherPlaybackIdentity() }.eachCount()
}

internal fun shouldApplyListenTogetherQueueUpdateWithoutReload(
    causeType: String?,
    currentQueue: List<SongItem>,
    currentSong: SongItem?,
    incomingQueue: List<SongItem>,
    incomingCurrentIndex: Int
): Boolean {
    if (!isListenTogetherQueueUpdateCause(causeType)) return false
    val incomingCurrentSong = incomingQueue.getOrNull(incomingCurrentIndex) ?: return false
    if (currentQueue.isEmpty() || currentSong?.sameTrackAs(incomingCurrentSong) != true) {
        return false
    }
    return causeType !in LISTEN_TOGETHER_PLAYBACK_MODE_QUEUE_UPDATE_CAUSES ||
        currentQueue.hasSameTrackMultisetAs(incomingQueue)
}

internal fun isListenTogetherQueueUpdateCause(causeType: String?): Boolean {
    return causeType in LISTEN_TOGETHER_QUEUE_UPDATE_CAUSES ||
        causeType in LISTEN_TOGETHER_PLAYBACK_MODE_QUEUE_UPDATE_CAUSES
}

private val LISTEN_TOGETHER_QUEUE_UPDATE_CAUSES = setOf(
    "SET_QUEUE",
    "REQUEST_SET_QUEUE"
)

private val LISTEN_TOGETHER_PLAYBACK_MODE_QUEUE_UPDATE_CAUSES = setOf(
    "PLAYBACK_MODE",
    "REQUEST_PLAYBACK_MODE"
)

internal fun List<SongItem>.indexOfTrack(track: SongItem?): Int {
    track ?: return -1
    return indexOfFirst { candidate -> candidate.sameTrackAs(track) }
}
