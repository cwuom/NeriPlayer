package moe.ouom.neriplayer.core.player.queue.policy

import moe.ouom.neriplayer.core.player.queue.model.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.queue.model.reorderQueueSongsPreservingLatestMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs

internal data class ReplaceCurrentQueueEdit(
    val queue: PlayerQueueSnapshot,
    val existingIndex: Int,
)

internal data class RemoveQueueEdit(
    val queue: PlayerQueueSnapshot,
    val removedSong: SongItem,
    val removedCurrent: Boolean,
)

internal enum class RemovedQueuePlaybackAction {
    KEEP_PLAYING,
    PLAY_NEXT,
    STOP,
}

internal data class InsertQueueEdit(
    val queue: PlayerQueueSnapshot,
    val existingIndex: Int,
    val insertedIndex: Int,
)

internal enum class QueueInsertPlacement(val logName: String) {
    NEXT("addToQueueNext"),
    END("addToQueueEnd"),
}

internal object PlayerQueueEditOwner {
    fun insert(
        queue: PlayerQueueSnapshot,
        song: SongItem,
        currentSong: SongItem?,
        placement: QueueInsertPlacement,
    ): InsertQueueEdit? = when (placement) {
        QueueInsertPlacement.NEXT -> insertNext(queue, song, currentSong)
        QueueInsertPlacement.END -> insertEnd(queue, song, currentSong)
    }

    fun replaceCurrent(queue: PlayerQueueSnapshot, song: SongItem): ReplaceCurrentQueueEdit? {
        val oldIndex = queue.currentIndex
        if (oldIndex !in queue.playlist.indices) return null
        val songs = queue.playlist.toMutableList()
        val existingIndex = songs.indexOfFirst { it.sameIdentityAs(song) }
        val targetIndex = if (existingIndex >= 0 && existingIndex != oldIndex) {
            songs.removeAt(existingIndex)
            if (existingIndex < oldIndex) oldIndex - 1 else oldIndex
        } else {
            oldIndex
        }
        songs[targetIndex] = song
        return ReplaceCurrentQueueEdit(PlayerQueueSnapshot.from(songs, targetIndex), existingIndex)
    }

    fun move(queue: PlayerQueueSnapshot, fromIndex: Int, toIndex: Int): PlayerQueueSnapshot? {
        if (!canMove(queue.playlist.size, fromIndex, toIndex)) return null
        val songs = queue.playlist.toMutableList()
        songs.add(toIndex, songs.removeAt(fromIndex))
        return PlayerQueueSnapshot.from(
            songs,
            resolveQueueCurrentIndexAfterMove(queue.currentIndex, fromIndex, toIndex, songs.size),
        )
    }

    private fun canMove(queueSize: Int, fromIndex: Int, toIndex: Int): Boolean =
        queueSize > 1 && validMoveEndpoints(fromIndex, toIndex, queueSize) && fromIndex != toIndex

    fun remove(queue: PlayerQueueSnapshot, index: Int): RemoveQueueEdit? {
        if (index !in queue.playlist.indices) return null
        val songs = queue.playlist.toMutableList()
        val removedSong = songs.removeAt(index)
        val updated = PlayerQueueSnapshot.from(
            songs,
            resolveQueueCurrentIndexAfterRemoval(queue.currentIndex, index, queue.playlist.size),
        )
        return RemoveQueueEdit(updated, removedSong, index == queue.currentIndex)
    }

    fun removalPlaybackAction(
        edit: RemoveQueueEdit,
        transportActive: Boolean,
    ): RemovedQueuePlaybackAction = when {
        edit.queue.playlist.isEmpty() -> RemovedQueuePlaybackAction.STOP
        !edit.removedCurrent -> RemovedQueuePlaybackAction.KEEP_PLAYING
        transportActive -> RemovedQueuePlaybackAction.PLAY_NEXT
        else -> RemovedQueuePlaybackAction.STOP
    }

    fun reorder(
        current: PlayerQueueSnapshot,
        requestedQueue: List<SongItem>,
        requestedIndex: Int,
        currentSong: SongItem?,
    ): PlayerQueueSnapshot? {
        val songs = reorderQueueSongsPreservingLatestMetadata(
            currentQueue = current.playlist,
            requestedQueue = requestedQueue,
        ) ?: return null
        val selectedIndex = resolveQueueCurrentIndexAfterReorder(
            queue = songs,
            currentSong = currentSong ?: current.playlist.getOrNull(current.currentIndex),
            submittedCurrentIndex = requestedIndex,
            fallbackCurrentIndex = current.currentIndex,
        )
        return PlayerQueueSnapshot.from(songs, selectedIndex)
    }

    fun insertNext(
        queue: PlayerQueueSnapshot,
        song: SongItem,
        currentSong: SongItem?,
    ): InsertQueueEdit? {
        if (queue.playlist.isEmpty()) return null
        val songs = queue.playlist.toMutableList()
        val existingIndex = songs.indexOfFirst { it.sameIdentityAs(song) }
        val desiredIndex = (queue.currentIndex + 1).coerceIn(0, songs.size + 1)
        if (existingIndex >= 0) songs.removeAt(existingIndex)
        val insertIndex = (desiredIndex - if (existingIndex in 0 until desiredIndex) 1 else 0)
            .coerceIn(0, songs.size)
        songs.add(insertIndex, song)
        return InsertQueueEdit(selectedQueue(songs, queue.currentIndex, currentSong), existingIndex, insertIndex)
    }

    fun insertEnd(
        queue: PlayerQueueSnapshot,
        song: SongItem,
        currentSong: SongItem?,
    ): InsertQueueEdit? {
        if (queue.playlist.isEmpty()) return null
        val songs = queue.playlist.toMutableList()
        val existingIndex = songs.indexOfFirst { it.sameIdentityAs(song) }
        if (existingIndex >= 0) songs.removeAt(existingIndex)
        val insertIndex = songs.size
        songs.add(song)
        return InsertQueueEdit(selectedQueue(songs, queue.currentIndex, currentSong), existingIndex, insertIndex)
    }

    private fun selectedQueue(
        songs: List<SongItem>,
        previousIndex: Int,
        currentSong: SongItem?,
    ): PlayerQueueSnapshot {
        val matchedIndex = currentSong?.let { current ->
            songs.indexOfFirst { it.sameIdentityAs(current) }.takeIf { it >= 0 }
        }
        return PlayerQueueSnapshot.from(
            songs,
            matchedIndex ?: previousIndex.coerceIn(0, songs.lastIndex),
        )
    }

    fun remote(queue: List<SongItem>, index: Int): PlayerQueueSnapshot =
        PlayerQueueSnapshot.from(
            queue,
            if (queue.isEmpty()) -1 else index.coerceIn(queue.indices),
        )
}

internal fun resolveQueueCurrentIndexAfterMove(
    currentIndex: Int,
    fromIndex: Int,
    toIndex: Int,
    queueSize: Int,
): Int {
    if (!validQueueIndex(currentIndex, queueSize)) return -1
    if (!validMoveEndpoints(fromIndex, toIndex, queueSize)) return currentIndex
    if (fromIndex == toIndex) return currentIndex
    return movedCurrentIndex(currentIndex, fromIndex, toIndex)
}

private fun validQueueIndex(index: Int, queueSize: Int): Boolean =
    queueSize > 0 && index in 0 until queueSize

private fun validMoveEndpoints(fromIndex: Int, toIndex: Int, queueSize: Int): Boolean =
    validQueueIndex(fromIndex, queueSize) && validQueueIndex(toIndex, queueSize)

private fun movedCurrentIndex(currentIndex: Int, fromIndex: Int, toIndex: Int): Int = when {
    currentIndex == fromIndex -> toIndex
    fromIndex < currentIndex && currentIndex <= toIndex -> currentIndex - 1
    toIndex <= currentIndex && currentIndex < fromIndex -> currentIndex + 1
    else -> currentIndex
}

internal fun resolveQueueCurrentIndexAfterRemoval(
    currentIndex: Int,
    removedIndex: Int,
    queueSize: Int,
): Int {
    if (!validQueueIndex(currentIndex, queueSize)) return -1
    if (!validQueueIndex(removedIndex, queueSize)) return currentIndex
    val newSize = queueSize - 1
    if (newSize <= 0) return -1
    return removedCurrentIndex(currentIndex, removedIndex, newSize)
}

private fun removedCurrentIndex(currentIndex: Int, removedIndex: Int, newSize: Int): Int = when {
    currentIndex == removedIndex -> removedIndex.coerceAtMost(newSize - 1)
    removedIndex < currentIndex -> currentIndex - 1
    else -> currentIndex
}

internal fun resolveQueueCurrentIndexAfterReorder(
    queue: List<SongItem>,
    currentSong: SongItem?,
    submittedCurrentIndex: Int,
    fallbackCurrentIndex: Int,
): Int {
    if (queue.isEmpty()) return -1
    matchingCurrentIndex(queue, currentSong, submittedCurrentIndex)?.let { return it }
    return submittedCurrentIndex.takeIf { it in queue.indices }
        ?: fallbackCurrentIndex.coerceIn(queue.indices)
}

private fun matchingCurrentIndex(
    queue: List<SongItem>,
    currentSong: SongItem?,
    submittedIndex: Int,
): Int? {
    val song = currentSong ?: return null
    if (submittedIndex in queue.indices && queue[submittedIndex].sameIdentityAs(song)) {
        return submittedIndex
    }
    return queue.indexOfFirst { it.sameIdentityAs(song) }.takeIf { it >= 0 }
}
