package moe.ouom.neriplayer.core.player.queue.model

data class ListenTogetherTrackFinishPlan(
    val shouldAdvance: Boolean,
    val nextIndex: Int,
)

data class QueueNavigationStep(
    val index: Int,
    val reshuffleAtWrap: Boolean = false,
)

enum class QueueTrackCompletion {
    REPLAY_CURRENT,
    ADVANCE,
    WRAP,
    STOP,
}

data class RemotePlaybackModeUpdate(
    val repeatMode: Int?,
    val shuffleEnabled: Boolean?,
)
