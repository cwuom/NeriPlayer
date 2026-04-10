package moe.ouom.neriplayer.ui.component

import kotlin.math.abs

private const val TranslationAlignmentToleranceMs = 450L

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

            val shouldSkipStaleTranslation = translation.startTimeMs < line.startTimeMs &&
                currentDistanceMs > toleranceMs
            if (shouldSkipStaleTranslation) {
                translationIndex++
                continue
            }

            val shouldMatchCurrentLine = currentDistanceMs <= toleranceMs &&
                currentDistanceMs <= nextDistanceMs
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
    toleranceMs: Long = TranslationAlignmentToleranceMs
): LyricEntry? {
    if (translations.isEmpty()) {
        return null
    }

    return translations
        .minWithOrNull(
            compareBy<LyricEntry> {
                calculateStartDistanceToLineMs(
                    timestampMs = it.startTimeMs,
                    lineStartMs = lineStartMs,
                    lineEndMs = lineEndMs
                )
            }.thenBy { abs(it.startTimeMs - lineStartMs) }
                .thenBy { it.startTimeMs }
        )
        ?.takeIf { candidate ->
            calculateStartDistanceToLineMs(
                timestampMs = candidate.startTimeMs,
                lineStartMs = lineStartMs,
                lineEndMs = lineEndMs
            ) <= toleranceMs
        }
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
