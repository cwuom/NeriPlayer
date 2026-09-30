package moe.ouom.neriplayer.core.player

import androidx.media3.common.Player

internal val Player.currentPositionMsOrZero: Long
    get() = currentPositionMsOr(0L)

internal val Player.durationMsOrZero: Long
    get() = durationMsOr(0L)

internal fun Player.currentPositionMsOr(fallbackMs: Long): Long =
    runCatching { currentPosition.coerceAtLeast(0L) }.getOrDefault(fallbackMs)

internal fun Player.durationMsOr(fallbackMs: Long): Long =
    runCatching { duration.coerceAtLeast(0L) }.getOrDefault(fallbackMs)
