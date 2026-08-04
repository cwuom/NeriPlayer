package moe.ouom.neriplayer.core.api.lyrics

import kotlin.math.abs
import kotlin.math.max

private const val MIN_EXTERNAL_LYRIC_DURATION_TOLERANCE_MS = 7_000L
private const val MAX_EXTERNAL_LYRIC_DURATION_TOLERANCE_MS = 15_000L
private const val EXTERNAL_LYRIC_DURATION_TOLERANCE_PERCENT = 6L

internal fun isExternalLyricDurationCompatible(
    expectedDurationMs: Long,
    candidateDurationMs: Long
): Boolean {
    if (expectedDurationMs <= 0L || candidateDurationMs <= 0L) return false

    val toleranceMs = max(
        MIN_EXTERNAL_LYRIC_DURATION_TOLERANCE_MS,
        (expectedDurationMs * EXTERNAL_LYRIC_DURATION_TOLERANCE_PERCENT) / 100L
    ).coerceAtMost(MAX_EXTERNAL_LYRIC_DURATION_TOLERANCE_MS)
    return abs(expectedDurationMs - candidateDurationMs) <= toleranceMs
}
