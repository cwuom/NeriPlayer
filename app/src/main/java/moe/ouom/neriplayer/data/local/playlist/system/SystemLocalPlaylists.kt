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
 * File: moe.ouom.neriplayer.data.local.playlist.system/SystemLocalPlaylists
 * Updated: 2026/3/23
 */

import android.content.Context
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist

object SystemLocalPlaylists {
    data class Descriptor(
        val id: Long,
        val currentName: String
    )

    fun matchesReservedName(name: String?, context: Context? = null): Boolean {
        return FavoritesPlaylist.matches(name, context) || LocalFilesPlaylist.matches(name, context)
    }

    fun isSystemPlaylist(playlist: LocalPlaylist, context: Context): Boolean {
        return resolve(playlist.id, playlist.name, context) != null
    }

    fun resolve(playlistId: Long, playlistName: String?, context: Context): Descriptor? {
        return when {
            playlistId == FavoritesPlaylist.SYSTEM_ID ||
                (playlistId < 0 && FavoritesPlaylist.matches(playlistName, context)) -> {
                Descriptor(FavoritesPlaylist.SYSTEM_ID, FavoritesPlaylist.currentName(context))
            }

            playlistId == LocalFilesPlaylist.SYSTEM_ID ||
                (playlistId < 0 && LocalFilesPlaylist.matches(playlistName, context)) -> {
                Descriptor(LocalFilesPlaylist.SYSTEM_ID, LocalFilesPlaylist.currentName(context))
            }

            else -> null
        }
    }

    fun normalize(playlists: List<LocalPlaylist>, context: Context): List<LocalPlaylist> {
        val favorites = FavoritesPlaylist.merge(
            playlists.filter { FavoritesPlaylist.isSystemPlaylist(it, context) },
            context
        )
        val localFiles = LocalFilesPlaylist.merge(
            playlists.filter { LocalFilesPlaylist.isSystemPlaylist(it, context) },
            context
        )
        val others = playlists.filterNot { isSystemPlaylist(it, context) }

        return buildList {
            add(favorites)
            addAll(others)
            add(localFiles)
        }
    }
}
