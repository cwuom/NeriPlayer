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
import java.util.Locale

@Parcelize
data class SongIdentity(
    val id: Long,
    val album: String,
    val mediaUri: String?
) : Parcelable

private const val YOUTUBE_MUSIC_IDENTITY_ALBUM = "youtube_music"
private const val BILIBILI_IDENTITY_HINT = "Bilibili"

fun SongIdentity.stableKey(): String = buildString {
    append(id)
    append('|')
    append(album)
    append('|')
    append(mediaUri.orEmpty())
}

fun SongItem.identity(): SongIdentity {
    normalizedRemoteIdentity()?.let { return it }
    return SongIdentity(
        id = normalizedYouTubeMusicId(this) ?: id,
        album = normalizedYouTubeMusicAlbum(this),
        mediaUri = normalizedIdentityMediaUri(this)
    )
}

fun SongItem.stableKey(): String = identity().stableKey()

fun SyncSong.identity(): SongIdentity {
    normalizedRemoteIdentity()?.let { return it }
    return SongIdentity(
        id = extractYouTubeMusicVideoId(mediaUri)?.let(::stableYouTubeMusicId) ?: id,
        album = extractYouTubeMusicVideoId(mediaUri)?.let { YOUTUBE_MUSIC_IDENTITY_ALBUM } ?: album,
        mediaUri = extractYouTubeMusicVideoId(mediaUri)?.let { buildYouTubeMusicMediaUri(it) } ?: mediaUri
    )
}

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
    } else if (LocalSongSupport.isLocalSong(song, null)) {
        LocalSongSupport.identityMediaReference(song)
    } else {
        song.localFilePath ?: song.mediaUri
    }
}

private fun SongItem.normalizedRemoteIdentity(): SongIdentity? {
    if (LocalSongSupport.isLocalSong(this, null)) return null

    val videoId = extractYouTubeMusicVideoId(mediaUri)
    if (videoId != null) {
        return SongIdentity(
            id = stableYouTubeMusicId(videoId),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(videoId)
        )
    }

    val channel = normalizedChannelId(
        rawChannelId = channelId,
        album = album,
        mediaUri = mediaUri,
        inferNeteaseForBlankRemote = true
    )
    val audio = audioId?.trim()?.takeIf { it.isNotBlank() } ?: id.takeIf { it != 0L }?.toString()
    if (channel == null || audio == null) return null
    if (channel == YOUTUBE_MUSIC_IDENTITY_ALBUM) {
        return SongIdentity(
            id = stableYouTubeMusicId(audio),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(audio)
        )
    }

    return SongIdentity(
        id = stableRemoteIdentityId(
            channel = channel,
            audio = audio,
            subAudio = normalizedSubAudioId(channel, subAudioId, album)
        ),
        album = channel,
        mediaUri = null
    )
}

private fun SyncSong.normalizedRemoteIdentity(): SongIdentity? {
    val videoId = extractYouTubeMusicVideoId(mediaUri)
    if (videoId != null) {
        return SongIdentity(
            id = stableYouTubeMusicId(videoId),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(videoId)
        )
    }

    val channel = normalizedChannelId(
        rawChannelId = channelId,
        album = album,
        mediaUri = mediaUri,
        inferNeteaseForBlankRemote = false
    )
    val audio = audioId?.trim()?.takeIf { it.isNotBlank() } ?: id.takeIf { it != 0L }?.toString()
    if (channel == null || audio == null) return null
    if (channel == YOUTUBE_MUSIC_IDENTITY_ALBUM) {
        return SongIdentity(
            id = stableYouTubeMusicId(audio),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(audio)
        )
    }

    return SongIdentity(
        id = stableRemoteIdentityId(
            channel = channel,
            audio = audio,
            subAudio = normalizedSubAudioId(channel, subAudioId, album)
        ),
        album = channel,
        mediaUri = null
    )
}

private fun normalizedChannelId(
    rawChannelId: String?,
    album: String,
    mediaUri: String?,
    inferNeteaseForBlankRemote: Boolean
): String? {
    val channel = rawChannelId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.lowercase(Locale.US)
        ?.let(::normalizeChannelAlias)
    if (channel != null) return channel

    return when {
        extractYouTubeMusicVideoId(mediaUri) != null -> YOUTUBE_MUSIC_IDENTITY_ALBUM
        album.startsWith(BILIBILI_IDENTITY_HINT, ignoreCase = true) -> "bilibili"
        album.startsWith("Netease", ignoreCase = true) -> "netease"
        inferNeteaseForBlankRemote && mediaUri.isNullOrBlank() -> "netease"
        else -> null
    }
}

private fun normalizeChannelAlias(channel: String): String {
    return when (channel) {
        "youtube", "ytmusic", "youtubemusic" -> YOUTUBE_MUSIC_IDENTITY_ALBUM
        else -> channel
    }
}

private fun normalizedSubAudioId(
    channel: String,
    rawSubAudioId: String?,
    album: String
): String {
    val explicitSubAudioId = rawSubAudioId?.trim()?.takeIf { it.isNotBlank() }
    if (channel != "bilibili") return ""
    return explicitSubAudioId ?: album.substringAfter('|', "").takeIf { it.isNotBlank() }.orEmpty()
}

private fun stableRemoteIdentityId(channel: String, audio: String, subAudio: String): Long {
    return when {
        channel == "netease" -> audio.toLongOrNull() ?: stableYouTubeMusicId("$channel|$audio")
        else -> stableYouTubeMusicId("$channel|$audio|$subAudio")
    }
}
