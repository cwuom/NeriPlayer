package moe.ouom.neriplayer.data.model

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
 * File: moe.ouom.neriplayer.data.model/SongIdentity
 * Updated: 2026/3/23
 */


import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.data.sync.github.SyncSong
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

@Suppress("unused")
@Parcelize
data class SongIdentity(
    val id: Long,
    val album: String,
    val mediaUri: String?
) : Parcelable

private const val YOUTUBE_MUSIC_IDENTITY_ALBUM = "youtube_music"

fun SongIdentity.stableKey(): String = buildString {
    append(id)
    append('|')
    append(album)
    append('|')
    append(mediaUri.orEmpty())
}

fun SongItem.identity(): SongIdentity = SongIdentity(
    id = normalizedYouTubeMusicId(this) ?: id,
    album = normalizedYouTubeMusicAlbum(this),
    mediaUri = normalizedIdentityMediaUri(this)
)

fun SongItem.stableKey(): String = identity().stableKey()

fun SyncSong.identity(): SongIdentity = SongIdentity(
    id = extractYouTubeMusicVideoId(mediaUri)?.let(::stableYouTubeMusicId) ?: id,
    album = extractYouTubeMusicVideoId(mediaUri)?.let { YOUTUBE_MUSIC_IDENTITY_ALBUM } ?: album,
    mediaUri = extractYouTubeMusicVideoId(mediaUri)?.let { buildYouTubeMusicMediaUri(it) } ?: mediaUri
)

fun SyncSong.stableKey(): String = identity().stableKey()

fun SongItem.sameIdentityAs(other: SongItem?): Boolean {
    return other != null && identity() == other.identity()
}

fun SyncSong.sameIdentityAs(other: SyncSong?): Boolean {
    return other != null && identity() == other.identity()
}

private fun normalizedYouTubeMusicId(song: SongItem): Long? {
    return extractYouTubeMusicVideoId(song.mediaUri)?.let(::stableYouTubeMusicId)
}

private fun normalizedYouTubeMusicAlbum(song: SongItem): String {
    return if (extractYouTubeMusicVideoId(song.mediaUri) != null) {
        YOUTUBE_MUSIC_IDENTITY_ALBUM
    } else {
        LocalSongSupport.identityAlbumKey(song)
    }
}

private fun normalizedIdentityMediaUri(song: SongItem): String? {
    val videoId = extractYouTubeMusicVideoId(song.mediaUri)
    return if (videoId != null) {
        buildYouTubeMusicMediaUri(videoId)
    } else {
        song.localFilePath ?: song.mediaUri
    }
}
