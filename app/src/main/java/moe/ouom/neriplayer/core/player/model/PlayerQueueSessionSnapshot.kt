package moe.ouom.neriplayer.core.player.model

internal data class PlayerQueueSessionSnapshot(
    val queue: PlayerQueueSnapshot = PlayerQueueSnapshot.EMPTY,
    val shuffleEnabled: Boolean = false,
    val shuffleRestore: PlayerQueueSnapshot? = null
)
