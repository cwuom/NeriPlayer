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
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.remoteDownloadIdentityOrNull
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.common.locale.LanguageManager

object FavoritesPlaylist {
    const val SYSTEM_ID = -1001L

    private const val CANONICAL_ZH_NAME = "我喜欢的音乐"
    private const val CANONICAL_EN_NAME = "My Favorite Music"

    fun currentName(context: Context): String {
        val localizedContext = LanguageManager.applyLanguage(context)
        return localizedContext.getString(CoreCommonR.string.favorite_my_music)
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

    fun matchesSong(first: SongItem, second: SongItem): Boolean {
        if (first.sameIdentityAs(second)) return true
        val neteaseId = neteaseFavoriteSongId(first) ?: return false
        return neteaseId == neteaseFavoriteSongId(second)
    }

    private fun neteaseFavoriteSongId(song: SongItem): Long? {
        // 收藏可关联本地匹配结果，扫描和队列仍保留文件自身身份
        val remoteIdentity = if (LocalSongSupport.isLocalSong(song, null)) {
            song.remoteDownloadIdentityOrNull()
        } else {
            song.identity()
        }
        if (remoteIdentity != null) {
            return remoteIdentity.id.takeIf {
                remoteIdentity.album == "netease" && remoteIdentity.mediaUri == null && it > 0L
            }
        }
        if (song.matchedLyricSource != MusicPlatform.CLOUD_MUSIC) return null
        return song.matchedSongId?.toLongOrNull()?.takeIf { it > 0L }
    }

    fun merge(playlists: List<LocalPlaylist>, context: Context): LocalPlaylist {
        return LocalPlaylist(
            id = SYSTEM_ID,
            name = currentName(context),
            songs = mergedSongs(playlists),
            modifiedAt = latestModifiedAt(playlists),
            customCoverUrl = lastCustomCoverUrl(playlists),
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
        )
    }

    private fun mergedSongs(playlists: List<LocalPlaylist>): MutableList<SongItem> {
        val deduper = SystemPlaylistSongDeduper(playlists.sumOf { playlist -> playlist.songs.size })
        playlists.forEach { playlist -> deduper.addAll(playlist.songs) }
        return deduper.takeSongs()
    }

    private fun latestModifiedAt(playlists: List<LocalPlaylist>): Long =
        playlists.maxOfOrNull { it.modifiedAt } ?: System.currentTimeMillis()

    private fun lastCustomCoverUrl(playlists: List<LocalPlaylist>): String? =
        playlists.asReversed().firstNotNullOfOrNull { playlist ->
            playlist.customCoverUrl?.takeIf(String::isNotBlank)
        }
}
