@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.mapping

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
 * File: moe.ouom.neriplayer.data.sync.model/SyncDataModels
 * Created: 2025/1/7
 */

import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.normalizedSyncCausalTokens
import moe.ouom.neriplayer.data.sync.playlist.normalizedForDisplayOrder
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import android.content.Context
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.toSyncableRemoteSongOrNull
import moe.ouom.neriplayer.data.sync.CoverUrlMapper

internal fun sanitizeCoverUrlForSync(
    coverUrl: String?,
    mapper: CoverUrlMapper? = null
): String? {
    val normalizedUrl = coverUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!LocalSongSupport.isLocalMediaUri(normalizedUrl)) {
        return normalizedUrl
    }
    return mapper?.getSyncableNetworkUrl(normalizedUrl)
}

fun SyncPlaylist.Companion.fromLocalPlaylist(playlist: LocalPlaylist, modifiedAt: Long = System.currentTimeMillis(), context: Context? = null): SyncPlaylist {
    val systemDescriptor = context?.let {
        SystemLocalPlaylists.resolve(playlist.id, playlist.name, it)
    }
    return SyncPlaylist(
        id = systemDescriptor?.id ?: playlist.id,
        name = systemDescriptor?.currentName ?: playlist.name,
        songs = playlist.songs.mapNotNull { SyncSong.fromSongItemOrNull(it, context) },
        createdAt = playlist.id, // 使用ID作为创建时间
        modifiedAt = modifiedAt,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
    )
}

fun SyncPlaylist.toLocalPlaylist(): LocalPlaylist {
    val normalized = normalizedForDisplayOrder()
    return LocalPlaylist(
        id = normalized.id,
        name = normalized.name,
        songs = normalized.songs.map { it.toSongItem() }.toMutableList(),
        modifiedAt = normalized.modifiedAt,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
    )
}

fun SyncSong.Companion.fromSongItemOrNull(song: SongItem, context: Context? = null): SyncSong? {
    return song
        .toSyncableRemoteSongOrNull(context)
        ?.let { syncableSong -> fromSongItem(syncableSong, context) }
}

fun SyncSong.Companion.fromSongItem(song: SongItem, context: Context? = null): SyncSong {
    val mapper = context?.let { CoverUrlMapper.getInstance(it) }
    val syncCoverUrl = sanitizeCoverUrlForSync(song.coverUrl, mapper)
    val syncCustomCoverUrl = sanitizeCoverUrlForSync(song.customCoverUrl, mapper)
    val syncOriginalCoverUrl = sanitizeCoverUrlForSync(song.originalCoverUrl, mapper)

    return SyncSong(
        id = song.id,
        name = song.name,
        artist = song.artist,
        album = song.album,
        albumId = song.albumId,
        durationMs = song.durationMs,
        coverUrl = syncCoverUrl,
        mediaUri = LocalSongSupport.sanitizeMediaUriForSync(song.mediaUri),
        addedAt = song.addedAt.coerceAtLeast(0L),
        matchedLyric = song.matchedLyric,
        matchedTranslatedLyric = song.matchedTranslatedLyric,
        matchedLyricSource = song.matchedLyricSource?.name,
        matchedSongId = song.matchedSongId,
        userLyricOffsetMs = song.userLyricOffsetMs,
        customCoverUrl = syncCustomCoverUrl,
        customName = song.customName,
        customArtist = song.customArtist,
        originalName = song.originalName,
        originalArtist = song.originalArtist,
        originalCoverUrl = syncOriginalCoverUrl,
        originalLyric = song.originalLyric,
        originalTranslatedLyric = song.originalTranslatedLyric,
        channelId = song.channelId,
        audioId = song.audioId,
        subAudioId = song.subAudioId,
        playlistContextId = song.playlistContextId,
        syncMembershipTokens = song.syncMembershipTokens.normalizedSyncCausalTokens(),
        syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
    )
}

fun SyncSong.toSongItem(): SongItem {
    return SongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = LocalSongSupport.sanitizeMediaUriForSync(mediaUri),
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedLyricSource = matchedLyricSource?.let {
            try { MusicPlatform.valueOf(it) } catch (e: Exception) { null }
        },
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
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId,
        addedAt = addedAt,
        syncMembershipTokens = syncMembershipTokens.normalizedSyncCausalTokens()
    )
}

fun SyncFavoritePlaylist.Companion.fromFavoritePlaylist(playlist: FavoritePlaylist, context: Context? = null): SyncFavoritePlaylist {
    val mapper = context?.let { CoverUrlMapper.getInstance(it) }
    if (playlist.isDeleted) {
        return SyncFavoritePlaylist(
            id = playlist.id,
            name = playlist.name,
            coverUrl = sanitizeCoverUrlForSync(playlist.coverUrl, mapper),
            trackCount = 0,
            source = playlist.source,
            songs = emptyList(),
            addedTime = playlist.addedTime,
            modifiedAt = playlist.modifiedAt,
            isDeleted = true,
            sortOrder = playlist.sortOrder,
            browseId = playlist.browseId,
            playlistId = playlist.playlistId,
            subtitle = playlist.subtitle
        )
    }
    val syncedSongs = playlist.songs.mapNotNull { SyncSong.fromSongItemOrNull(it, context) }
    val hasFilteredLocalSongs = syncedSongs.size != playlist.songs.size
    val syncedCoverUrl = sanitizeCoverUrlForSync(playlist.coverUrl, mapper)
        ?: syncedSongs.firstOrNull()?.coverUrl
    return SyncFavoritePlaylist(
        id = playlist.id,
        name = playlist.name,
        coverUrl = syncedCoverUrl,
        trackCount = if (hasFilteredLocalSongs) {
            syncedSongs.size
        } else {
            maxOf(playlist.trackCount, syncedSongs.size)
        },
        source = playlist.source,
        songs = syncedSongs,
        addedTime = playlist.addedTime,
        modifiedAt = playlist.modifiedAt,
        isDeleted = false,
        sortOrder = playlist.sortOrder,
        browseId = playlist.browseId,
        playlistId = playlist.playlistId,
        subtitle = playlist.subtitle
    )
}

fun SyncFavoritePlaylist.toFavoritePlaylist(): FavoritePlaylist {
    return FavoritePlaylist(
        id = id,
        name = name,
        coverUrl = sanitizeCoverUrlForSync(coverUrl),
        trackCount = trackCount,
        source = source,
        browseId = browseId,
        playlistId = playlistId,
        subtitle = subtitle,
        songs = songs.map { it.toSongItem() },
        addedTime = addedTime,
        sortOrder = sortOrder,
        modifiedAt = modifiedAt,
        isDeleted = isDeleted
    )
}
