package moe.ouom.neriplayer.data.lyrics.matching

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import moe.ouom.neriplayer.api.lyrics.model.AmllTtmlSearchResult

private const val MIN_AMLL_ARTIST_MATCH_SCORE = 30

fun scoreAmllSearchResult(
    trackName: String,
    artistName: String,
    result: AmllTtmlSearchResult
): Int {
    val requestedTitle = normalizeAmllSearchText(trackName)
    val requestedArtists = splitAmllArtists(artistName)
    if (requestedTitle.isBlank()) return 0

    val titleScore = result.titles
        .ifEmpty { listOf(result.title) }
        .maxOfOrNull { candidate ->
            val normalized = normalizeAmllSearchText(candidate)
            when {
                normalized.isBlank() -> 0
                normalized == requestedTitle -> 90
                normalized.startsWith("$requestedTitle ") -> 78
                normalized.contains(requestedTitle) || requestedTitle.contains(normalized) -> 64
                else -> tokenOverlapScore(requestedTitle, normalized) * 6
            }
        } ?: 0

    val artistScore = if (requestedArtists.isEmpty()) {
        0
    } else {
        result.artists
            .ifEmpty { listOf(result.artist) }
            .maxOfOrNull { candidate ->
                val normalized = normalizeAmllSearchText(candidate)
                requestedArtists.maxOf { requested ->
                    when {
                        normalized.isBlank() -> 0
                        normalized == requested -> 55
                        normalized.contains(requested) || requested.contains(normalized) -> 40
                        else -> tokenOverlapScore(requested, normalized) * 8
                    }
                }
            } ?: 0
    }

    if (requestedArtists.isNotEmpty() && artistScore < MIN_AMLL_ARTIST_MATCH_SCORE) {
        return 0
    }
    return titleScore + artistScore
}

fun isAmllDurationCompatible(
    expectedDurationMs: Long,
    candidateDurationMs: Long
): Boolean {
    if (expectedDurationMs <= 0L || candidateDurationMs <= 0L) return true
    val deltaMs = candidateDurationMs - expectedDurationMs
    // TTML 末行常早于音频结束，片尾空白和 outro 要比超长候选更宽容
    val toleranceMs = if (deltaMs < 0L) {
        max(30_000L, (expectedDurationMs * 15L) / 100L).coerceAtMost(60_000L)
    } else {
        max(12_000L, expectedDurationMs / 10L).coerceAtMost(30_000L)
    }
    return abs(deltaMs) <= toleranceMs
}

internal fun normalizeAmllSearchText(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("""\b(feat|ft|featuring)\.?\b"""), " ")
        .replace(Regex("""[(){}\[\]【】（）]"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
}

private fun splitAmllArtists(value: String): List<String> {
    return value.split(Regex("""[/,，、&+]|(?:\s+x\s+)""", RegexOption.IGNORE_CASE))
        .map(::normalizeAmllSearchText)
        .filter { it.isNotBlank() }
}

private fun tokenOverlapScore(left: String, right: String): Int {
    val leftTokens = left.split(' ').filter { it.isNotBlank() }.toSet()
    val rightTokens = right.split(' ').filter { it.isNotBlank() }.toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0
    return leftTokens.intersect(rightTokens).size
}
