package moe.ouom.neriplayer.ui.screen.playlist

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.ui.screen.playlist/NeteaseCloudScreen
 */

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.playlist.PlaylistLoadErrorDialog
import moe.ouom.neriplayer.ui.component.playlist.playlistLoadErrorText
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.i18n.asString
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.navigation.rememberMainTabDetailVisibilityState
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.viewmodel.playlist.NeteaseCloudUiState
import moe.ouom.neriplayer.ui.viewmodel.playlist.NeteaseCloudViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeteaseCloudScreen(
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val vm: NeteaseCloudViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                NeteaseCloudViewModel(context.applicationContext as Application)
            }
        }
    )
    val ui by vm.uiState.collectAsState()
    val pageOwner = remember { Any() }
    DisposableEffect(vm, pageOwner) {
        vm.start(pageOwner, offlineMode)
        onDispose { vm.stop(pageOwner) }
    }
    LaunchedEffect(vm, pageOwner, offlineMode) {
        vm.updateOfflineMode(pageOwner, offlineMode)
    }

    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val playlists by repo.playlists.collectAsState()
    val favoriteSongs = remember(playlists, context) {
        FavoritesPlaylist.firstOrNull(playlists, context)?.songs.orEmpty()
    }
    val favoriteLookup = remember(favoriteSongs) { FavoriteSongLookup(favoriteSongs) }
    val favoriteAddedText = stringResource(CoreCommonR.string.favorite_added)
    val favoriteRemovedText = stringResource(CoreCommonR.string.favorite_removed)
    fun toggleSongFavorite(song: SongItem, isFavorite: Boolean) {
        val message = if (isFavorite) favoriteRemovedText else favoriteAddedText
        scope.launchLocalPlaylistMutation(
            operation = "toggleNeteaseCloudSongFavorite",
            onResult = { result ->
                if (result.isSuccess) {
                    scope.launch { snackbarHostState.showNeriSnackbar(message) }
                }
            }
        ) {
            if (isFavorite) {
                repo.removeFromFavorites(song)
            } else {
                repo.addToFavorites(song)
            }
        }
    }

    val error = ui.error
    val errorText = if (error != null) playlistLoadErrorText(error).asString() else null
    LaunchedEffect(errorText, ui.tracks.isNotEmpty()) {
        if (errorText != null && ui.tracks.isNotEmpty()) {
            snackbarHostState.showNeriSnackbar(errorText)
        }
    }
    if (error != null && ui.tracks.isEmpty() && !ui.loginRequired) {
        PlaylistLoadErrorDialog(
            message = error,
            onRetry = { vm.retry(pageOwner) },
            onBack = onBack
        )
        return
    }

    val detailVisibilityState = rememberMainTabDetailVisibilityState("netease_cloud")
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    AnimatedVisibility(
        visibleState = detailVisibilityState,
        enter = fadeIn() + slideInVertically { it / 6 },
        exit = fadeOut() + slideOutVertically { it / 6 }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(CoreCommonR.string.netease_cloud_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            HapticIconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(CoreCommonR.string.cd_back)
                                )
                            }
                        },
                        actions = {
                            HapticIconButton(
                                onClick = { vm.refresh(pageOwner) },
                                enabled = !ui.loading && !ui.refreshing && !ui.loadingMore
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(CoreCommonR.string.action_refresh)
                                )
                            }
                        },
                        windowInsets = WindowInsets.statusBars,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        )
                    )
                    when {
                        ui.loginRequired -> NeteaseCloudMessage(
                            icon = Icons.Rounded.Cloud,
                            title = stringResource(CoreCommonR.string.netease_cloud_login_required),
                            hint = stringResource(CoreCommonR.string.netease_cloud_login_hint),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )

                        ui.loading && ui.tracks.isEmpty() -> Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }

                        ui.tracks.isEmpty() -> NeteaseCloudMessage(
                            icon = Icons.Rounded.Cloud,
                            title = stringResource(CoreCommonR.string.netease_cloud_empty),
                            hint = stringResource(CoreCommonR.string.netease_cloud_empty_hint),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )

                        else -> LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(
                                bottom = miniPlayerHeight + 16.dp
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            item(key = "netease_cloud_header") {
                                NeteaseCloudHeader(
                                    ui = ui,
                                    onPlayAll = {
                                        if (ui.tracks.isNotEmpty()) onSongClick(ui.tracks, 0)
                                    }
                                )
                            }
                            itemsIndexed(
                                items = ui.tracks,
                                key = { _, song -> song.id }
                            ) { index, song ->
                                SongRow(
                                    index = index + 1,
                                    song = song,
                                    isCurrentSong = currentSong?.sameIdentityAs(song) == true,
                                    animatePlayingIndicator = isPlaying,
                                    isFavorite = favoriteLookup.contains(song),
                                    onFavoriteToggle = { target, favorite ->
                                        toggleSongFavorite(target, favorite)
                                    },
                                    showCover = true,
                                    selectionMode = false,
                                    selected = false,
                                    onToggleSelect = {},
                                    onLongPress = {},
                                    onClick = { onSongClick(ui.tracks, index) },
                                    onPlayNext = { PlayerManager.addToQueueNext(song) },
                                    onAddToQueueEnd = { PlayerManager.addToQueueEnd(song) },
                                    snackbarHostState = snackbarHostState,
                                    offlineMode = offlineMode
                                )
                            }
                            if (ui.loadingMore) {
                                item(key = "netease_cloud_loading_more") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(CoreCommonR.string.netease_cloud_loading_more),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            NeriOverlaySnackbarHost(hostState = snackbarHostState)
        }
    }
}

@Composable
private fun NeteaseCloudHeader(
    ui: NeteaseCloudUiState,
    onPlayAll: () -> Unit
) {
    val summary = if (ui.maxBytes > 0L) {
        stringResource(
            CoreCommonR.string.netease_cloud_summary,
            ui.totalCount,
            formatFileSize(ui.usedBytes),
            formatFileSize(ui.maxBytes)
        )
    } else {
        pluralStringResource(
            AppR.plurals.library_song_count,
            ui.totalCount,
            ui.totalCount
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(neriRoundedShape(20.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreCommonR.string.netease_cloud_title),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = onPlayAll,
                enabled = ui.tracks.isNotEmpty()
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(CoreCommonR.string.player_play_all))
            }
            if (ui.loading || ui.loadingMore || ui.refreshing) {
                Spacer(Modifier.width(16.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun NeteaseCloudMessage(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
