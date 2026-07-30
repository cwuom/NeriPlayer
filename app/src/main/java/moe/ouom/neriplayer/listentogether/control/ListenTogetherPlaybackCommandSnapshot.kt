package moe.ouom.neriplayer.listentogether.control

internal data class ListenTogetherPlaybackCommandSnapshot<T>(
    val queue: List<T>,
    val positionMs: Long
)

internal fun <T> resolveListenTogetherPlaybackCommandSnapshot(
    commandQueue: List<T>?,
    commandPositionMs: Long?,
    currentQueue: List<T>,
    currentPositionMs: Long
): ListenTogetherPlaybackCommandSnapshot<T> {
    return ListenTogetherPlaybackCommandSnapshot(
        queue = commandQueue?.takeIf { it.isNotEmpty() } ?: currentQueue,
        positionMs = (commandPositionMs ?: currentPositionMs).coerceAtLeast(0L)
    )
}
