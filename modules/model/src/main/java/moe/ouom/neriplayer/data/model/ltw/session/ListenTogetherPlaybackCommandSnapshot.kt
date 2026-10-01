package moe.ouom.neriplayer.data.model.ltw.session

data class ListenTogetherPlaybackCommandSnapshot<T>(
    val queue: List<T>,
    val positionMs: Long
)
