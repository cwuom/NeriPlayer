package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

private val StatsTabletContentMaxWidth = 1200.dp
private val StatsTabletColumnsMinWidth = 720.dp

internal data class PlaybackStatsLayout(
    val contentWidth: Dp,
    val horizontalPadding: Dp,
    val useColumns: Boolean,
    val overviewWidth: Dp
)

internal fun resolvePlaybackStatsLayout(availableWidth: Dp, tablet: Boolean): PlaybackStatsLayout {
    val padding = if (tablet) 24.dp else 8.dp
    val contentWidth = (availableWidth - padding * 2).coerceAtLeast(0.dp)
        .let { if (tablet) minOf(it, StatsTabletContentMaxWidth) else it }
    return PlaybackStatsLayout(
        contentWidth = contentWidth,
        horizontalPadding = padding,
        useColumns = tablet && contentWidth >= StatsTabletColumnsMinWidth,
        overviewWidth = minOf(contentWidth * 0.32f, 320.dp)
    )
}

@Composable
internal fun PlaybackStatsContent(
    state: StatsPageState,
    request: StatsPageRequest,
    selectedPeriod: PlaybackStatsPeriod,
    sortMode: StatsSortMode,
    onPeriodSelected: (PlaybackStatsPeriod) -> Unit,
    onPageRequest: (StatsPageRequest) -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit,
    offlineMode: Boolean,
    miniPlayerHeight: Dp,
    tablet: Boolean,
    modifier: Modifier = Modifier
) {
    val rankingListState = rememberLazyListState()
    val displayedRequest = state.loadedRequest ?: request
    val displayedSortMode = state.loadedQuery?.sort?.let { StatsSortMode.valueOf(it.name) } ?: sortMode
    BoxWithConstraints(modifier.testTag("statsContent"), contentAlignment = Alignment.TopCenter) {
        val layout = resolvePlaybackStatsLayout(maxWidth, tablet)
        val contentModifier = Modifier.widthIn(max = layout.contentWidth).fillMaxSize()
        if (layout.useColumns) {
            Column(contentModifier.testTag("statsTabletColumns")) {
                StatsPeriodSelector(selectedPeriod, onPeriodSelected)
                StatsRefreshStatus(state, request, onPageRequest)
                StatsCompatibilityNotice(state)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp + miniPlayerHeight),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(
                        modifier = Modifier.width(layout.overviewWidth).fillMaxHeight().testTag("statsOverviewPane")
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StatsOverviewCard(
                            state.summary.totalPlayCount, state.summary.totalListenMs,
                            state.summary.trackCount, compact = true, stackMetrics = true,
                            modifier = Modifier.testTag("statsOverview")
                        )
                        if (displayedRequest.offset == 0 && state.page.tracks.size >= 2) {
                            StatsChartCard(state, displayedSortMode)
                        }
                    }
                    AdvancedGlassSurface(
                        role = AdvancedGlassRole.SemanticCard,
                        modifier = Modifier.weight(1f).testTag("statsRankingPane"),
                        shape = RoundedCornerShape(24.dp),
                        fallbackColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.8f),
                        tintColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            LazyColumn(
                                state = rankingListState,
                                modifier = Modifier.fillMaxWidth().testTag("statsRankings"),
                                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 16.dp)
                            ) {
                                item {
                                    Text(
                                        stringResource(displayedSortMode.labelResId()),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                    )
                                }
                                if (state.page.tracks.isEmpty()) {
                                    item { StatsEmptyResult(state) }
                                } else {
                                    statsTrackItems(state, displayedRequest, offlineMode, onSongClick)
                                    statsPageNavigation(state, request, onPageRequest)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = rankingListState,
                modifier = if (tablet) contentModifier.testTag("statsSingleColumn")
                    else Modifier.fillMaxSize().testTag("statsSingleColumn"),
                contentPadding = PaddingValues(
                    start = if (tablet) 0.dp else layout.horizontalPadding,
                    end = if (tablet) 0.dp else layout.horizontalPadding,
                    top = 8.dp, bottom = 8.dp + miniPlayerHeight
                )
            ) {
                item {
                    StatsPeriodSelector(selectedPeriod, onPeriodSelected)
                    StatsRefreshStatus(state, request, onPageRequest)
                    Spacer(Modifier.height(12.dp))
                }
                item { StatsCompatibilityNotice(state) }
                if (state.page.tracks.isEmpty()) {
                    item { StatsEmptyResult(state) }
                } else {
                    item {
                        StatsOverviewCard(
                            state.summary.totalPlayCount, state.summary.totalListenMs,
                            state.summary.trackCount, compact = tablet,
                            modifier = Modifier.testTag("statsOverview")
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    if (displayedRequest.offset == 0 && state.page.tracks.size >= 2) {
                        item {
                            if (tablet) StatsChartCard(state, displayedSortMode)
                            else TopTracksBarChart(state.page.tracks.take(5), displayedSortMode, Modifier.testTag("statsChart"))
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    statsTrackItems(state, displayedRequest, offlineMode, onSongClick)
                }
                statsPageNavigation(state, request, onPageRequest)
            }
        }
    }
}

@Composable
private fun StatsRefreshStatus(state: StatsPageState, request: StatsPageRequest, retry: (StatsPageRequest) -> Unit) {
    Box(Modifier.fillMaxWidth().height(4.dp)) {
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxSize().testTag("statsLoading"))
        }
    }
    if (state.failed) {
        HapticTextButton(
            onClick = { retry(request.copy(retry = request.retry + 1)) },
            modifier = Modifier.testTag("statsRetry")
        ) { Text(stringResource(CoreCommonR.string.stats_load_failed)) }
    }
}

@Composable
private fun StatsEmptyResult(state: StatsPageState) {
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        if (!state.loading && !state.failed) {
            StatsEmptyContent(stringResource(when {
                !state.summary.hasAnyStats -> CoreCommonR.string.stats_empty
                state.summary.usesLegacyBreakdown -> CoreCommonR.string.stats_period_missing_breakdown
                else -> CoreCommonR.string.stats_period_empty
            }))
        }
    }
}

@Composable
private fun StatsCompatibilityNotice(state: StatsPageState) {
    if (state.summary.usesLegacyBreakdown && state.page.tracks.isNotEmpty()) {
        Text(
            stringResource(CoreCommonR.string.stats_period_compat_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatsChartCard(state: StatsPageState, sortMode: StatsSortMode) {
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth().testTag("statsChart"),
        shape = RoundedCornerShape(20.dp),
        fallbackColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.75f),
        tintColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Box(Modifier.padding(16.dp)) { TopTracksBarChart(state.page.tracks.take(5), sortMode, compact = true) }
    }
}

private fun LazyListScope.statsTrackItems(
    state: StatsPageState,
    request: StatsPageRequest,
    offlineMode: Boolean,
    onSongClick: (List<SongItem>, Int) -> Unit
) {
    itemsIndexed(state.page.tracks, key = { _, stat -> stat.identityKey }) { index, stat ->
        StatTrackRow(
            rank = request.offset + index + 1,
            stat = stat,
            offlineMode = offlineMode,
            enabled = !state.loading && !state.failed,
            modifier = Modifier.testTag("statsTrack_${stat.identityKey}"),
            onClick = { onSongClick(listOf(stat.toPlaybackStatsSongItem()), 0) }
        )
    }
}

private fun LazyListScope.statsPageNavigation(
    state: StatsPageState,
    request: StatsPageRequest,
    change: (StatsPageRequest) -> Unit
) {
    item(key = "stats_page_navigation") {
        Box(Modifier.testTag("statsPageNavigation")) { StatsPageNavigation(state, request, change) }
    }
}

private fun StatsSortMode.labelResId(): Int = when (this) {
    StatsSortMode.PLAY_COUNT -> CoreCommonR.string.stats_sort_play_count
    StatsSortMode.LISTEN_TIME -> CoreCommonR.string.stats_sort_listen_time
    StatsSortMode.RECENT -> CoreCommonR.string.stats_sort_recent
    StatsSortMode.FIRST_PLAYED -> CoreCommonR.string.stats_sort_first_played
}
