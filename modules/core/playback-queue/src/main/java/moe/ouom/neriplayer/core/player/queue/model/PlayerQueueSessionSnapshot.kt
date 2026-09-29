package moe.ouom.neriplayer.core.player.queue.model

data class PlayerQueueSessionSnapshot(
    val queue: PlayerQueueSnapshot = PlayerQueueSnapshot.EMPTY,
    val shuffleEnabled: Boolean = false,
    val shuffleRestore: PlayerQueueSnapshot? = null
)
