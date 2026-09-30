package moe.ouom.neriplayer.core.player.ltw

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

fun SongItem.resolvedChannelId(): String? =
    with(PlayerListenTogetherSongMapper) { this@resolvedChannelId.resolvedChannelId() }

fun SongItem.resolvedAudioId(): String? =
    with(PlayerListenTogetherSongMapper) { this@resolvedAudioId.resolvedAudioId() }

fun SongItem.resolvedSubAudioId(): String? =
    with(PlayerListenTogetherSongMapper) { this@resolvedSubAudioId.resolvedSubAudioId() }

fun SongItem.resolvedPlaylistContextId(): String? =
    with(PlayerListenTogetherSongMapper) { this@resolvedPlaylistContextId.resolvedPlaylistContextId() }

fun SongItem.toListenTogetherTrackOrNull(includeLocal: Boolean = false): ListenTogetherTrack? =
    with(PlayerListenTogetherSongMapper) { this@toListenTogetherTrackOrNull.toListenTogetherTrackOrNull(includeLocal) }

fun ListenTogetherTrack.toSongItem(): SongItem =
    with(PlayerListenTogetherSongMapper) { this@toSongItem.toSongItem() }

fun ListenTogetherRoomState.targetSongItem(): SongItem? =
    with(PlayerListenTogetherSongMapper) { this@targetSongItem.targetSongItem() }

fun SongItem.sameTrackAs(other: SongItem): Boolean =
    with(PlayerListenTogetherSongMapper) { this@sameTrackAs.sameTrackAs(other) }

fun List<SongItem>.hasSameTrackSequenceAs(other: List<SongItem>): Boolean =
    with(PlayerListenTogetherSongMapper) { this@hasSameTrackSequenceAs.hasSameTrackSequenceAs(other) }

fun List<SongItem>.hasSameTrackMultisetAs(other: List<SongItem>): Boolean =
    with(PlayerListenTogetherSongMapper) { this@hasSameTrackMultisetAs.hasSameTrackMultisetAs(other) }

fun shouldApplyListenTogetherQueueUpdateWithoutReload(causeType: String?, currentQueue: List<SongItem>, currentSong: SongItem?, incomingQueue: List<SongItem>, incomingCurrentIndex: Int): Boolean =
    with(PlayerListenTogetherSongMapper) { shouldApplyListenTogetherQueueUpdateWithoutReload(causeType, currentQueue, currentSong, incomingQueue, incomingCurrentIndex) }

fun List<SongItem>.indexOfTrack(track: SongItem?): Int =
    with(PlayerListenTogetherSongMapper) { this@indexOfTrack.indexOfTrack(track) }

fun SongItem?.isShareableForListenTogether(): Boolean =
    with(PlayerListenTogetherSongMapper) { this@isShareableForListenTogether.isShareableForListenTogether() }

fun List<SongItem>.hasShareableListenTogetherTrackAt(index: Int): Boolean =
    with(PlayerListenTogetherSongMapper) { this@hasShareableListenTogetherTrackAt.hasShareableListenTogetherTrackAt(index) }

fun List<SongItem>.toShareableQueueSnapshot(currentIndex: Int, roomSettings: ListenTogetherRoomSettings? = null, includeResolvedStreamUrl: Boolean = true, resolvedCurrentStreamUrls: List<String>? = null): Pair<List<ListenTogetherTrack>, Int> =
    with(PlayerListenTogetherSongMapper) { this@toShareableQueueSnapshot.toShareableQueueSnapshot(currentIndex, roomSettings, includeResolvedStreamUrl, resolvedCurrentStreamUrls) }

fun List<SongItem>.toShareableShuffleRestoreQueueSnapshot(activeQueue: List<ListenTogetherTrack>): List<ListenTogetherTrack> =
    with(PlayerListenTogetherSongMapper) { this@toShareableShuffleRestoreQueueSnapshot.toShareableShuffleRestoreQueueSnapshot(activeQueue) }
