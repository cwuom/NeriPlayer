package moe.ouom.neriplayer.platform.lyrics.repository

import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.lyrics.api.client.LrcLibClient
import moe.ouom.neriplayer.data.model.lyrics.lrclib.LrcLibRecord
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.platform.lyrics.matching.extractPlainLyricsFromCollapsedTimedLyrics
import moe.ouom.neriplayer.platform.lyrics.matching.isExternalLyricDurationCompatible
import moe.ouom.neriplayer.platform.lyrics.matching.isReliableLyricMatchIdentity
import moe.ouom.neriplayer.platform.lyrics.matching.isUsableTimedLyricTimeline
import moe.ouom.neriplayer.data.model.lyrics.lrclib.LrcLibResult

class LrcLibLyricsRepository(private val client: LrcLibClient) {
    suspend fun getLyrics(
        trackName: String,
        artistName: String,
        durationSeconds: Long
    ): LrcLibResult? = withContext(Dispatchers.IO) {
        if (durationSeconds <= 0L) return@withContext null
        try {
            for ((lookupTrackName, lookupArtistName) in
                lrcLibLookupVariants(trackName, artistName).take(MAX_GET_LOOKUP_VARIANTS)
            ) {
                val result = client.getLyrics(lookupTrackName, lookupArtistName, durationSeconds)
                    ?.let(::normalizeLrcLibResult)
                if (result != null && isLrcLibResultCompatible(result, trackName, artistName, durationSeconds)) {
                    return@withContext result
                }
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "LRCLIB getLyrics failed: ${error.message}")
            null
        }
    }

    suspend fun searchLyrics(
        trackName: String,
        artistName: String,
        durationSeconds: Long
    ): LrcLibResult? = withContext(Dispatchers.IO) {
        if (durationSeconds <= 0L) return@withContext null
        try {
            for (query in lrcLibLookupQueries(trackName, artistName).take(MAX_SEARCH_LOOKUP_QUERIES)) {
                client.searchLyrics(query)
                    .mapNotNull(::normalizeLrcLibResult)
                    .filter { isLrcLibResultCompatible(it, trackName, artistName, durationSeconds) }
                    .sortedWith(
                        compareByDescending<LrcLibResult> { !it.syncedLyrics.isNullOrBlank() }
                            .thenBy { absDurationDeltaSeconds(it.durationSeconds, durationSeconds) }
                    ).firstOrNull()?.let { return@withContext it }
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "LRCLIB searchLyrics failed: ${error.message}")
            null
        }
    }

    suspend fun searchLyricsCandidates(keyword: String): List<LrcLibResult> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        try {
            client.searchLyrics(keyword).mapNotNull(::normalizeLrcLibResult)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "LRCLIB searchLyricsCandidates failed: ${error.message}")
            emptyList()
        }
    }

    private companion object {
        const val TAG = "LrcLibLyricsRepository"
        const val MAX_GET_LOOKUP_VARIANTS = 1
        const val MAX_SEARCH_LOOKUP_QUERIES = 2
    }
}

private fun normalizeLrcLibResult(record: LrcLibRecord): LrcLibResult? {
    val rawSyncedLyrics = record.syncedLyrics
    val syncedLyrics = rawSyncedLyrics?.takeIf(::isUsableTimedLyricTimeline)
    val recoveredPlainLyrics = recoverMissingLrcLibPlainLyrics(record)
    val plainLyrics = record.plainLyrics ?: recoveredPlainLyrics
    if (syncedLyrics == null && plainLyrics == null) return null
    return LrcLibResult(
        syncedLyrics = syncedLyrics,
        plainLyrics = plainLyrics,
        trackName = record.trackName,
        artistName = record.artistName,
        durationSeconds = record.durationSeconds,
        plainLyricsRecoveredFromCollapsedTimeline = recoveredPlainLyrics != null
    )
}

private fun recoverMissingLrcLibPlainLyrics(record: LrcLibRecord): String? {
    if (record.plainLyrics != null) return null
    return record.syncedLyrics?.let(::extractPlainLyricsFromCollapsedTimedLyrics)
}

internal fun isLrcLibResultCompatible(
    result: LrcLibResult,
    trackName: String,
    artistName: String,
    durationSeconds: Long
): Boolean {
    val candidateDurationSeconds = result.durationSeconds ?: return false
    return isReliableLyricMatchIdentity(
        expectedTitle = trackName,
        expectedArtist = artistName,
        candidateTitle = result.trackName,
        candidateArtist = result.artistName
    ) &&
        isExternalLyricDurationCompatible(
            expectedDurationMs = durationSeconds * 1_000L,
            candidateDurationMs = candidateDurationSeconds * 1_000L
        )
}

private fun lrcLibLookupVariants(trackName: String, artistName: String): List<Pair<String, String>> {
    val cleanedTrackName = cleanLrcLibTrackName(trackName)
    val cleanedArtistName = cleanLrcLibArtistName(artistName)
    return listOf(
        cleanedTrackName to cleanedArtistName,
        trackName.trim() to artistName.trim()
    ).filter(::hasLrcLibLookupIdentity)
        .distinctBy { (track, artist) ->
            "${normalizeLrcLibMatchText(track)}|${normalizeLrcLibMatchText(artist)}"
        }
}

private fun hasLrcLibLookupIdentity(identity: Pair<String, String>): Boolean {
    val (track, artist) = identity
    return track.isNotBlank() && artist.isNotBlank()
}

private fun lrcLibLookupQueries(trackName: String, artistName: String): List<String> {
    val variants = lrcLibLookupVariants(trackName, artistName)
    return (variants.map { (track, artist) -> "$track $artist" } +
        variants.map { it.first } +
        listOf(trackName.trim()))
        .filter(String::isNotBlank)
        .distinctBy(::normalizeLrcLibMatchText)
}

private fun cleanLrcLibTrackName(value: String): String {
    return value.trim()
        .replace(
            Regex(
                """\s*(?:\(.*?(?:official|video|audio|lyrics?|visualizer|hd|hq|4k).*?\)|\[.*?(?:official|video|audio|lyrics?|visualizer|hd|hq|4k).*?\]|【.*?】)""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        .replace(
            Regex("\\s*-\\s*(official|video|audio|lyrics?)$", RegexOption.IGNORE_CASE),
            ""
        )
        .trim()
}

private fun cleanLrcLibArtistName(value: String): String {
    return value.trim().split(
        Regex(
            "\\s+(?:feat\\.?|ft\\.?|featuring|with)\\s+|\\s+[xX]\\s+|\\s*&\\s*|\\s+and\\s+",
            RegexOption.IGNORE_CASE
        ),
        limit = 2
    ).firstOrNull().orEmpty().trim()
}

private fun normalizeLrcLibMatchText(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

private fun absDurationDeltaSeconds(candidateDuration: Long?, expectedDuration: Long): Long {
    return candidateDuration?.let { kotlin.math.abs(it - expectedDuration) } ?: Long.MAX_VALUE
}
