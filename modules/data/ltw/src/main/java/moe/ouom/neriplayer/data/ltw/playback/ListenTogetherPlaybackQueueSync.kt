package moe.ouom.neriplayer.data.ltw.playback

import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState

internal data class ListenTogetherQueueSyncSnapshot(
    val queue: List<SongItem>,
    val targetIndex: Int,
    val targetSong: SongItem,
    val previousSong: SongItem?,
    val queueChanged: Boolean,
    val indexChanged: Boolean
)

internal class ListenTogetherPlaybackQueueSync(
    private val playback: ListenTogetherPlaybackHost,
    songMapper: ListenTogetherSongMapper
) : ListenTogetherSongMapper by songMapper {
    fun songQueue(state: ListenTogetherRoomState): List<SongItem> {
        if (state.queue.isNotEmpty()) {
            return state.queue.mergeCurrentTrack(state.currentIndex, state.track).map { it.toSongItem() }
        }
        return state.track?.let { listOf(it.toSongItem()) }.orEmpty()
    }

    fun synchronize(state: ListenTogetherRoomState, queue: List<SongItem>, causeType: String?): ListenTogetherQueueSyncSnapshot {
        val targetIndex = state.currentIndex.coerceIn(0, queue.lastIndex)
        val previousQueue = playback.currentQueueFlow.value
        val previousSong = playback.currentSongFlow.value
        val updateWithoutReload = shouldApplyListenTogetherQueueUpdateWithoutReload(
            causeType, previousQueue, previousSong, queue, targetIndex
        )
        if (updateWithoutReload) playback.applyRemoteQueueUpdate(queue, targetIndex)
        val synchronizedQueue = if (updateWithoutReload) playback.currentQueueFlow.value else previousQueue
        val localIndex = if (updateWithoutReload) targetIndex else synchronizedQueue.indexOfTrack(previousSong)
        return ListenTogetherQueueSyncSnapshot(
            queue, targetIndex, queue[targetIndex], previousSong,
            queueChanged = !synchronizedQueue.hasSameTrackSequenceAs(queue),
            indexChanged = localIndex != targetIndex
        )
    }
}
