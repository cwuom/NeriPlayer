package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.TrackStat
import moe.ouom.neriplayer.data.stats.aggregatePlaybackStatBucketsForPeriod
import moe.ouom.neriplayer.data.stats.aggregatePlaybackStatsCompatForPeriod
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

internal enum class StatsSortMode {
    PLAY_COUNT, LISTEN_TIME, RECENT, FIRST_PLAYED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackStatsScreen(
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val stats by AppContainer.playbackStatsRepo.statsFlow.collectAsStateWithLifecycle()
    val dailyStats by AppContainer.playbackStatsRepo.dailyStatsFlow.collectAsStateWithLifecycle()
    val mini = LocalMiniPlayerHeight.current
    var selectedPeriod by remember { mutableStateOf(PlaybackStatsPeriod.ALL) }
    var sortMode by remember { mutableStateOf(StatsSortMode.PLAY_COUNT) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val periodNeedsCompatBreakdown = remember(stats, dailyStats, selectedPeriod) {
        selectedPeriod != PlaybackStatsPeriod.ALL &&
            stats.isNotEmpty() &&
            dailyStats.isEmpty()
    }

    val periodStats = remember(stats, dailyStats, selectedPeriod) {
        if (selectedPeriod == PlaybackStatsPeriod.ALL) {
            stats
        } else if (dailyStats.isEmpty()) {
            aggregatePlaybackStatsCompatForPeriod(stats, selectedPeriod)
        } else {
            aggregatePlaybackStatBucketsForPeriod(dailyStats, selectedPeriod)
        }
    }
    val usesCompatPeriodStats = periodNeedsCompatBreakdown && periodStats.isNotEmpty()
    val sortedStats = remember(periodStats, sortMode) {
        when (sortMode) {
            StatsSortMode.PLAY_COUNT -> periodStats.sortedByDescending { it.playCount }
            StatsSortMode.LISTEN_TIME -> periodStats.sortedByDescending { it.totalListenMs }
            StatsSortMode.RECENT -> periodStats.sortedByDescending { it.lastPlayedAt }
            StatsSortMode.FIRST_PLAYED -> periodStats.sortedBy { it.firstPlayedAt }
        }
    }

    val totalPlayCount = remember(periodStats) { periodStats.sumOf { it.playCount } }
    val totalListenMs = remember(periodStats) { periodStats.sumOf { it.totalListenMs } }
    val trackCount = periodStats.size

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.stats_clear_title)) },
            text = { Text(stringResource(R.string.stats_clear_message)) },
            confirmButton = {
                HapticTextButton(onClick = {
                    AppContainer.playbackStatsRepo.clearAll()
                    showClearDialog = false
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    Box {
                        HapticIconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.stats_sort_play_count)) },
                                onClick = { sortMode = StatsSortMode.PLAY_COUNT; showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.stats_sort_listen_time)) },
                                onClick = { sortMode = StatsSortMode.LISTEN_TIME; showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.stats_sort_recent)) },
                                onClick = { sortMode = StatsSortMode.RECENT; showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.stats_sort_first_played)) },
                                onClick = { sortMode = StatsSortMode.FIRST_PLAYED; showSortMenu = false }
                            )
                        }
                    }
                    HapticIconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.ClearAll, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        val hasAnyStats = stats.isNotEmpty() || dailyStats.isNotEmpty()
        if (!hasAnyStats) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                StatsEmptyContent(message = stringResource(R.string.stats_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 8.dp, end = 8.dp, top = 8.dp,
                    bottom = 8.dp + mini
                )
            ) {
                item {
                    StatsPeriodSelector(
                        selectedPeriod = selectedPeriod,
                        onPeriodSelected = { selectedPeriod = it }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (usesCompatPeriodStats) {
                    item {
                        Text(
                            text = stringResource(R.string.stats_period_compat_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (periodStats.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            StatsEmptyContent(
                                message = stringResource(
                                    if (periodNeedsCompatBreakdown) {
                                        R.string.stats_period_missing_breakdown
                                    } else {
                                        R.string.stats_period_empty
                                    }
                                )
                            )
                        }
                    }
                } else {
                    // 概览卡片
                    item {
                        StatsOverviewCard(
                            totalPlayCount = totalPlayCount,
                            totalListenMs = totalListenMs,
                            trackCount = trackCount
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    // Top 5 条形图
                    if (sortedStats.size >= 2) {
                        item {
                            TopTracksBarChart(
                                tracks = sortedStats.take(5),
                                sortMode = sortMode
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    // 歌曲列表
                    itemsIndexed(sortedStats, key = { _, stat -> stat.identityKey }) { index, stat ->
                        StatTrackRow(
                            rank = index + 1,
                            stat = stat,
                            offlineMode = offlineMode,
                            onClick = {
                                val songItem = stat.toSongItem()
                                onSongClick(listOf(songItem), 0)
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun TrackStat.toSongItem(): SongItem = SongItem(
    id = id,
    name = name,
    artist = artist,
    album = album,
    albumId = albumId,
    durationMs = durationMs,
    coverUrl = coverUrl,
    mediaUri = localFilePath ?: mediaUri,
    localFilePath = localFilePath,
    localFileName = localFileName,
    customName = customName,
    customArtist = customArtist,
    customCoverUrl = customCoverUrl
)
