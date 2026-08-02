package moe.ouom.neriplayer.core.player.model

import moe.ouom.neriplayer.data.model.SongItem

data class PlayerQueueDisplayItem(
    val queueIndex: Int,
    val song: SongItem
)

data class PlayerQueueDisplayState(
    val items: List<PlayerQueueDisplayItem>,
    val currentDisplayIndex: Int
) {
    companion object {
        val EMPTY = PlayerQueueDisplayState(
            items = emptyList(),
            currentDisplayIndex = -1
        )
    }
}

internal data class PlayerQueueShuffleOrder(
    val queueIndices: List<Int>,
    val currentIndex: Int
)

internal fun buildPlayerQueueDisplayState(
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
