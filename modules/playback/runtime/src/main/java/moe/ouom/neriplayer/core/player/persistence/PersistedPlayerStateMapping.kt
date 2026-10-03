package moe.ouom.neriplayer.core.player.persistence

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

import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.playback.PersistedPlaybackState
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem

internal fun SongItem.toPersistedSongItem(includeLyrics: Boolean = true): PersistedSongItem {
    val preserveLyrics = includeLyrics || lyricSyncEdited != false
    return PersistedSongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        matchedLyric = matchedLyric.takeIf { preserveLyrics },
        matchedTranslatedLyric = matchedTranslatedLyric.takeIf { preserveLyrics },
        matchedRomanizedLyric = matchedRomanizedLyric.takeIf { preserveLyrics },
        originalRomanizedLyric = originalRomanizedLyric.takeIf { preserveLyrics },
        matchedLyricSource = matchedLyricSource,
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        lyricSyncRevision = lyricSyncRevision,
        lyricSyncEdited = lyricSyncEdited,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric.takeIf { preserveLyrics },
        originalTranslatedLyric = originalTranslatedLyric.takeIf { preserveLyrics },
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId,
        streamUrl = streamUrl
    )
}

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

fun PersistedSongItem.toSongItem(): SongItem {
    val inferredChannelId = channelId ?: if (
        !localFilePath.isNullOrBlank() ||
        LocalSongSupport.isLocalSong(album, mediaUri, albumId, null)
    ) {
        "local"
    } else {
        null
    }
    val inferredAudioId = audioId ?: if (inferredChannelId == "local") id.toString() else null
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
        matchedRomanizedLyric = matchedRomanizedLyric,
        originalRomanizedLyric = originalRomanizedLyric,
        matchedLyricSource = matchedLyricSource,
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        lyricSyncRevision = lyricSyncRevision,
        lyricSyncEdited = lyricSyncEdited,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = inferredChannelId,
        audioId = inferredAudioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId,
        streamUrl = streamUrl
    )
}
