package moe.ouom.neriplayer.ui.component

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal fun findBestMatchingTranslation(
    translations: List<LyricEntry>,
    lineStartMs: Long,
    lineEndMs: Long,
    toleranceMs: Long = 1_500L
): LyricEntry? {
    if (translations.isEmpty()) {
        return null
    }

    val normalizedLineEndMs = normalizeExclusiveEndTime(lineStartMs, lineEndMs)
    var bestOverlappingTranslation: LyricEntry? = null
    var bestOverlapMs = 0L
    var bestStartDeltaMs = Long.MAX_VALUE

    // 优先按时间区间重叠匹配，只有完全不重叠时才退回到“最近开始时间”
    translations.forEach { candidate ->
        val candidateEndMs = normalizeExclusiveEndTime(
            candidate.startTimeMs,
            candidate.endTimeMs
        )
        val overlapMs = calculateIntervalOverlapMs(
            firstStartMs = lineStartMs,
            firstEndMs = normalizedLineEndMs,
            secondStartMs = candidate.startTimeMs,
            secondEndMs = candidateEndMs
        )
        if (overlapMs <= 0L) {
            return@forEach
        }

        val startDeltaMs = abs(candidate.startTimeMs - lineStartMs)
        val shouldReplaceBest = overlapMs > bestOverlapMs ||
            (overlapMs == bestOverlapMs && startDeltaMs < bestStartDeltaMs) ||
            (
                overlapMs == bestOverlapMs &&
                    startDeltaMs == bestStartDeltaMs &&
                    candidate.startTimeMs < (bestOverlappingTranslation?.startTimeMs ?: Long.MAX_VALUE)
                )
        if (shouldReplaceBest) {
            bestOverlappingTranslation = candidate
            bestOverlapMs = overlapMs
            bestStartDeltaMs = startDeltaMs
        }
    }

    if (bestOverlappingTranslation != null) {
        return bestOverlappingTranslation
    }

    val nearestTranslation = translations.minWithOrNull(
        compareBy<LyricEntry> { abs(it.startTimeMs - lineStartMs) }
            .thenBy { it.startTimeMs }
    )
    return nearestTranslation?.takeIf {
        abs(it.startTimeMs - lineStartMs) <= toleranceMs
    }
}

private fun calculateIntervalOverlapMs(
    firstStartMs: Long,
    firstEndMs: Long,
    secondStartMs: Long,
    secondEndMs: Long
): Long {
    return min(firstEndMs, secondEndMs) - max(firstStartMs, secondStartMs)
}

private fun normalizeExclusiveEndTime(startMs: Long, endMs: Long): Long {
    return if (endMs > startMs) endMs else startMs + 1L
}
