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

    val distinct = ArrayList<SongItem>(size)
    val seenIdentities = HashSet<SongIdentity>(size)
    val seenLocalKeys = HashSet<String>()
    for (song in this) {
        val identity = song.identity()
        if (identity in seenIdentities) {
            continue
        }
        val localKeys = LocalSongSupport.localDuplicateKeys(
            song = song,
            includeMetadataFallback = true
        )
        if (localKeys.none(seenLocalKeys::contains)) {
            distinct += song
            seenIdentities += identity
            seenLocalKeys += localKeys
        }
    }
    return distinct
}
