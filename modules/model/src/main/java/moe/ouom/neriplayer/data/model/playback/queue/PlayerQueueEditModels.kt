package moe.ouom.neriplayer.data.model.playback.queue

import moe.ouom.neriplayer.data.model.SongItem

data class ReplaceCurrentQueueEdit(
    val queue: PlayerQueueSnapshot,
    val existingIndex: Int,
)

data class RemoveQueueEdit(
    val queue: PlayerQueueSnapshot,
    val removedSong: SongItem,
    val removedCurrent: Boolean,
)

enum class RemovedQueuePlaybackAction {
    KEEP_PLAYING,
    PLAY_NEXT,
    STOP,
}

data class InsertQueueEdit(
    val queue: PlayerQueueSnapshot,
    val existingIndex: Int,
    val insertedIndex: Int,
)

enum class QueueInsertPlacement(val logName: String) {
    NEXT("addToQueueNext"),
    END("addToQueueEnd"),
}
