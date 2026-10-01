package moe.ouom.neriplayer.platform.lyrics.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.lyrics.api.client.AmllTtmlClient
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlLyrics
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlSearchResult
import moe.ouom.neriplayer.platform.lyrics.matching.scoreAmllSearchResult

class AmllLyricsRepository(private val client: AmllTtmlClient) {
    suspend fun searchLyrics(trackName: String, artistName: String): List<AmllTtmlSearchResult> =
        searchLyrics(query = trackName, trackName = trackName, artistName = artistName)

    suspend fun searchLyrics(
        query: String,
        trackName: String,
        artistName: String
    ): List<AmllTtmlSearchResult> = withContext(Dispatchers.IO) {
        client.searchLyrics(query)
            .filter { scoreAmllSearchResult(trackName, artistName, it) >= MIN_SEARCH_MATCH_SCORE }
            .sortedWith(
                compareByDescending<AmllTtmlSearchResult> {
                    scoreAmllSearchResult(trackName, artistName, it)
                }.thenByDescending { it.score }
            )
    }

    suspend fun getLyrics(searchResult: AmllTtmlSearchResult): AmllTtmlLyrics? =
        client.getLyrics(searchResult)

    private companion object {
        const val MIN_SEARCH_MATCH_SCORE = 70
    }
}
