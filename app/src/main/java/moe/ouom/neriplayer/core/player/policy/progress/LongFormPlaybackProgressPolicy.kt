package moe.ouom.neriplayer.core.player.policy.progress

internal const val LONG_FORM_PLAYBACK_MIN_DURATION_MS = 15L * 60L * 1000L
private const val LONG_FORM_PLAYBACK_MIN_RESUME_POSITION_MS = 5L * 1000L
private const val LONG_FORM_PLAYBACK_COMPLETION_TOLERANCE_MS = 30L * 1000L

internal fun resolveLongFormPlaybackResumePosition(
    enabled: Boolean,
    durationMs: Long,
    requestedPositionMs: Long,
    rememberedPositionMs: Long,
    allowRememberedPosition: Boolean = true
): Long {
    val normalizedRequestedPositionMs = requestedPositionMs.coerceAtLeast(0L)
    if (normalizedRequestedPositionMs > 0L) {
        return normalizedRequestedPositionMs
    }
    if (!allowRememberedPosition || !enabled || durationMs < LONG_FORM_PLAYBACK_MIN_DURATION_MS) {
        return 0L
    }

    val normalizedRememberedPositionMs = rememberedPositionMs.coerceAtLeast(0L)
    val latestResumePositionMs =
        (durationMs - LONG_FORM_PLAYBACK_COMPLETION_TOLERANCE_MS).coerceAtLeast(0L)
    return normalizedRememberedPositionMs.takeIf {
        it >= LONG_FORM_PLAYBACK_MIN_RESUME_POSITION_MS &&
            it < latestResumePositionMs
    } ?: 0L
}

internal fun resolveLongFormPlaybackPositionForPersistence(
    enabled: Boolean,
    durationMs: Long,
    positionMs: Long
): Long? {
    if (!enabled || durationMs < LONG_FORM_PLAYBACK_MIN_DURATION_MS) {
        return null
    }

    val normalizedPositionMs = positionMs.coerceAtLeast(0L)
    val latestResumePositionMs =
        (durationMs - LONG_FORM_PLAYBACK_COMPLETION_TOLERANCE_MS).coerceAtLeast(0L)
    if (normalizedPositionMs >= latestResumePositionMs) {
        return 0L
    }
    return normalizedPositionMs.takeIf {
        it >= LONG_FORM_PLAYBACK_MIN_RESUME_POSITION_MS
    }
}
