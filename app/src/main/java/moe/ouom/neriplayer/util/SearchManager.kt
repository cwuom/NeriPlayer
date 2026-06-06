package moe.ouom.neriplayer.util

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SearchApi
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.util/SearchManager
 * Created: 2025/8/17
 */

object SearchManager {
    private const val MINIMUM_MATCH_SCORE = 60

    private val cloudMusicApi = AppContainer.cloudMusicSearchApi
    private val qqMusicApi = AppContainer.qqMusicSearchApi
    private val whitespaceRegex = Regex("\\s+")
    private val artistSeparatorRegex = Regex(
        "\\s*([/,\\u3001\\uFF0C&])\\s*|\\s+(feat\\.?|ft\\.?)\\s+|\\s+[xX]\\s+",
        RegexOption.IGNORE_CASE
    )

    suspend fun search(
        keyword: String,
        platform: MusicPlatform,
    ): List<SongSearchInfo> {
        val api = if (platform == MusicPlatform.CLOUD_MUSIC) cloudMusicApi else qqMusicApi

        NPLogger.d("SearchManager", "try to search $keyword")
        return try {
            api.search(keyword, page = 1).take(10)
        } catch (e: Exception) {
            NPLogger.e("SearchManager", "Failed to find match", e)
            emptyList()
        }
    }

    suspend fun findBestSearchCandidate(
        songName: String,
        songArtist: String
    ): SongSearchInfo? {
        NPLogger.d("SearchManager", "try to match $songName / $songArtist")

        val searchResults = buildList {
            addAll(searchCandidates(songName, qqMusicApi, "qq"))
            addAll(searchCandidates(songName, cloudMusicApi, "cloud"))
        }
        if (searchResults.isEmpty()) {
            return null
        }

        val normalizedSongName = normalizeText(songName)
        val normalizedArtist = normalizeText(songArtist)
        val normalizedArtists = normalizeArtists(songArtist)

        val scoredResults = searchResults.mapIndexed { index, candidate ->
            IndexedValue(
                index = index,
                value = candidate to scoreCandidate(
                    candidate = candidate,
                    targetSongName = normalizedSongName,
                    targetArtist = normalizedArtist,
                    targetArtists = normalizedArtists
                )
            )
        }

        val bestScore = scoredResults.maxOfOrNull { it.value.second } ?: return null
        if (bestScore < MINIMUM_MATCH_SCORE) {
            NPLogger.d(
                "SearchManager",
                "No confident match for $songName / $songArtist, bestScore=$bestScore"
            )
            return null
        }

        return scoredResults.firstOrNull { it.value.second == bestScore }?.value?.first
    }

    private suspend fun searchCandidates(
        keyword: String,
        api: SearchApi,
        label: String
    ): List<SongSearchInfo> {
        return runCatching { api.search(keyword, page = 1) }
            .onFailure {
                NPLogger.w(
                    "SearchManager",
                    "Failed to search $label for $keyword: ${it.message}"
                )
            }
            .getOrDefault(emptyList())
    }

    private fun scoreCandidate(
        candidate: SongSearchInfo,
        targetSongName: String,
        targetArtist: String,
        targetArtists: Set<String>
    ): Int {
        val candidateSongName = normalizeText(candidate.songName)
        val candidateArtist = normalizeText(candidate.singer)
        val candidateArtists = normalizeArtists(candidate.singer)

        var score = when {
            candidateSongName == targetSongName -> 100
            candidateSongName.contains(targetSongName) || targetSongName.contains(candidateSongName) -> 60
            else -> 0
        }

        if (targetArtist.isNotBlank() || targetArtists.isNotEmpty()) {
            score += when {
                candidateArtist == targetArtist -> 40
                candidateArtists.intersect(targetArtists).isNotEmpty() -> 25
                candidateArtist.contains(targetArtist) || targetArtist.contains(candidateArtist) -> 15
                else -> 0
            }
        }

        if (!candidate.coverUrl.isNullOrBlank()) score += 2
        if (!candidate.albumName.isNullOrBlank()) score += 1
        return score
    }

    private fun normalizeText(value: String): String {
        return value.trim().lowercase().replace(whitespaceRegex, " ")
    }

    private fun normalizeArtists(value: String): Set<String> {
        return artistSeparatorRegex.split(value)
            .asSequence()
            .map(::normalizeText)
            .filter { it.isNotBlank() }
            .toSet()
    }
}
