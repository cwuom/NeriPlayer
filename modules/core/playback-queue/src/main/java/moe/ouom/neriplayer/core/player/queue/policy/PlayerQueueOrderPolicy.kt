package moe.ouom.neriplayer.core.player.queue.policy

import moe.ouom.neriplayer.core.player.queue.identity.QueueSongIdentity
import moe.ouom.neriplayer.core.player.queue.model.PlayerQueueDisplayItem
import moe.ouom.neriplayer.core.player.queue.model.PlayerQueueDisplayState
import moe.ouom.neriplayer.core.player.queue.model.PlayerQueueRestoreOrder
import moe.ouom.neriplayer.core.player.queue.model.PlayerQueueShuffleOrder
import moe.ouom.neriplayer.data.model.SongItem

fun buildPlayerQueueDisplayState(
    playlist: List<SongItem>,
    currentIndex: Int
): PlayerQueueDisplayState {
    if (playlist.isEmpty()) return PlayerQueueDisplayState.EMPTY
    val displayIndices = resolvePlayerQueueDisplayIndices(
        queueSize = playlist.size
    )
    return PlayerQueueDisplayState(
        items = displayIndices.map { index ->
            PlayerQueueDisplayItem(
                queueIndex = index,
                song = playlist[index]
            )
        },
        currentDisplayIndex = displayIndices.indexOf(currentIndex)
    )
}

internal fun resolvePlayerQueueDisplayIndices(
    queueSize: Int
): List<Int> {
    if (queueSize <= 0) return emptyList()
    return List(queueSize) { it }
}

internal fun resolvePlayerSequentialShuffleOrder(
    queueSize: Int,
    currentIndex: Int,
    shuffleRemaining: (MutableList<Int>) -> Unit = { it.shuffle() }
): PlayerQueueShuffleOrder {
    if (queueSize <= 0) {
        return PlayerQueueShuffleOrder(
            queueIndices = emptyList(),
            currentIndex = -1
        )
    }
    val resolvedCurrentIndex = currentIndex.takeIf { it in 0 until queueSize } ?: 0
    val remainingIndices = MutableList(queueSize) { it }
    remainingIndices.remove(resolvedCurrentIndex)
    shuffleRemaining(remainingIndices)
    return PlayerQueueShuffleOrder(
        queueIndices = listOf(resolvedCurrentIndex) + remainingIndices,
        currentIndex = 0
    )
}

internal fun resolvePlayerRepeatAllShuffleOrder(
    queueSize: Int,
    completedIndex: Int,
    shuffleQueue: (MutableList<Int>) -> Unit = { it.shuffle() }
): PlayerQueueShuffleOrder {
    if (queueSize <= 0) {
        return PlayerQueueShuffleOrder(
            queueIndices = emptyList(),
            currentIndex = -1
        )
    }
    val resolvedCompletedIndex = completedShuffleIndex(completedIndex, queueSize)
    val nextCycleIndices = MutableList(queueSize) { it }
    shuffleQueue(nextCycleIndices)
    avoidCompletedTrackAtStart(nextCycleIndices, resolvedCompletedIndex)
    changeUnchangedShuffle(nextCycleIndices, queueSize)
    return PlayerQueueShuffleOrder(
        queueIndices = nextCycleIndices,
        currentIndex = 0
    )
}

private fun completedShuffleIndex(completedIndex: Int, queueSize: Int): Int =
    completedIndex.takeIf { it in 0 until queueSize } ?: (queueSize - 1)

private fun avoidCompletedTrackAtStart(indices: MutableList<Int>, completedIndex: Int) {
    if (indices.firstOrNull() == completedIndex && indices.size > 1) {
        indices[0] = indices[1]
        indices[1] = completedIndex
    }
}

private fun changeUnchangedShuffle(indices: MutableList<Int>, queueSize: Int) {
    if (indices == List(queueSize) { it } && indices.size > 2) {
        val second = indices[1]
        indices[1] = indices[2]
        indices[2] = second
    }
}

internal fun resolvePlayerQueueRestoreOrder(
    restorePlaylist: List<SongItem>?,
    currentSong: SongItem?,
    fallbackIndex: Int,
    identity: QueueSongIdentity
): PlayerQueueRestoreOrder? {
    if (restorePlaylist.isNullOrEmpty()) return null
    val resolvedCurrentIndex = matchingRestoreIndex(restorePlaylist, currentSong, identity)
        ?: validRestoreFallback(restorePlaylist, fallbackIndex)
    return PlayerQueueRestoreOrder(
        playlist = restorePlaylist.toList(),
        currentIndex = resolvedCurrentIndex
    )
}

private fun matchingRestoreIndex(
    playlist: List<SongItem>,
    currentSong: SongItem?,
    identity: QueueSongIdentity
): Int? {
    val song = currentSong ?: return null
    return playlist.indexOfFirst { identity.sameIdentity(it, song) }.takeIf { it >= 0 }
}

private fun validRestoreFallback(playlist: List<SongItem>, fallbackIndex: Int): Int =
    fallbackIndex.takeIf { it in playlist.indices } ?: 0
