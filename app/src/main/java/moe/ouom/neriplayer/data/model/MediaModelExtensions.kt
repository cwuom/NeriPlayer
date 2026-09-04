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
 * File: moe.ouom.neriplayer.data.model/MediaModelExtensions
 * Updated: 2026/3/23
 */

import android.content.Context
import android.os.Looper
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isMediaStoreCoverReference
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.playlist.model.LocalArtistSummary
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist

fun SongItem.displayCoverUrl(): String? = customCoverUrl
    ?.takeIf { it.isNotBlank() && !CustomSongCoverStorage.isDirectoryReference(it) }
    ?: coverUrl

fun SongItem.displayCoverUrl(
    context: Context,
    resolveLocalMetadataFallback: Boolean = true
): String? {
    customCoverUrl
        ?.takeIf { it.isNotBlank() && !CustomSongCoverStorage.isDirectoryReference(it) }
        ?.let { return it }
    val current = coverUrl?.takeIf { it.isNotBlank() }
    val onMainThread = Looper.myLooper() == Looper.getMainLooper()
    val localCover = if (resolveLocalMetadataFallback && shouldResolveLocalCoverFallback(current)) {
        if (isLocalSong()) {
            // local rows already carry their source path; a download-index rebuild here
            // would block the first frame and repeat the same directory scan
            AudioDownloadManager.peekLocalCoverUri(this)
                ?.takeUnless(::isMediaStoreCoverReference)
                ?: if (!onMainThread) {
                    LocalMediaSupport.resolveCoverUri(context, this)
                } else if (
                    localFilePath?.startsWith("/") == true &&
                        mediaUri?.startsWith("content://", ignoreCase = true) != true
                ) {
                    // direct file sidecars are bounded existence checks and are safe for the first frame
                    LocalMediaSupport.resolveNearbyCoverUri(context, this)
                } else {
                    null
                }
        } else {
            AudioDownloadManager.getLocalCoverUri(
                context = context,
                song = this,
                resolveLocalMediaFallback = false
            )
        }
    } else {
        null
    }
    resolveDisplayCoverUrl(
        customCoverUrl = null,
        currentCoverUrl = current,
        localCoverUrl = localCover,
        onMainThread = onMainThread
    )?.let { return it }
    return current
}

fun SongItem.displayName(): String = customName ?: name
fun SongItem.displayArtist(): String = customArtist ?: artist

fun LocalPlaylist.displayCoverUrl(
    additionalCoverCandidates: List<SongItem> = emptyList()
): String? {
    return customCoverUrl
        ?.takeIf { it.isNotBlank() && !CustomSongCoverStorage.isDirectoryReference(it) }
        ?: (songs.asSequence() + additionalCoverCandidates.asSequence())
        .firstNotNullOfOrNull { song ->
        song.displayCoverUrl()?.takeIf { it.isNotBlank() }
    }
}

fun LocalPlaylist.displayCoverUrl(
    context: Context,
    resolveLocalMetadataFallback: Boolean = true,
    additionalCoverCandidates: List<SongItem> = emptyList()
): String? {
    return customCoverUrl
        ?.takeIf { it.isNotBlank() && !CustomSongCoverStorage.isDirectoryReference(it) }
        ?: (songs.asSequence() + additionalCoverCandidates.asSequence())
        .firstNotNullOfOrNull { song ->
        song.displayCoverUrl(
            context = context,
            resolveLocalMetadataFallback = resolveLocalMetadataFallback
        )?.takeIf { it.isNotBlank() }
    }
}

fun LocalArtistSummary.displayCoverUrl(): String? {
    return songs.firstNotNullOfOrNull { song ->
        song.displayCoverUrl()?.takeIf { it.isNotBlank() }
    }
}

fun LocalArtistSummary.displayCoverUrl(
    context: Context,
    resolveLocalMetadataFallback: Boolean = true
): String? {
    return songs.firstNotNullOfOrNull { song ->
        song.displayCoverUrl(
            context = context,
            resolveLocalMetadataFallback = resolveLocalMetadataFallback
        )?.takeIf { it.isNotBlank() }
    }
}

private fun String.isRemoteCoverSource(): Boolean {
    return startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true)
}

internal fun SongItem.shouldResolveLocalCoverFallback(currentCoverUrl: String?): Boolean {
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

internal fun resolveDisplayCoverUrl(
    customCoverUrl: String?,
    currentCoverUrl: String?,
    localCoverUrl: String?,
    onMainThread: Boolean
): String? {
    customCoverUrl?.takeIf { it.isNotBlank() }?.let { return it }
    localCoverUrl?.takeIf { it.isNotBlank() }?.let { return it }

    val current = currentCoverUrl?.takeIf { it.isNotBlank() } ?: return null
    if (!current.isRemoteCoverSource()) {
        return current
    }
    return if (onMainThread) current else null
}
