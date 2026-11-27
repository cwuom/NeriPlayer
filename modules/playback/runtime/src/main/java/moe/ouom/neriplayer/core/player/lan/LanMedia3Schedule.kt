package moe.ouom.neriplayer.core.player.lan

import kotlin.math.abs
import kotlin.math.roundToLong

internal data class LanMedia3StartPlan(val deadlineMs: Long, val positionMs: Long)

private const val LATE_SEEK_LEAD_MS = 250L
private const val PLAYABLE_TAIL_MS = 100L

internal fun clampLanMediaPosition(positionMs: Long, durationMs: Long?): Long {
    val lastPlayable = durationMs?.let { (it - PLAYABLE_TAIL_MS).coerceAtLeast(0) } ?: Long.MAX_VALUE
    return positionMs.coerceIn(0, lastPlayable)
}

internal fun planLanMedia3Start(
    nowMs: Long,
    serverDeadlineMs: Double,
    clockOffsetMs: Double,
    nudgeMs: Int,
    positionSeconds: Double,
    durationMs: Long?,
): LanMedia3StartPlan? {
    val targetMs = (serverDeadlineMs - clockOffsetMs - nudgeMs).roundToLong()
    val deadlineMs = if (targetMs >= nowMs) targetMs else nowMs + LATE_SEEK_LEAD_MS
    val requestedMs = (positionSeconds * 1000).roundToLong().coerceAtLeast(0L)
    if (durationMs != null && requestedMs >= durationMs) {
        return LanMedia3StartPlan(deadlineMs, clampLanMediaPosition(requestedMs, durationMs))
    }
    val positionMs = requestedMs + (deadlineMs - targetMs)
    if (durationMs != null && positionMs >= durationMs) return null
    return LanMedia3StartPlan(deadlineMs, clampLanMediaPosition(positionMs, durationMs))
}

private const val MIN_CATALOG_LENGTH_FOR_PREVIEW_CHECK_MS = 180_000L
private const val PREVIEW_LENGTH_TOLERANCE_MS = 750L

internal fun isLanDefinitePreview(channelId: String, catalogMs: Long, seekableStreamMs: Long?): Boolean {
    if (channelId != "netease" || catalogMs < MIN_CATALOG_LENGTH_FOR_PREVIEW_CHECK_MS || seekableStreamMs == null) return false
    return abs(seekableStreamMs - 30_000L) <= PREVIEW_LENGTH_TOLERANCE_MS ||
        abs(seekableStreamMs - 60_000L) <= PREVIEW_LENGTH_TOLERANCE_MS
}
