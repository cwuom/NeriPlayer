package moe.ouom.neriplayer.data.local.playlist.system

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
 * File: moe.ouom.neriplayer.data.local.playlist.system/SystemPlaylistSongDeduper
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.SongItem

internal fun List<SongItem>.distinctSystemSongs(): List<SongItem> {
    if (size < 2) return this

    return SystemPlaylistSongDeduper(size)
        .apply { addAll(this@distinctSystemSongs) }
        .songs()
}

internal class SystemPlaylistSongDeduper(expectedSongCount: Int) {
    private val initialCapacity = expectedSongCount.coerceIn(0, MAX_INITIAL_CAPACITY)
    private val distinct = ArrayList<SongItem>(initialCapacity)
    private val seenIdentities = HashSet<SongIdentity>(initialCapacity)
    private val indexByIdentity = HashMap<SongIdentity, Int>(initialCapacity)
    private val indexByLocalKey = HashMap<String, Int>()

    fun addAll(songs: Iterable<SongItem>) {
        songs.forEach(::add)
    }

    fun songs(): List<SongItem> = distinct

    fun takeSongs(): MutableList<SongItem> = distinct

    private fun add(song: SongItem) {
        val identity = song.identity()
        if (identity in seenIdentities) {
            indexByIdentity[identity]?.let { index ->
                distinct[index] = mergeDuplicateSong(distinct[index], song)
            }
            return
        }
        val localKeys = LocalSongSupport.localDuplicateKeys(
            song = song,
            includeMetadataFallback = true
        )
        val duplicateIndex = localKeys.firstNotNullOfOrNull(indexByLocalKey::get)
        if (duplicateIndex != null) {
            distinct[duplicateIndex] = mergeDuplicateSong(distinct[duplicateIndex], song)
            seenIdentities += identity
            indexByIdentity[identity] = duplicateIndex
            localKeys.forEach { key ->
                indexByLocalKey[key] = duplicateIndex
            }
            return
        }
        val index = distinct.size
        distinct += song
        seenIdentities += identity
        indexByIdentity[identity] = index
        localKeys.forEach { key ->
            indexByLocalKey[key] = index
        }
    }

    private fun mergeDuplicateSong(existing: SongItem, candidate: SongItem): SongItem {
        return existing.copy(
            name = existing.name.takeIf(String::isNotBlank) ?: candidate.name,
            artist = existing.artist.takeIf(String::isNotBlank) ?: candidate.artist,
            album = existing.album.takeIf(String::isNotBlank) ?: candidate.album,
            durationMs = existing.durationMs.takeIf { it > 0L } ?: candidate.durationMs,
            coverUrl = existing.coverUrl.takeIf { !it.isNullOrBlank() } ?: candidate.coverUrl,
            customCoverUrl = existing.customCoverUrl
                ?: candidate.customCoverUrl,
            originalCoverUrl = existing.originalCoverUrl
                ?: candidate.originalCoverUrl,
            matchedLyric = existing.matchedLyric ?: candidate.matchedLyric,
            matchedTranslatedLyric = existing.matchedTranslatedLyric
                ?: candidate.matchedTranslatedLyric,
            originalLyric = existing.originalLyric ?: candidate.originalLyric,
            originalTranslatedLyric = existing.originalTranslatedLyric
                ?: candidate.originalTranslatedLyric
        )
    }

    private companion object {
        const val MAX_INITIAL_CAPACITY = 4_096
    }
}
