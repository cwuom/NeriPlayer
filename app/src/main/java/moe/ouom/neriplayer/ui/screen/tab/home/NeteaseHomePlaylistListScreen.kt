package moe.ouom.neriplayer.ui.screen.tab.home

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.home.NeteaseHomePlaylistListScreen
 * Created: 2026/11/20
 */

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomePlaylistSource
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 「某个分类下的更多歌单」列表页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeteaseHomePlaylistListScreen(
    source: NeteaseHomePlaylistSource,
    onBack: () -> Unit = {},
    onPlaylistClick: (PlaylistSummary) -> Unit = {},
    offlineMode: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val homeViewModel: HomeViewModel = viewModel()

    var playlists by remember(source) { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var loading by remember(source) { mutableStateOf(true) }
    var refreshing by remember(source) { mutableStateOf(false) }
    var errorText by remember(source) { mutableStateOf<String?>(null) }
    var refreshToken by remember(source) { mutableIntStateOf(0) }

    LaunchedEffect(source, refreshToken) {
        val isFirstLoad = playlists.isEmpty()
        if (isFirstLoad) loading = true else refreshing = true
        errorText = null
        homeViewModel.fetchFullPlaylistSection(source)
            .onSuccess { loaded ->
                playlists = loaded
                errorText = null
            }
            .onFailure { error ->
                errorText = error.message ?: error::class.java.simpleName
            }
        loading = false
        refreshing = false
    }

    val title = stringResource(source.titleRes)
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsStateWithLifecycle()
    val favoriteKeys = remember(favorites) {
        favorites.mapTo(mutableSetOf()) { "${it.source}:${it.id}" }
    }
    val windowWidthDp = currentWindowWidthDp()
    val gridColumns = remember(windowWidthDp) {
        if (windowWidthDp >= 600.dp) GridCells.Adaptive(156.dp) else GridCells.Adaptive(120.dp)
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = title,
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
                    enabled = !offlineMode && !loading && !refreshing,
                    onClick = { refreshToken += 1 }
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(CoreCommonR.string.recommend_refresh)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                if (!offlineMode) refreshToken += 1
            },
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                loading && playlists.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                errorText != null && playlists.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(CoreCommonR.string.home_section_load_failed, errorText.orEmpty()),
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = { refreshToken += 1 }) {
                                Text(stringResource(CoreCommonR.string.home_retry_hint))
                            }
                        }
                    }
                }

                playlists.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(CoreCommonR.string.home_section_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = gridColumns,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 24.dp + miniPlayerHeight
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = playlists,
                            key = { playlist -> "home-playlist-list:${playlist.id}" }
                        ) { playlist ->
                            PlaylistCard(
                                playlist = playlist,
                                isFavorite = favoriteKeys.contains("netease:${playlist.id}"),
                                onClick = { onPlaylistClick(playlist) },
                                onShowSnackbar = { message ->
                                    scope.launch {
                                        snackbarHostState.showNeriSnackbar(message)
                                    }
                                },
                                offlineMode = offlineMode
                            )
                        }
                    }
                }
            }
        }
      }

        NeriOverlaySnackbarHost(
            hostState = snackbarHostState,
            bottomPadding = LocalMiniPlayerHeight.current
        )
    }
}
