package moe.ouom.neriplayer.core.player.queue.model
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

internal data class PlayerQueueRestoreOrder(
    val playlist: List<SongItem>,
    val currentIndex: Int
)
