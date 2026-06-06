package moe.ouom.neriplayer.ui.component

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TranslationAlignmentToleranceMs = 450L
private const val TranslationClosestMatchToleranceMs = 1000L

internal fun matchTranslationsToLineIndices(
    lines: List<LyricEntry>,
    translations: List<LyricEntry>,
    toleranceMs: Long = TranslationAlignmentToleranceMs
): Map<Int, LyricEntry> {
    if (lines.isEmpty() || translations.isEmpty()) {
        return emptyMap()
    }

    val matchesByIndex = linkedMapOf<Int, LyricEntry>()
    var translationIndex = 0

    lines.forEachIndexed { lineIndex, line ->
        while (translationIndex < translations.size) {
            val translation = translations[translationIndex]
            val currentDistanceMs = calculateStartDistanceToLineMs(
                timestampMs = translation.startTimeMs,
                lineStartMs = line.startTimeMs,
                lineEndMs = line.endTimeMs
            )
            val nextDistanceMs = lines.getOrNull(lineIndex + 1)?.let { nextLine ->
                calculateStartDistanceToLineMs(
                    timestampMs = translation.startTimeMs,
                    lineStartMs = nextLine.startTimeMs,
                    lineEndMs = nextLine.endTimeMs
                )
            } ?: Long.MAX_VALUE

            // 翻译远落后于当前行（超过宽松容差）时才丢弃
            val shouldSkipStaleTranslation = translation.startTimeMs < line.startTimeMs &&
                currentDistanceMs > TranslationClosestMatchToleranceMs
            if (shouldSkipStaleTranslation) {
                translationIndex++
                continue
            }

            // 严格匹配：在容差内且离当前行最近
            // 宽松匹配：翻译在当前行之前但在宽松容差内，且离当前行比下一行近
            val shouldMatchCurrentLine =
                (currentDistanceMs <= toleranceMs && currentDistanceMs <= nextDistanceMs) ||
                (currentDistanceMs <= TranslationClosestMatchToleranceMs && currentDistanceMs <= nextDistanceMs)
            if (shouldMatchCurrentLine) {
                matchesByIndex[lineIndex] = translation
                translationIndex++
            }
            break
        }
    }

    return matchesByIndex
}

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

private fun calculateStartDistanceToLineMs(
    timestampMs: Long,
    lineStartMs: Long,
    lineEndMs: Long
): Long {
    val normalizedLineEndMs = normalizeExclusiveEndTime(lineStartMs, lineEndMs)
    return when {
        timestampMs < lineStartMs -> lineStartMs - timestampMs
        timestampMs >= normalizedLineEndMs -> timestampMs - normalizedLineEndMs + 1L
        else -> 0L
    }
}

private fun normalizeExclusiveEndTime(startMs: Long, endMs: Long): Long {
    return if (endMs > startMs) endMs else startMs + 1L
}
