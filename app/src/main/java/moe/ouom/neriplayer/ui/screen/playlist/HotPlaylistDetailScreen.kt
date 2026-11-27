package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.ui.screen.history.stats.rememberHotPlaybackStatsQuery
import moe.ouom.neriplayer.ui.screen.history.stats.rememberStatsQueryDay
import moe.ouom.neriplayer.ui.screen.history.stats.StatsPageRequest
import moe.ouom.neriplayer.ui.screen.history.stats.StatsPageNavigation
import moe.ouom.neriplayer.ui.screen.history.stats.rememberStatsPage
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.navigation.scrollToTopForReselect
import moe.ouom.neriplayer.ui.navigation.scrollToTopOnTitleBarTap
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.history.stats.StatTrackRow
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.R as AppR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotPlaylistDetailScreen(
    period: PlaybackStatsPeriod,
    onBack: () -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val queryDay by rememberStatsQueryDay()
    val query = rememberHotPlaybackStatsQuery(period, queryDay.nowMillis)
    val queryDayKey = queryDay.key.takeUnless { period == PlaybackStatsPeriod.ALL }
    val pageRequestState = remember(query, queryDayKey) { mutableStateOf(StatsPageRequest()) }
    var pageRequest by pageRequestState
    val state by rememberStatsPage(query, pageRequestState)
    val tracks = state.page.tracks
    val songs = remember(tracks) { tracks.map { it.toPlaybackStatsSongItem() } }
    val context = LocalContext.current
    val title = stringResource(period.hotPlaylistTitleResId())
    // 提升到顶栏可达处；列表在加载或失败时会卸载，原先重新出现时从顶部开始，这里保持一致
    val listState = rememberLazyListState()
    val listShown = !state.loading && !state.failed
    LaunchedEffect(listShown) {
        if (!listShown) listState.requestScrollToItem(0)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                modifier = Modifier.scrollToTopOnTitleBarTap {
                    listState.scrollToTopForReselect()
                },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreCommonR.string.action_back)
                        )
                    }
                },
                actions = {
                    HapticIconButton(
                        enabled = songs.isNotEmpty(),
                        onClick = { onSongClick(songs, 0) }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = stringResource(if (state.summary.trackCount <= 100) CoreCommonR.string.cd_play_all else AppR.string.stats_play_page)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (state.failed) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                moe.ouom.neriplayer.ui.haptic.HapticTextButton(onClick = { pageRequest = pageRequest.copy(retry = pageRequest.retry + 1) }) { Text(stringResource(AppR.string.stats_load_failed)) }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp + LocalMiniPlayerHeight.current
                )
            ) {
                item(key = "hot_playlist_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                CoreCommonR.string.library_hot_playlist_summary,
                                state.summary.trackCount,
                                formatPlayCount(context, state.summary.totalPlayCount)
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (tracks.isEmpty()) {
                    item(key = "hot_playlist_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(CoreCommonR.string.library_hot_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    itemsIndexed(tracks, key = { _, stat -> stat.identityKey }) { index, stat ->
                        StatTrackRow(
                            rank = pageRequest.offset + index + 1,
                            stat = stat,
                            offlineMode = offlineMode,
                            onClick = { onSongClick(songs, index) }
                        )
                    }
                }
                item(key = "hot_playlist_page_navigation") {
                    StatsPageNavigation(state, pageRequest) { pageRequest = it }
                }
            }
        }
    }
}

private fun PlaybackStatsPeriod.hotPlaylistTitleResId(): Int = when (this) {
    PlaybackStatsPeriod.MONTH -> CoreCommonR.string.library_hot_playlist_month
    else -> CoreCommonR.string.library_hot_playlist_week
}
