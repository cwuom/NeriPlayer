package moe.ouom.neriplayer.data.ltw.testing

import moe.ouom.neriplayer.data.ltw.mapping.DefaultListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

internal object TestSongMapper : ListenTogetherSongMapper by DefaultListenTogetherSongMapper(
    isLocalSong = { it.channelId.equals("local", ignoreCase = true) || !it.localFilePath.isNullOrBlank() },
    localAlbumIdentity = "Local"
)

fun SongItem.resolvedChannelId(): String? =
    with(TestSongMapper) { this@resolvedChannelId.resolvedChannelId() }

fun SongItem.resolvedAudioId(): String? =
    with(TestSongMapper) { this@resolvedAudioId.resolvedAudioId() }

fun SongItem.resolvedSubAudioId(): String? =
    with(TestSongMapper) { this@resolvedSubAudioId.resolvedSubAudioId() }

fun SongItem.resolvedPlaylistContextId(): String? =
    with(TestSongMapper) { this@resolvedPlaylistContextId.resolvedPlaylistContextId() }

fun SongItem.toListenTogetherTrackOrNull(includeLocal: Boolean = false): ListenTogetherTrack? =
    with(TestSongMapper) { this@toListenTogetherTrackOrNull.toListenTogetherTrackOrNull(includeLocal) }

fun ListenTogetherTrack.toSongItem(): SongItem =
    with(TestSongMapper) { this@toSongItem.toSongItem() }

fun ListenTogetherRoomState.targetSongItem(): SongItem? =
    with(TestSongMapper) { this@targetSongItem.targetSongItem() }

fun SongItem.sameTrackAs(other: SongItem): Boolean =
    with(TestSongMapper) { this@sameTrackAs.sameTrackAs(other) }

fun List<SongItem>.hasSameTrackSequenceAs(other: List<SongItem>): Boolean =
    with(TestSongMapper) { this@hasSameTrackSequenceAs.hasSameTrackSequenceAs(other) }

fun List<SongItem>.hasSameTrackMultisetAs(other: List<SongItem>): Boolean =
    with(TestSongMapper) { this@hasSameTrackMultisetAs.hasSameTrackMultisetAs(other) }

fun shouldApplyListenTogetherQueueUpdateWithoutReload(causeType: String?, currentQueue: List<SongItem>, currentSong: SongItem?, incomingQueue: List<SongItem>, incomingCurrentIndex: Int): Boolean =
    with(TestSongMapper) { shouldApplyListenTogetherQueueUpdateWithoutReload(causeType, currentQueue, currentSong, incomingQueue, incomingCurrentIndex) }

fun List<SongItem>.indexOfTrack(track: SongItem?): Int =
    with(TestSongMapper) { this@indexOfTrack.indexOfTrack(track) }

fun SongItem?.isShareableForListenTogether(): Boolean =
    with(TestSongMapper) { this@isShareableForListenTogether.isShareableForListenTogether() }

fun List<SongItem>.hasShareableListenTogetherTrackAt(index: Int): Boolean =
    with(TestSongMapper) { this@hasShareableListenTogetherTrackAt.hasShareableListenTogetherTrackAt(index) }

fun List<SongItem>.toShareableQueueSnapshot(currentIndex: Int, roomSettings: ListenTogetherRoomSettings? = null, includeResolvedStreamUrl: Boolean = true, resolvedCurrentStreamUrls: List<String>? = null): Pair<List<ListenTogetherTrack>, Int> =
    with(TestSongMapper) { this@toShareableQueueSnapshot.toShareableQueueSnapshot(currentIndex, roomSettings, includeResolvedStreamUrl, resolvedCurrentStreamUrls) }

fun List<SongItem>.toShareableShuffleRestoreQueueSnapshot(activeQueue: List<ListenTogetherTrack>): List<ListenTogetherTrack> =
    with(TestSongMapper) { this@toShareableShuffleRestoreQueueSnapshot.toShareableShuffleRestoreQueueSnapshot(activeQueue) }
