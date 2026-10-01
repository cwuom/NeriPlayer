package moe.ouom.neriplayer.core.player.queue.policy.order

import moe.ouom.neriplayer.data.model.SongItem

internal data class PlayerQueueShuffleOrder(
    val queueIndices: List<Int>,
    val currentIndex: Int
)

internal data class PlayerQueueRestoreOrder(
    val playlist: List<SongItem>,
    val currentIndex: Int
)
