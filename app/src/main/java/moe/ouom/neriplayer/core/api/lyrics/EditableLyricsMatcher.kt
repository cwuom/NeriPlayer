package moe.ouom.neriplayer.core.api.lyrics

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.QQMusicSearchApi
import moe.ouom.neriplayer.core.api.search.SearchApi
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicClient
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicSearchResult
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.metadata.convertPlainLyricsToEntries
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.ui.component.lyrics.toEditableLyricsText

private const val TAG = "EditableLyricsMatcher"
private const val MAX_SOURCE_RESULTS = 5
private const val MAX_DETAIL_RESULTS = 2
private const val MAX_RESULTS = 20
private val matchSourceOrder = listOf(
    EditableLyricMatchSource.AMLL_TTML,
    EditableLyricMatchSource.CLOUD_MUSIC,
    EditableLyricMatchSource.KUGOU,
    EditableLyricMatchSource.QQ_MUSIC,
    EditableLyricMatchSource.LRCLIB,
    EditableLyricMatchSource.YOUTUBE_MUSIC
)

class EditableLyricsMatcher(
    private val cloudMusicSearchApi: SearchApi,
    private val qqMusicSearchApi: SearchApi,
    private val kugouLyricsClient: KugouLyricsClient,
    private val lrcLibClient: LrcLibClient,
    private val amllTtmlClient: AmllTtmlClient,
    private val youtubeMusicClient: YouTubeMusicClient
) {
    suspend fun matchLyrics(request: EditableLyricMatchRequest): List<RankedEditableLyricMatch> {
        val normalizedRequest = request.copy(
            keyword = request.keyword.trim(),
            trackName = request.trackName.trim(),
            artistName = request.artistName.trim()
        )
        if (normalizedRequest.keyword.isBlank()) {
            return emptyList()
        }
        if (normalizedRequest.sources.isEmpty()) {
            return emptyList()
        }

        val candidates = buildList {
            for (source in matchSourceOrder) {
                if (source !in normalizedRequest.sources) continue
                addAll(
                    when (source) {
                        EditableLyricMatchSource.KUGOU -> searchKugou(normalizedRequest)
                        EditableLyricMatchSource.CLOUD_MUSIC -> searchCloudMusic(normalizedRequest)
                        EditableLyricMatchSource.QQ_MUSIC -> searchQqMusic(normalizedRequest)
                        EditableLyricMatchSource.AMLL_TTML -> searchAmllTtml(normalizedRequest)
                        EditableLyricMatchSource.LRCLIB -> searchLrcLib(normalizedRequest)
                        EditableLyricMatchSource.YOUTUBE_MUSIC -> searchYouTubeMusic(normalizedRequest)
                    }
                )
            }
        }
        return rankEditableLyricMatches(
            normalizedRequest,
            candidates.map(::sanitizeEditableLyricMatchCandidate)
        )
            .distinctBy { result ->
                "${result.candidate.source}:${normalizeLyricMatchText(result.candidate.title)}:" +
                    "${normalizeLyricMatchText(result.candidate.artist)}:${result.candidate.lyrics.hashCode()}"
            }
            .take(MAX_RESULTS)
    }

    private suspend fun searchKugou(request: EditableLyricMatchRequest): List<EditableLyricMatchCandidate> {
        return runSourceSearch("kugou") {
            collectLyricSearchResults(
                label = "kugou",
                request = request,
                key = { it.id }
            ) { query ->
                kugouLyricsClient.searchSongs(query)
            }
                .rankKugouForDetailLookup(request)
                .mapNotNull { song ->
                    val lyricPayload = kugouLyricsClient.getBestLyricPayload(song) ?: return@mapNotNull null
                    EditableLyricMatchCandidate(
                        id = song.id,
                        source = EditableLyricMatchSource.KUGOU,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        durationMs = song.durationMs,
                        lyrics = lyricPayload.lyrics,
                        translatedLyrics = lyricPayload.translatedLyrics,
                        format = resolveLyricFormat(lyricPayload.lyrics),
                        sourceScore = 4
                    )
                }
        }
    }

    private suspend fun searchCloudMusic(request: EditableLyricMatchRequest): List<EditableLyricMatchCandidate> {
        return searchSearchApi(
            request = request,
            api = cloudMusicSearchApi,
            source = EditableLyricMatchSource.CLOUD_MUSIC,
            label = "netease"
        )
    }

    private suspend fun searchQqMusic(request: EditableLyricMatchRequest): List<EditableLyricMatchCandidate> {
        return searchSearchApi(
            request = request,
            api = qqMusicSearchApi,
            source = EditableLyricMatchSource.QQ_MUSIC,
            label = "qq"
        )
    }

    private suspend fun searchSearchApi(
        request: EditableLyricMatchRequest,
        api: SearchApi,
        source: EditableLyricMatchSource,
        label: String
    ): List<EditableLyricMatchCandidate> {
        return runSourceSearch(label) {
            buildList {
                collectLyricSearchResults(
                    label = label,
                    request = request,
                    key = { it.id }
                ) { query ->
                    api.search(query, page = 1)
                }
                    .rankSearchApiForDetailLookup(request)
                    .forEach { searchInfo ->
                        val details = try {
                            if (source == EditableLyricMatchSource.QQ_MUSIC && api is QQMusicSearchApi) {
                                api.getNativeSongInfo(searchInfo.id)
                            } else {
                                api.getSongInfo(searchInfo.id)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            NPLogger.d(TAG, "Editable lyric detail failed for $label: ${error.message}")
                            return@forEach
                        }
                        val lyric = details.lyric?.takeIf { it.isNotBlank() } ?: return@forEach
                        add(
                            EditableLyricMatchCandidate(
                                id = details.id,
                                source = source,
                                title = details.songName,
                                artist = details.singer,
                                album = details.album,
                                durationMs = parseSearchDurationMs(searchInfo),
                                lyrics = lyric,
                                translatedLyrics = details.translatedLyric,
                                format = resolveLyricFormat(lyric),
                                sourceScore = if (searchInfo.source == MusicPlatform.CLOUD_MUSIC) 4 else 3
                            )
                        )
                    }
            }
        }
    }

    private suspend fun searchAmllTtml(request: EditableLyricMatchRequest): List<EditableLyricMatchCandidate> {
        return runSourceSearch("amll") {
            val results = collectLyricSearchResults(
                label = "amll",
                request = request,
                key = { it.file }
            ) { query ->
                amllTtmlClient.searchLyrics(
                    query = query,
                    trackName = request.trackName,
                    artistName = request.artistName
                )
            }
            results
                .sortedWith(
                    compareByDescending<AmllTtmlSearchResult> {
                        scoreAmllSearchResult(request.trackName, request.artistName, it)
                    }.thenByDescending { it.score }
                )
                .take(MAX_DETAIL_RESULTS)
                .mapNotNull { result ->
                    val lyrics = amllTtmlClient.getLyrics(result) ?: return@mapNotNull null
                    EditableLyricMatchCandidate(
                        id = result.file,
                        source = EditableLyricMatchSource.AMLL_TTML,
                        title = lyrics.title.ifBlank { result.title },
                        artist = lyrics.artists.joinToString("/").ifBlank { result.artist },
                        album = lyrics.album.ifBlank { result.albums.firstOrNull() },
                        durationMs = estimateLyricDurationMs(lyrics.lyrics),
                        lyrics = lyrics.lyrics,
                        format = EditableLyricFormat.TTML,
                        sourceScore = (result.score / 20).coerceIn(0, 10)
                    )
                }
        }
    }

    private suspend fun searchLrcLib(request: EditableLyricMatchRequest): List<EditableLyricMatchCandidate> {
        return runSourceSearch("lrclib") {
            collectLyricSearchResults(
                label = "lrclib",
                request = request,
                key = { "${it.trackName}:${it.artistName}:${it.durationSeconds}" }
            ) { query ->
                lrcLibClient.searchLyricsCandidates(query)
            }
                .take(MAX_SOURCE_RESULTS)
                .mapNotNull { result ->
                    val rawLyric = result.syncedLyrics
                        ?: result.plainLyrics?.let {
                            plainLyricsToEditableLrc(it, result.durationSeconds?.times(1_000L) ?: request.durationMs)
                        }
                        ?: return@mapNotNull null
                    EditableLyricMatchCandidate(
                        id = "${result.trackName}:${result.artistName}:${result.durationSeconds}",
                        source = EditableLyricMatchSource.LRCLIB,
                        title = result.trackName,
                        artist = result.artistName,
                        durationMs = result.durationSeconds?.times(1_000L) ?: 0L,
                        lyrics = rawLyric,
                        format = if (result.syncedLyrics.isNullOrBlank()) {
                            EditableLyricFormat.PLAIN
                        } else {
                            EditableLyricFormat.LRC
                        },
                        sourceScore = if (result.syncedLyrics.isNullOrBlank()) 1 else 4
                    )
                }
        }
    }

    private suspend fun searchYouTubeMusic(request: EditableLyricMatchRequest): List<EditableLyricMatchCandidate> {
        return runSourceSearch("youtube") {
            buildList {
                collectLyricSearchResults(
                    label = "youtube",
                    request = request,
                    key = { it.videoId }
                ) { query ->
                    youtubeMusicClient.search(query, limit = MAX_SOURCE_RESULTS)
                }
                    .rankYouTubeForDetailLookup(request)
                    .forEach { result ->
                        val lyrics = try {
                            youtubeMusicClient.getLyrics(result.videoId)?.lyrics
                                ?.takeIf { it.isNotBlank() }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            NPLogger.d(TAG, "YouTube lyric detail failed: ${error.message}")
                            null
                        } ?: return@forEach
                        val editableLyric = if (looksLikeTimedLyric(lyrics)) {
                            lyrics
                        } else {
                            plainLyricsToEditableLrc(
                                lyrics,
                                result.durationMs.takeIf { it > 0L } ?: request.durationMs
                            )
                        }
                        add(
                            EditableLyricMatchCandidate(
                                id = result.videoId,
                                source = EditableLyricMatchSource.YOUTUBE_MUSIC,
                                title = result.title,
                                artist = result.artist,
                                album = result.album.ifBlank { null },
                                durationMs = result.durationMs,
                                lyrics = editableLyric,
                                format = resolveLyricFormat(editableLyric),
                                sourceScore = sourceScoreForYouTubeResult(result)
                            )
                        )
                    }
            }
        }
    }

    private suspend fun runSourceSearch(
        label: String,
        block: suspend () -> List<EditableLyricMatchCandidate>
    ): List<EditableLyricMatchCandidate> {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "Editable lyric match failed for $label: ${error.message}")
            emptyList()
        }
    }

    private suspend fun <T> collectLyricSearchResults(
        label: String,
        request: EditableLyricMatchRequest,
        key: (T) -> String,
        search: suspend (String) -> List<T>
    ): List<T> {
        val results = linkedMapOf<String, T>()
        for (query in editableLyricMatchSearchQueries(request)) {
            val items = try {
                search(query)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.d(TAG, "Editable lyric query failed for $label: ${error.message}")
                emptyList()
            }
            items.take(MAX_SOURCE_RESULTS).forEach { item ->
                val resultKey = key(item).ifBlank { "$query:${item.hashCode()}" }
                results.putIfAbsent(resultKey, item)
            }
        }
        return results.values.toList()
    }

    private fun parseSearchDurationMs(searchInfo: SongSearchInfo): Long {
        val parts = searchInfo.duration.trim().split(':')
        if (parts.size !in 2..3) return 0L
        val values = parts.map { it.toLongOrNull() ?: return 0L }
        val seconds = when (values.size) {
            2 -> values[0] * 60L + values[1]
            3 -> values[0] * 3_600L + values[1] * 60L + values[2]
            else -> 0L
        }
        return seconds.takeIf { it > 0L }?.times(1_000L) ?: 0L
    }

    private fun sourceScoreForYouTubeResult(result: YouTubeMusicSearchResult): Int {
        return if (result.durationMs > 0L) 3 else 0
    }

    private fun sanitizeEditableLyricMatchCandidate(
        candidate: EditableLyricMatchCandidate
    ): EditableLyricMatchCandidate {
        val sanitized = sanitizeMatchedEditableLyrics(
            lyrics = candidate.lyrics,
            translatedLyrics = candidate.translatedLyrics,
            title = candidate.title,
            artist = candidate.artist,
            album = candidate.album
        )
        if (sanitized.lyrics == candidate.lyrics && sanitized.translatedLyrics == candidate.translatedLyrics) {
            return candidate
        }
        return candidate.copy(
            lyrics = sanitized.lyrics,
            translatedLyrics = sanitized.translatedLyrics,
            format = resolveLyricFormat(sanitized.lyrics)
        )
    }

}

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

private fun List<SongSearchInfo>.rankSearchApiForDetailLookup(
    request: EditableLyricMatchRequest
): List<SongSearchInfo> {
    return sortedWith(
        compareByDescending<SongSearchInfo> { scoreLyricMatchTitle(request.trackName, it.songName) }
            .thenByDescending { scoreLyricMatchArtist(request.artistName, it.singer) }
            .thenByDescending { scoreLyricMatchDuration(request.durationMs, parseDurationMs(it.duration)) }
    ).take(2)
}

private fun List<KugouSongSearchResult>.rankKugouForDetailLookup(
    request: EditableLyricMatchRequest
): List<KugouSongSearchResult> {
    return sortedWith(
        compareByDescending<KugouSongSearchResult> { scoreLyricMatchTitle(request.trackName, it.title) }
            .thenByDescending { scoreLyricMatchArtist(request.artistName, it.artist) }
            .thenByDescending { scoreLyricMatchDuration(request.durationMs, it.durationMs) }
    ).take(2)
}

private fun List<YouTubeMusicSearchResult>.rankYouTubeForDetailLookup(
    request: EditableLyricMatchRequest
): List<YouTubeMusicSearchResult> {
    return sortedWith(
        compareByDescending<YouTubeMusicSearchResult> { scoreLyricMatchTitle(request.trackName, it.title) }
            .thenByDescending { scoreLyricMatchArtist(request.artistName, it.artist) }
            .thenByDescending { scoreLyricMatchDuration(request.durationMs, it.durationMs) }
    ).take(2)
}

private fun resolveLyricFormat(rawLyric: String): EditableLyricFormat {
    return when {
        rawLyric.contains(Regex("""<\s*tt(?:\s|>)""", RegexOption.IGNORE_CASE)) -> EditableLyricFormat.TTML
        rawLyric.contains(Regex("""\[\d{1,19},\s*\d{1,19}]""")) -> EditableLyricFormat.YRC
        looksLikeTimedLyric(rawLyric) -> EditableLyricFormat.LRC
        else -> EditableLyricFormat.PLAIN
    }
}

private fun parseDurationMs(value: String): Long {
    val parts = value.trim().split(':')
    if (parts.size !in 2..3) return 0L
    val values = parts.map { it.toLongOrNull() ?: return 0L }
    val seconds = when (values.size) {
        2 -> values[0] * 60L + values[1]
        3 -> values[0] * 3_600L + values[1] * 60L + values[2]
        else -> 0L
    }
    return seconds.takeIf { it > 0L }?.times(1_000L) ?: 0L
}

private fun looksLikeTimedLyric(rawLyric: String): Boolean {
    return rawLyric.contains(Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]"""))
}

private fun plainLyricsToEditableLrc(plainLyrics: String, durationMs: Long): String {
    return convertPlainLyricsToEntries(plainLyrics, durationMs).toEditableLyricsText()
        .ifBlank { plainLyrics }
}

private fun estimateLyricDurationMs(rawLyric: String): Long {
    return runCatching {
        parseNeteaseLyricsAuto(rawLyric).estimatedDurationMs()
    }.getOrDefault(0L)
}

private fun List<LyricEntry>.estimatedDurationMs(): Long {
    return maxOfOrNull { maxOf(it.startTimeMs, it.endTimeMs) } ?: 0L
}
