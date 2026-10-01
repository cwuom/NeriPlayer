package moe.ouom.neriplayer.data.model.playback.queue

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
