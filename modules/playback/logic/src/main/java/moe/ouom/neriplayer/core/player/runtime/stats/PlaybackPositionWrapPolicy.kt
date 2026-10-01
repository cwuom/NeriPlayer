package moe.ouom.neriplayer.core.player.runtime.stats

private const val PLAYBACK_POSITION_WRAP_TOLERANCE_MS = 3_000L

internal fun hasPlaybackPositionWrapped(
    previousPositionMs: Long?,
    positionMs: Long,
    durationMs: Long
): Boolean {
    if (durationMs <= 0L || previousPositionMs == null || previousPositionMs <= positionMs) return false
    val nearEnd = previousPositionMs >=
        (durationMs - PLAYBACK_POSITION_WRAP_TOLERANCE_MS).coerceAtLeast(0L)
    val nearStart = positionMs <= PLAYBACK_POSITION_WRAP_TOLERANCE_MS
    return nearEnd && nearStart
}
