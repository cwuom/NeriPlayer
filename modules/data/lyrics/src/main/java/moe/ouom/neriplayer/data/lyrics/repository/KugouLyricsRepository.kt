package moe.ouom.neriplayer.data.lyrics.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.api.lyrics.client.KugouLyricsClient
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricCandidate
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricsPayload
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouSongSearchResult
import moe.ouom.neriplayer.core.logging.NPLogger

class KugouLyricsRepository(private val client: KugouLyricsClient) {
    suspend fun searchSongs(keyword: String, limit: Int = 8): List<KugouSongSearchResult> =
        client.searchSongs(keyword, limit)

    suspend fun getBestLyrics(song: KugouSongSearchResult): String? = getBestLyricPayload(song)?.lyrics

    suspend fun getBestLyricPayload(song: KugouSongSearchResult): KugouLyricsPayload? = withContext(Dispatchers.IO) {
        try {
            val candidates = client.searchLyricCandidates(song)
                .sortedWith(
                    compareByDescending<KugouLyricCandidate> { it.score }
                        .thenBy { kotlin.math.abs(it.durationMs - song.durationMs) }
                )
            for (candidate in candidates) {
                client.downloadKrcLyric(candidate)?.let { payload ->
                    return@withContext payload
                }
            }
            for (candidate in candidates) {
                client.downloadLrcLyric(candidate)?.let { payload ->
                    return@withContext payload
                }
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "Kugou lyric lookup failed: ${error.message}")
            null
        }
    }

    private companion object {
        const val TAG = "KugouLyricsRepository"
    }
}
