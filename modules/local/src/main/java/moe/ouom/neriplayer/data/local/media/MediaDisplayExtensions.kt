package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess

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
 * File: moe.ouom.neriplayer.data.model/MediaModelExtensions
 * Updated: 2026/3/23
 */

import android.content.Context
import android.os.Looper
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isMediaStoreCoverReference
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalArtistSummary
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist

fun SongItem.displayCoverUrl(): String? = customSongCoverOrNull(customCoverUrl)
    ?: coverUrl?.takeUnless(::isMediaStoreCoverReference)

fun SongItem.displayCoverUrl(
    context: Context,
    resolveLocalMetadataFallback: Boolean = true
): String? {
    customSongCoverOrNull(customCoverUrl)?.let { return it }
    val current = displayableCoverOrNull(coverUrl)
    val onMainThread = Looper.myLooper() == Looper.getMainLooper()
    val localCover = localFallbackCover(context, current, resolveLocalMetadataFallback, onMainThread)
    return resolveDisplayCoverUrl(
        customCoverUrl = null,
        currentCoverUrl = current,
        localCoverUrl = localCover,
        onMainThread = onMainThread
    ) ?: current
}

private fun SongItem.localFallbackCover(
    context: Context,
    current: String?,
    resolveLocalMetadataFallback: Boolean,
    onMainThread: Boolean
): String? = when {
    !resolveLocalMetadataFallback || !shouldResolveLocalCoverFallback(current) -> null
    isLocalSong() -> peekedLocalCover(context, onMainThread)
    else -> LocalMediaHostAccess.covers.getLocalCoverUri(
        context = context,
        song = this,
        resolveLocalMediaFallback = false
    )
}

// local rows already carry their source path; a download-index rebuild here
// would block the first frame and repeat the same directory scan
private fun SongItem.peekedLocalCover(context: Context, onMainThread: Boolean): String? =
    LocalMediaHostAccess.covers.peekLocalCoverUri(this)?.takeUnless(::isMediaStoreCoverReference)
        ?: if (onMainThread) null else LocalMediaSupport.resolveCoverUri(context, this)

/** A cover the user picked for a playlist, unless it is blank or names a cover directory. */
private fun customPlaylistCoverOrNull(customCoverUrl: String?): String? =
    customCoverUrl?.takeIf { it.isNotBlank() && !CustomSongCoverStorage.isDirectoryReference(it) }

private fun customSongCoverOrNull(customCoverUrl: String?): String? =
    customPlaylistCoverOrNull(customCoverUrl)?.takeUnless(::isMediaStoreCoverReference)

private fun displayableCoverOrNull(url: String?): String? =
    url?.takeIf { it.isNotBlank() }?.takeUnless(::isMediaStoreCoverReference)

private fun firstDisplayCover(songs: Sequence<SongItem>, cover: (SongItem) -> String?): String? =
    songs.firstNotNullOfOrNull { song -> cover(song)?.takeIf { it.isNotBlank() } }

fun SongItem.displayName(): String = customName ?: name
fun SongItem.displayArtist(): String = customArtist ?: artist

fun LocalPlaylist.displayCoverUrl(
    additionalCoverCandidates: List<SongItem> = emptyList()
): String? {
    return customPlaylistCoverOrNull(customCoverUrl)
        ?: firstDisplayCover(songs.asSequence() + additionalCoverCandidates.asSequence()) { song -> song.displayCoverUrl() }
}

fun LocalPlaylist.displayCoverUrl(
    context: Context,
    resolveLocalMetadataFallback: Boolean = true,
    additionalCoverCandidates: List<SongItem> = emptyList()
): String? {
    return customPlaylistCoverOrNull(customCoverUrl)
        ?: firstDisplayCover(songs.asSequence() + additionalCoverCandidates.asSequence()) { song ->
            song.displayCoverUrl(context = context, resolveLocalMetadataFallback = resolveLocalMetadataFallback)
        }
}

fun LocalArtistSummary.displayCoverUrl(): String? {
    return firstDisplayCover(songs.asSequence()) { song -> song.displayCoverUrl() }
}

fun LocalArtistSummary.displayCoverUrl(
    context: Context,
    resolveLocalMetadataFallback: Boolean = true
): String? {
    return firstDisplayCover(songs.asSequence()) { song ->
        song.displayCoverUrl(context = context, resolveLocalMetadataFallback = resolveLocalMetadataFallback)
    }
}

private fun String.isRemoteCoverSource(): Boolean {
    return startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true)
}

fun SongItem.shouldResolveLocalCoverFallback(currentCoverUrl: String?): Boolean {
    if (!isLocalSong()) return true
    if (currentCoverUrl.isNullOrBlank()) return true
    return currentCoverUrl.isRemoteCoverSource() || currentCoverUrl.isStaleLocalCoverReference()
}

private fun String.isStaleLocalCoverReference(): Boolean {
    return isMediaStoreCoverReference(this) ||
        startsWith("content://", ignoreCase = true) ||
        startsWith("file:", ignoreCase = true) ||
        startsWith("/")
}

fun resolveDisplayCoverUrl(
    customCoverUrl: String?,
    currentCoverUrl: String?,
    localCoverUrl: String?,
    onMainThread: Boolean
): String? {
    displayableCoverOrNull(customCoverUrl)?.let { return it }
    displayableCoverOrNull(localCoverUrl)?.let { return it }
    val current = displayableCoverOrNull(currentCoverUrl) ?: return null
    return if (onMainThread || !current.isRemoteCoverSource()) current else null
}
