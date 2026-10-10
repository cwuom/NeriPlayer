package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ClearAll
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP

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
    val mini = LocalMiniPlayerHeight.current
    var selectedPeriod by remember { mutableStateOf(PlaybackStatsPeriod.ALL) }
    var sortMode by remember { mutableStateOf(StatsSortMode.PLAY_COUNT) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val queryDay by rememberStatsQueryDay()
    val query = rememberPlaybackStatsQuery(selectedPeriod, PlaybackStatsSort.valueOf(sortMode.name), queryDay.nowMillis)
    val queryDayKey = queryDay.key.takeUnless { selectedPeriod == PlaybackStatsPeriod.ALL }
    val pageRequestState = remember(query, queryDayKey) { mutableStateOf(StatsPageRequest()) }
    var pageRequest by pageRequestState
    val pageState by rememberStatsPage(query, pageRequestState)
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(CoreCommonR.string.stats_clear_title)) },
            text = { Text(stringResource(CoreCommonR.string.stats_clear_message)) },
            confirmButton = {
                HapticTextButton(onClick = {
                    AppContainer.playbackStatsRepo.clearAll()
                    showClearDialog = false
                }) {
                    Text(stringResource(CoreCommonR.string.action_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.statusBars,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(CoreCommonR.string.stats_title)) },
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
                                    text = { Text(stringResource(CoreCommonR.string.stats_sort_play_count)) },
                                    onClick = { sortMode = StatsSortMode.PLAY_COUNT; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreCommonR.string.stats_sort_listen_time)) },
                                    onClick = { sortMode = StatsSortMode.LISTEN_TIME; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreCommonR.string.stats_sort_recent)) },
                                    onClick = { sortMode = StatsSortMode.RECENT; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreCommonR.string.stats_sort_first_played)) },
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
            PlaybackStatsContent(
                state = if (!pageState.failed && (pageState.loadedQuery != query || pageState.loadedRequest != pageRequest))
                    pageState.copy(loading = true) else pageState,
                request = pageRequest,
                selectedPeriod = selectedPeriod,
                sortMode = sortMode,
                onPeriodSelected = { selectedPeriod = it },
                onPageRequest = { pageRequest = it },
                onSongClick = onSongClick,
                offlineMode = offlineMode,
                miniPlayerHeight = mini,
                tablet = tablet,
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            )
        }
    }
}
