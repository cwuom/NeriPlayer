package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.annotation.StringRes
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
import moe.ouom.neriplayer.ui.component.overlay.DropdownMenu
import moe.ouom.neriplayer.ui.component.overlay.DropdownMenuItem
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
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.R as AppR

internal enum class StatsSortMode(@param:StringRes val labelRes: Int) {
    PLAY_COUNT(AppR.string.stats_sort_play_count),
    LISTEN_TIME(AppR.string.stats_sort_listen_time),
    RECENT(AppR.string.stats_sort_recent),
    FIRST_PLAYED(AppR.string.stats_sort_first_played)
}

@Composable
fun PlaybackStatsScreen(
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val mini = LocalMiniPlayerHeight.current
    var selection by rememberSaveable { mutableStateOf(StatsDateFilter()) }
    var showRankings by rememberSaveable { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf(StatsSortMode.PLAY_COUNT) }
    var showClearDialog by remember { mutableStateOf(false) }
    val queryDay by rememberStatsQueryDay()
    val zone = ZoneId.of(queryDay.key.timeZoneId)
    val today = Instant.ofEpochMilli(queryDay.nowMillis).atZone(zone).toLocalDate()
    val firstWeekday = WeekFields.of(LocalConfiguration.current.locales[0]).firstDayOfWeek
    val range = selection.dates(today, firstWeekday)?.toStatsTimeRange(zone)
    val sort = if (showRankings) PlaybackStatsSort.valueOf(sortMode.name) else PlaybackStatsSort.LISTEN_TIME
    val query = rememberPlaybackStatsQuery(selection.period, sort, queryDay.nowMillis).copy(range = range)
    val pageRequestState = remember(query) { mutableStateOf(StatsPageRequest()) }
    var pageRequest by pageRequestState
    val insightState = if (showRankings) null else rememberListeningInsights(query, pageRequest.retry).value
    val pageState = if (insightState == null) {
        val page = rememberStatsPage(query, pageRequestState).value
        when {
            page.loadedQuery != null && page.loadedQuery != query ->
                StatsPageState(loading = !page.failed, failed = page.failed)
            !page.failed && page.loadedRequest != pageRequest -> page.copy(loading = true)
            else -> page
        }
    } else {
        StatsPageState(
            summary = insightState.insights?.summary ?: PlaybackStatsSummary(),
            loading = insightState.loading,
            failed = insightState.failed
        )
    }
    val tablet = usePlaybackStatsTabletLayout(currentWindowWidthDp())

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(AppR.string.stats_clear_title)) },
            text = { Text(stringResource(AppR.string.stats_clear_message)) },
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
                PlaybackStatsTopBar(
                    sortMode = sortMode,
                    onSortModeChange = { sortMode = it },
                    onBack = onBack,
                    onClearRequest = { showClearDialog = true },
                    showSort = showRankings
                )
            }
        ) { innerPadding ->
            key(selection, showRankings, sort) {
                PlaybackStatsContent(
                    state = pageState,
                    request = pageRequest,
                    selectedPeriod = selection.period,
                    sortMode = sortMode,
                    onPeriodSelected = { selection = StatsDateFilter(period = it) },
                    onPageRequest = { pageRequest = it },
                    onSongClick = onSongClick,
                    offlineMode = offlineMode,
                    miniPlayerHeight = mini,
                    tablet = tablet,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    filters = {
                        StatsDateControls(selection, today, firstWeekday) { selection = it }
                        StatsViewSelector(showRankings) { showRankings = it }
                    },
                    overviewContent = if (insightState == null) null else ({
                        insightState.insights?.let { insights ->
                            PlaybackStatsInsightsContent(insights, insightState.hours, offlineMode, onTrackClick = {
                                onSongClick(listOf(it.toPlaybackStatsSongItem()), 0)
                            })
                        }
                    })
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackStatsTopBar(
    sortMode: StatsSortMode,
    onSortModeChange: (StatsSortMode) -> Unit,
    onBack: () -> Unit,
    onClearRequest: () -> Unit,
    showSort: Boolean = true
) {
    var showSortMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(AppR.string.stats_title)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        ),
        navigationIcon = {
            HapticIconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreCommonR.string.action_back)
                )
            }
        },
        actions = {
            if (showSort) Box {
                HapticIconButton(onClick = { showSortMenu = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(sortMode.labelRes)
                    )
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    StatsSortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(stringResource(mode.labelRes)) },
                            onClick = { onSortModeChange(mode); showSortMenu = false }
                        )
                    }
                }
            }
            HapticIconButton(onClick = onClearRequest) {
                Icon(
                    Icons.Filled.ClearAll,
                    contentDescription = stringResource(AppR.string.stats_clear_title)
                )
            }
        }
    )
}
