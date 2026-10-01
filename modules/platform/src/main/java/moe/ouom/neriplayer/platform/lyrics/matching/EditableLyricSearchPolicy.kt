package moe.ouom.neriplayer.platform.lyrics.matching

import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.platform.lyrics.search.toSimplifiedChineseForDomesticSearch

fun editableLyricMatchSearchQueries(request: EditableLyricMatchRequest): List<String> {
    val metadataQuery = listOf(request.trackName, request.artistName)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
    return listOf(request.keyword, metadataQuery, request.trackName)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::normalizeLyricMatchText)
}

fun editableLyricMatchDomesticSearchQueries(request: EditableLyricMatchRequest): List<String> {
    return editableLyricMatchSearchQueries(request)
        .map(::toSimplifiedChineseForDomesticSearch)
        .filter { it.isNotBlank() }
        .distinctBy(::normalizeLyricMatchText)
}

fun isLyricDetailLookupDurationAllowed(
    expectedDurationMs: Long,
    candidateDurationMs: Long
): Boolean {
    return expectedDurationMs <= 0L ||
        candidateDurationMs <= 0L ||
        isExternalLyricDurationCompatible(expectedDurationMs, candidateDurationMs)
}
