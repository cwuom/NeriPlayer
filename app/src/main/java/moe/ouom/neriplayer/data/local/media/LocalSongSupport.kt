package moe.ouom.neriplayer.data.local.media

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
 * File: moe.ouom.neriplayer.data.local.media/LocalSongSupport
 * Updated: 2026/3/23
 */


import android.content.Context
import androidx.core.net.toUri
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

object LocalSongSupport {
    private val localUriSchemes = setOf("content", "file", "android.resource")
    const val LOCAL_ALBUM_IDENTITY = "__local_files__"

    fun isLocalSong(song: SongItem, context: Context? = null): Boolean {
        return !song.localFilePath.isNullOrBlank() ||
            isLocalMediaUri(song.mediaUri) ||
            isLikelyLegacyLocalSong(song, context)
    }

    fun isLocalSong(
        album: String?,
        mediaUri: String?,
        albumId: Long? = null,
        context: Context? = null
    ): Boolean {
        return isLocalMediaUri(mediaUri) ||
            (
                mediaUri.isNullOrBlank() &&
                    albumId == 0L &&
                    isLocalAlbumPlaceholder(album, context)
            )
    }

    fun isLocalMediaUri(mediaUri: String?): Boolean {
        if (mediaUri.isNullOrBlank()) return false
        if (mediaUri.startsWith("/")) return true

        val scheme = runCatching { mediaUri.toUri().scheme.orEmpty().lowercase() }
            .getOrDefault("")
        return scheme in localUriSchemes
    }

    fun sanitizeMediaUriForSync(mediaUri: String?): String? {
        return mediaUri?.takeUnless { isLocalMediaUri(it) }
    }

    private fun isLikelyLegacyLocalSong(song: SongItem, context: Context?): Boolean {
        return song.mediaUri.isNullOrBlank() &&
            song.albumId == 0L &&
            isLocalAlbumPlaceholder(song.album, context)
    }

    private fun isLocalAlbumPlaceholder(album: String?, context: Context?): Boolean {
        if (album.isNullOrBlank()) return false
        return album == LOCAL_ALBUM_IDENTITY || LocalFilesPlaylist.matches(album, context)
    }

    internal fun identityAlbumKey(song: SongItem): String {
        return if (isLocalSong(song, null)) LOCAL_ALBUM_IDENTITY else song.album
    }
}
