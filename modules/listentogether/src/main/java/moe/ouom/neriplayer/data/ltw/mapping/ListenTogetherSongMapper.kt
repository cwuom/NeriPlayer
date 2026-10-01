package moe.ouom.neriplayer.data.ltw.mapping

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

interface ListenTogetherSongMapper {
    fun SongItem.resolvedChannelId(): String?
    fun SongItem.resolvedAudioId(): String?
    fun SongItem.resolvedSubAudioId(): String?
    fun SongItem.resolvedPlaylistContextId(): String?
    fun SongItem.toListenTogetherTrackOrNull(includeLocal: Boolean = false): ListenTogetherTrack?
    fun ListenTogetherTrack.toSongItem(): SongItem
    fun ListenTogetherRoomState.targetSongItem(): SongItem?
    fun SongItem.sameTrackAs(other: SongItem): Boolean
    fun List<SongItem>.hasSameTrackSequenceAs(other: List<SongItem>): Boolean
    fun List<SongItem>.hasSameTrackMultisetAs(other: List<SongItem>): Boolean
    fun shouldApplyListenTogetherQueueUpdateWithoutReload(causeType: String?, currentQueue: List<SongItem>, currentSong: SongItem?, incomingQueue: List<SongItem>, incomingCurrentIndex: Int): Boolean
    fun List<SongItem>.indexOfTrack(track: SongItem?): Int
    fun SongItem?.isShareableForListenTogether(): Boolean
    fun List<SongItem>.hasShareableListenTogetherTrackAt(index: Int): Boolean
    fun List<SongItem>.toShareableQueueSnapshot(currentIndex: Int, roomSettings: ListenTogetherRoomSettings? = null, includeResolvedStreamUrl: Boolean = true, resolvedCurrentStreamUrls: List<String>? = null): Pair<List<ListenTogetherTrack>, Int>
    fun List<SongItem>.toShareableShuffleRestoreQueueSnapshot(activeQueue: List<ListenTogetherTrack>): List<ListenTogetherTrack>
}
