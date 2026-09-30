package moe.ouom.neriplayer.data.ltw.control

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherPlaybackCommandSnapshot

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
