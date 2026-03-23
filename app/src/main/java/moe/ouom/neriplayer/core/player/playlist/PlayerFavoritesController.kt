package moe.ouom.neriplayer.core.player.playlist

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
 * File: moe.ouom.neriplayer.core.player.playlist/PlayerFavoritesController
 * Updated: 2026/3/23
 */

import android.app.Application
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal object PlayerFavoritesController {

    fun isFavorite(
        playlists: List<LocalPlaylist>,
        song: SongItem,
        application: Application
    ): Boolean {
        return FavoritesPlaylist.firstOrNull(playlists, application)
            ?.songs
            ?.any { it.sameIdentityAs(song) } == true
    }

    fun optimisticUpdateFavorites(
        playlists: List<LocalPlaylist>,
        add: Boolean,
        song: SongItem?,
        application: Application,
        favoritePlaylistName: String
    ): List<LocalPlaylist> {
        val favoriteIndex = playlists.indexOfFirst {
            FavoritesPlaylist.isSystemPlaylist(it, application)
        }
        val copiedPlaylists = playlists.map { playlist ->
            LocalPlaylist(
                id = playlist.id,
                name = playlist.name,
                songs = playlist.songs.toMutableList(),
                modifiedAt = playlist.modifiedAt,
                customCoverUrl = playlist.customCoverUrl
            )
        }.toMutableList()

        if (favoriteIndex >= 0) {
            val favorites = copiedPlaylists[favoriteIndex]
            when {
                add && song != null && favorites.songs.none { it.sameIdentityAs(song) } -> {
                    favorites.songs.add(song)
                }
                !add && song != null -> {
                    favorites.songs.removeAll { it.sameIdentityAs(song) }
                }
            }
        } else if (add && song != null) {
            copiedPlaylists += LocalPlaylist(
                id = FavoritesPlaylist.SYSTEM_ID,
                name = favoritePlaylistName,
                songs = mutableListOf(song)
            )
        }

        return copiedPlaylists
    }

    fun deepCopyPlaylists(playlists: List<LocalPlaylist>): List<LocalPlaylist> {
        return playlists.map { playlist ->
            LocalPlaylist(
                id = playlist.id,
                name = playlist.name,
                songs = playlist.songs.toMutableList(),
                modifiedAt = playlist.modifiedAt,
                customCoverUrl = playlist.customCoverUrl
            )
        }
    }
}
