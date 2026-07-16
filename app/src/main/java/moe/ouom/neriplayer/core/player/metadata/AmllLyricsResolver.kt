package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.api.lyrics.AmllTtmlClient
import moe.ouom.neriplayer.core.api.lyrics.AmllTtmlLyrics
import moe.ouom.neriplayer.core.api.lyrics.isAmllDurationCompatible
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.hasWordTimedEntries
import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto
import kotlin.math.max

internal data class AmllResolvedLyrics(
    val rawLyrics: String,
    val entries: List<LyricEntry>
)

internal object AmllLyricsResolver {
    private const val MAX_SEARCH_CANDIDATES = 5

    suspend fun loadForSong(
        song: SongItem,
        amllTtmlClient: AmllTtmlClient,
        requireDurationMatch: Boolean
    ): List<LyricEntry> {
        return loadRawByMetadata(
            trackName = song.name,
            artistName = song.artist,
            durationMs = song.durationMs,
            amllTtmlClient = amllTtmlClient,
            requireDurationMatch = requireDurationMatch
        )?.entries.orEmpty()
    }

    suspend fun loadRawByMetadata(
        trackName: String,
        artistName: String,
        durationMs: Long,
        amllTtmlClient: AmllTtmlClient,
        requireDurationMatch: Boolean
    ): AmllResolvedLyrics? {
        val results = runCatching {
            amllTtmlClient.searchLyrics(trackName, artistName)
        }.onFailure { error ->
            NPLogger.d(
                "NERI-PlayerManager",
                "AMLL lyrics search failed: song='$trackName', artist='$artistName', error=${error.message}"
            )
        }.getOrDefault(emptyList())
            .take(MAX_SEARCH_CANDIDATES)
        if (results.isEmpty()) {
            NPLogger.d(
                "NERI-PlayerManager",
                "AMLL lyrics search returned no candidates: song='$trackName', artist='$artistName'"
            )
            return null
        }
        for (result in results) {
            val lyrics = runCatching {
                amllTtmlClient.getLyrics(result)
            }.onFailure { error ->
                NPLogger.d(
                    "NERI-PlayerManager",
                    "AMLL raw lyric request failed: song='$trackName', source=search:${result.file}, " +
                        "error=${error.message}"
                )
            }.getOrNull()
            if (lyrics == null) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "AMLL raw lyric unavailable: song='$trackName', source=search:${result.file}"
                )
                continue
            }
            val resolved = parseUsableLyrics(
                trackName = trackName,
                durationMs = durationMs,
                amllLyrics = lyrics,
                requireDurationMatch = requireDurationMatch,
                sourceLabel = "search:${result.file}"
            )
            if (resolved != null) {
                return resolved
            }
        }
        NPLogger.d(
            "NERI-PlayerManager",
            "AMLL lyrics search had no usable word-timed candidate: song='$trackName', artist='$artistName'"
        )
        return null
    }

    private fun parseUsableLyrics(
        trackName: String,
        durationMs: Long,
        amllLyrics: AmllTtmlLyrics,
        requireDurationMatch: Boolean,
        sourceLabel: String
    ): AmllResolvedLyrics? {
        val entries = parseLyricsOrEmpty(amllLyrics.lyrics)
        if (!entries.hasWordTimedEntries()) {
            NPLogger.d(
                "NERI-PlayerManager",
                "AMLL lyrics skipped without word timing: song='$trackName', source=$sourceLabel"
            )
            return null
        }
        if (!isDurationAccepted(durationMs, entries, requireDurationMatch)) {
            NPLogger.d(
                "NERI-PlayerManager",
                "AMLL lyrics skipped by duration: song='$trackName', " +
                    "source=$sourceLabel, expected=$durationMs, " +
                    "candidate=${entries.estimatedDurationMs()}"
            )
            return null
        }
        NPLogger.d(
            "NERI-PlayerManager",
            "Using AMLL TTML lyrics for '$trackName' from $sourceLabel"
        )
        return AmllResolvedLyrics(
            rawLyrics = amllLyrics.lyrics,
            entries = entries
        )
    }

    private fun parseLyricsOrEmpty(rawLyric: String): List<LyricEntry> {
        if (rawLyric.isBlank()) {
            return emptyList()
        }
        return try {
            parseNeteaseLyricsAuto(rawLyric)
        } catch (error: Exception) {
            NPLogger.w("NERI-PlayerManager", "AMLL 歌词解析失败: ${error.message}")
            emptyList()
        }
    }

    private fun isDurationAccepted(
        expectedDurationMs: Long,
        entries: List<LyricEntry>,
        requireDurationMatch: Boolean
    ): Boolean {
        val candidateDurationMs = entries.estimatedDurationMs()
        if (expectedDurationMs <= 0L || candidateDurationMs <= 0L) {
            return !requireDurationMatch
        }
        return isAmllDurationCompatible(expectedDurationMs, candidateDurationMs)
    }

    private fun List<LyricEntry>.estimatedDurationMs(): Long {
        return maxOfOrNull { max(it.endTimeMs, it.startTimeMs) } ?: 0L
    }
}
