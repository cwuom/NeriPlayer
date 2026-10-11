package moe.ouom.neriplayer.ui.screen.playlist

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
 * File: moe.ouom.neriplayer.ui.screen.playlist/LocalPlaylistDetailScreen
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.data.identity.stableKey
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import java.util.LinkedHashMap
import kotlin.time.Duration.Companion.milliseconds
internal const val LOCAL_PLAYLIST_ARTWORK_IDLE_DELAY_MS = 96L
internal const val LOCAL_PLAYLIST_ARTWORK_MEMORY_CACHE_LIMIT = 256
internal val retainedLocalPlaylistArtworkCache = object : LinkedHashMap<String, String>(
    LOCAL_PLAYLIST_ARTWORK_MEMORY_CACHE_LIMIT,
    0.75f,
    true
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
        return size > LOCAL_PLAYLIST_ARTWORK_MEMORY_CACHE_LIMIT
    }
}

@Composable
internal fun rememberLocalPlaylistArtworkIdle(
    sessionKey: Any,
    isScrollInProgress: Boolean
): Boolean {
    var hasReachedIdleWindow by remember(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(sessionKey, isScrollInProgress) {
        if (isScrollInProgress) {
            hasReachedIdleWindow = false
        } else {
            delay(LOCAL_PLAYLIST_ARTWORK_IDLE_DELAY_MS.milliseconds)
            hasReachedIdleWindow = true
        }
    }
    return hasReachedIdleWindow
}

@Composable
internal fun LocalPlaylistSongArtwork(
    song: SongItem,
    offlineMode: Boolean,
    resolveLocalFallback: Boolean,
    downloadPresenceVersion: Int,
    allowEmbeddedCoverFallback: Boolean
) {
    val requestedCoverUrl = rememberSongDisplayCoverUrl(
        song = song,
        resolveLocalFallback = resolveLocalFallback,
        downloadPresenceVersion = downloadPresenceVersion,
        allowEmbeddedCoverFallback = allowEmbeddedCoverFallback
    )
    val context = LocalContext.current
    val artworkIdentityKey = remember(song, downloadPresenceVersion) {
        "${song.stableKey()}|generation=$downloadPresenceVersion"
    }
    var displayedCoverUrl by remember(artworkIdentityKey) {
        mutableStateOf(
            initialLocalPlaylistArtworkUrl(
                retainedCoverUrl = cachedRetainedLocalPlaylistArtworkUrl(artworkIdentityKey),
                requestedCoverUrl = requestedCoverUrl,
                immediateCoverUrl = song.displayCoverUrl()
            )
        )
    }
    val latestRequestedCoverUrl by rememberUpdatedState(requestedCoverUrl)
    val visibleCoverUrl = retainedLocalPlaylistArtworkUrl(
        displayedCoverUrl = displayedCoverUrl,
        requestedCoverUrl = requestedCoverUrl
    )
    val visibleImageRequest = remember(context, visibleCoverUrl, offlineMode) {
        visibleCoverUrl?.let { coverUrl ->
            fastScrollableImageRequest(
                context = context,
                data = coverUrl,
                sizePx = 128,
                crossfade = false,
                offlineMode = offlineMode,
                cacheKey = listOf(
                    "playlist-row-cover",
                    artworkIdentityKey,
                    coverUrl
                ).joinToString("|")
            )
        }
    }
    val pendingCoverUrl = requestedCoverUrl
        ?.takeIf { displayedCoverUrl != null && it != displayedCoverUrl }
    val pendingImageRequest = remember(context, pendingCoverUrl, offlineMode) {
        pendingCoverUrl?.let { coverUrl ->
            fastScrollableImageRequest(
                context = context,
                data = coverUrl,
                sizePx = 128,
                crossfade = false,
                offlineMode = offlineMode,
                cacheKey = listOf(
                    "playlist-row-cover",
                    artworkIdentityKey,
                    coverUrl
                ).joinToString("|")
            )
        }
    }

    fun promoteLoadedCover(coverUrl: String) {
        if (latestRequestedCoverUrl == coverUrl) {
            displayedCoverUrl = coverUrl
            rememberRetainedLocalPlaylistArtworkUrl(artworkIdentityKey, coverUrl)
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        visibleImageRequest?.let { imageRequest ->
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = {
                    visibleCoverUrl?.let { coverUrl ->
                        if (displayedCoverUrl == null) {
                            promoteLoadedCover(coverUrl)
                        } else {
                            rememberRetainedLocalPlaylistArtworkUrl(artworkIdentityKey, coverUrl)
                        }
                    }
                }
            )
        }
        pendingImageRequest?.let { imageRequest ->
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0f },
                onSuccess = {
                    pendingCoverUrl?.let(::promoteLoadedCover)
                }
            )
        }
    }
}

internal fun retainedLocalPlaylistArtworkUrl(
    displayedCoverUrl: String?,
    requestedCoverUrl: String?
): String? = displayedCoverUrl ?: requestedCoverUrl

internal fun initialLocalPlaylistArtworkUrl(
    retainedCoverUrl: String?,
    requestedCoverUrl: String?,
    immediateCoverUrl: String?
): String? = retainedCoverUrl ?: requestedCoverUrl ?: immediateCoverUrl

internal fun cachedRetainedLocalPlaylistArtworkUrl(artworkIdentityKey: String): String? {
    return synchronized(retainedLocalPlaylistArtworkCache) {
        retainedLocalPlaylistArtworkCache[artworkIdentityKey]
    }
}

internal fun rememberRetainedLocalPlaylistArtworkUrl(
    artworkIdentityKey: String,
    coverUrl: String
) {
    if (coverUrl.isBlank()) return
    synchronized(retainedLocalPlaylistArtworkCache) {
        retainedLocalPlaylistArtworkCache[artworkIdentityKey] = coverUrl
    }
}

internal fun shouldResolveLocalPlaylistRowArtworkFallback(): Boolean = true

internal fun SongItem.hasMeaningfulPreviewMetadata(context: Context, fileName: String): Boolean =
    hasPreviewTitleMetadata(fileName) ||
        hasPreviewArtistMetadata(context.getString(CoreCommonR.string.music_unknown_artist)) ||
        hasPreviewAlbumMetadata(context) ||
        hasPreviewCover()

private fun SongItem.hasPreviewTitleMetadata(fileName: String): Boolean {
    val fileTitle = fileName.substringBeforeLast('.', fileName).trim()
    return name.isNotBlank() && (fileTitle.isBlank() || !name.equals(fileTitle, ignoreCase = true))
}

private fun SongItem.hasPreviewArtistMetadata(unknownArtist: String): Boolean =
    artist.trim().isNotBlank() && !artist.equals(unknownArtist, ignoreCase = true)

private fun SongItem.hasPreviewAlbumMetadata(context: Context): Boolean =
    album.trim().isNotBlank() &&
        album != moe.ouom.neriplayer.data.local.media.LocalSongSupport.LOCAL_ALBUM_IDENTITY &&
        !LocalFilesPlaylist.matches(album, context)

private fun SongItem.hasPreviewCover(): Boolean =
    !coverUrl.isNullOrBlank() || !originalCoverUrl.isNullOrBlank()
