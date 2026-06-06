package moe.ouom.neriplayer.core.player.model

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
 * File: moe.ouom.neriplayer.core.player.model/PersistedPlayerState
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal data class PersistedSongItem(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val coverUrl: String?,
    val mediaUri: String? = null,
    val matchedLyric: String? = null,
    val matchedTranslatedLyric: String? = null,
    val matchedLyricSource: MusicPlatform? = null,
    val matchedSongId: String? = null,
    val userLyricOffsetMs: Long = 0L,
    val customCoverUrl: String? = null,
    val customName: String? = null,
    val customArtist: String? = null,
    val originalName: String? = null,
    val originalArtist: String? = null,
    val originalCoverUrl: String? = null,
    val originalLyric: String? = null,
    val originalTranslatedLyric: String? = null,
    val localFileName: String? = null,
    val localFilePath: String? = null
) {
    fun toSongItem(): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = durationMs,
            coverUrl = coverUrl,
            mediaUri = mediaUri,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedLyricSource = matchedLyricSource,
            matchedSongId = matchedSongId,
            userLyricOffsetMs = userLyricOffsetMs,
            customCoverUrl = customCoverUrl,
            customName = customName,
            customArtist = customArtist,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric,
            localFileName = localFileName,
            localFilePath = localFilePath
        )
    }
}

internal fun SongItem.toPersistedSongItem(includeLyrics: Boolean = true): PersistedSongItem {
    return PersistedSongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        matchedLyric = matchedLyric.takeIf { includeLyrics },
        matchedTranslatedLyric = matchedTranslatedLyric.takeIf { includeLyrics },
        matchedLyricSource = matchedLyricSource,
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric.takeIf { includeLyrics },
        originalTranslatedLyric = originalTranslatedLyric.takeIf { includeLyrics },
        localFileName = localFileName,
        localFilePath = localFilePath
    )
}

internal data class PersistedState(
    val playlist: List<PersistedSongItem>,
    val index: Int,
    val mediaUrl: String? = null,
    val positionMs: Long = 0L,
    val shouldResumePlayback: Boolean = false,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null
)

internal data class PersistedPlaybackState(
    val index: Int,
    val mediaUrl: String? = null,
    val positionMs: Long = 0L,
    val shouldResumePlayback: Boolean = false,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null
)

internal fun PersistedState.toPlaybackState(): PersistedPlaybackState {
    return PersistedPlaybackState(
        index = index,
        mediaUrl = mediaUrl,
        positionMs = positionMs,
        shouldResumePlayback = shouldResumePlayback,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled
    )
}

internal fun PersistedState.withPlaybackState(playbackState: PersistedPlaybackState): PersistedState {
    return copy(
        index = playbackState.index,
        mediaUrl = playbackState.mediaUrl,
        positionMs = playbackState.positionMs,
        shouldResumePlayback = playbackState.shouldResumePlayback,
        repeatMode = playbackState.repeatMode,
        shuffleEnabled = playbackState.shuffleEnabled
    )
}
