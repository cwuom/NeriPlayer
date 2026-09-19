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


import android.annotation.SuppressLint
import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportResult
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioScanPhase
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioScanProgress
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSongDeleteResult
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseRemotePlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.isSyncableRemoteSong
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.rememberMainTabDetailVisibilityState
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedSongs
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedPlaylist
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportFailure
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistDeleteResultGlobally
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistSongDeleteResult
import moe.ouom.neriplayer.ui.component.local.LocalSongDetailsDialog
import moe.ouom.neriplayer.ui.component.local.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.ui.component.download.SongDownloadSubtitle
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.feedback.dismissCurrentNeriSnackbar
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.util.rememberPlaylistDisplayCoverUrl
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalMetadataProcessingState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import moe.ouom.neriplayer.ui.haptic.HapticFloatingActionButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.format.formatTotalDuration
import moe.ouom.neriplayer.util.media.CoverArtColorCache
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.util.search.playlistSearchValues
import moe.ouom.neriplayer.util.search.SearchTextMatcher
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialogContent
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.io.File
import java.util.LinkedHashMap
import kotlin.random.Random
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
            delay(LOCAL_PLAYLIST_ARTWORK_IDLE_DELAY_MS)
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

internal fun SongItem.hasMeaningfulPreviewMetadata(context: Context, fileName: String): Boolean {
    val fileTitle = fileName.substringBeforeLast('.', fileName).trim()
    val unknownArtist = context.getString(R.string.music_unknown_artist)
    val hasTitleMetadata = name.isNotBlank() &&
        (fileTitle.isBlank() || !name.equals(fileTitle, ignoreCase = true))
    val hasArtistMetadata = artist.trim().isNotBlank() &&
        !artist.equals(unknownArtist, ignoreCase = true)
    val hasAlbumMetadata = album.trim().isNotBlank() &&
        album != moe.ouom.neriplayer.data.local.media.LocalSongSupport.LOCAL_ALBUM_IDENTITY &&
        !LocalFilesPlaylist.matches(album, context)
    return hasTitleMetadata || hasArtistMetadata || hasAlbumMetadata ||
        !coverUrl.isNullOrBlank() || !originalCoverUrl.isNullOrBlank()
}
