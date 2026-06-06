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
 * File: moe.ouom.neriplayer.data.local.playlist.system/FavoritesPlaylist
 * Updated: 2026/3/23
 */

import android.content.Context
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.util.LanguageManager

object FavoritesPlaylist {
    const val SYSTEM_ID = -1001L

    private const val CANONICAL_ZH_NAME = "我喜欢的音乐"
    private const val CANONICAL_EN_NAME = "My Favorite Music"

    fun currentName(context: Context): String {
        val localizedContext = LanguageManager.applyLanguage(context)
        return localizedContext.getString(R.string.favorite_my_music)
    }

    fun candidateNames(context: Context? = null): Set<String> {
        return buildSystemPlaylistCandidateNames(
            canonicalChineseName = CANONICAL_ZH_NAME,
            canonicalEnglishName = CANONICAL_EN_NAME,
            localizedName = runCatching {
                context?.let(::currentName) ?: CANONICAL_ZH_NAME
            }.getOrDefault(CANONICAL_ZH_NAME)
        )
    }

    fun matches(name: String?, context: Context? = null): Boolean {
        if (name.isNullOrBlank()) return false
        return candidateNames(context).any { it.equals(name, ignoreCase = true) }
    }

    fun firstOrNull(playlists: List<LocalPlaylist>, context: Context? = null): LocalPlaylist? {
        return playlists.firstOrNull { it.id == SYSTEM_ID || (it.id < 0 && matches(it.name, context)) }
    }

    fun isSystemPlaylist(playlist: LocalPlaylist, context: Context): Boolean {
        return playlist.id == SYSTEM_ID || (playlist.id < 0 && matches(playlist.name, context))
    }

    fun merge(playlists: List<LocalPlaylist>, context: Context): LocalPlaylist {
        return LocalPlaylist(
            id = SYSTEM_ID,
            name = currentName(context),
            songs = playlists
                .flatMap { it.songs }
                .distinctBy { it.identity() }
                .toMutableList(),
            modifiedAt = playlists.maxOfOrNull { it.modifiedAt } ?: System.currentTimeMillis(),
            customCoverUrl = playlists.lastOrNull { !it.customCoverUrl.isNullOrBlank() }?.customCoverUrl
        )
    }
}
