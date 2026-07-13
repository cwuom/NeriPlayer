package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.listentogether.mapping.resolvedAudioId
import moe.ouom.neriplayer.listentogether.mapping.resolvedChannelId
import moe.ouom.neriplayer.listentogether.mapping.resolvedPlaylistContextId
import moe.ouom.neriplayer.listentogether.mapping.resolvedSubAudioId
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

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

internal fun List<SongItem>.indexOfTrack(track: SongItem?): Int {
    track ?: return -1
    return indexOfFirst { candidate -> candidate.sameTrackAs(track) }
}
