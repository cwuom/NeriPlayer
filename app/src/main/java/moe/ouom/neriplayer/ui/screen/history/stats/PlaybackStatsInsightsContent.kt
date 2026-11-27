package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.NumberFormat
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsInsights
import moe.ouom.neriplayer.data.stats.PlaybackStatsRank
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

@Composable
internal fun PlaybackStatsInsightsContent(
    insights: PlaybackStatsInsights,
    hourListenMs: List<Long>,
    offlineMode: Boolean,
    onTrackClick: (TrackStat) -> Unit,
    modifier: Modifier = Modifier,
    singleDay: Boolean = false
) {
    if (insights.summary.trackCount == 0L) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaybackStatsRankingInsights(insights, offlineMode, onTrackClick)
        PlaybackStatsRhythm(insights, hourListenMs, singleDay)
    }
}

@Composable
internal fun PlaybackStatsRankingInsights(
    insights: PlaybackStatsInsights,
    offlineMode: Boolean,
    onTrackClick: (TrackStat) -> Unit,
    modifier: Modifier = Modifier
) {
    if (insights.topTrack == null && insights.artists.isEmpty() && insights.albums.isEmpty()) return
    var albumsSelected by rememberSaveable { mutableStateOf(false) }
    val showAlbums = insights.artists.isEmpty() || (albumsSelected && insights.albums.isNotEmpty())
    val ranking = if (showAlbums) insights.albums else insights.artists
    var expanded by rememberSaveable(showAlbums) { mutableStateOf(false) }
    var detailIndex by remember(showAlbums, ranking) { mutableStateOf<Int?>(null) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        insights.topTrack?.let { track ->
            val song = track.toPlaybackStatsSongItem()
            val title = song.displayName()
            val playLabel = stringResource(R.string.stats_top_track_action, title)
            val clean = NeriDesign.isClean
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("statsSpotlight"),
                color = if (clean) colors.surfaceContainer else colors.tertiaryContainer,
                contentColor = if (clean) colors.onSurface else colors.onTertiaryContainer,
                shape = NeriDesign.tokens.cardShape
            ) {
                Row(Modifier.clickable(onClickLabel = playLabel, role = Role.Button) { onTrackClick(track) }
                    .padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatsArtwork(song.displayCoverUrl(), offlineMode, if (clean) 58.dp else 66.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.stats_spotlight), style = MaterialTheme.typography.labelMedium)
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(song.displayArtist(), statsDuration(track.totalListenMs)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(40.dp).background(
                        if (clean) colors.primary else colors.onTertiaryContainer, CircleShape
                    ), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, null, tint = if (clean) colors.onPrimary else colors.tertiaryContainer)
                    }
                }
            }
        }
        if (ranking.isNotEmpty()) {
            StatsInsightCard(Modifier.testTag("statsRankInsights")) {
                StatsHeading(stringResource(R.string.stats_rotation))
                val labels = listOf(
                    stringResource(R.string.stats_artist_tab, statsNumber(insights.artistCount)),
                    stringResource(R.string.stats_album_tab, statsNumber(insights.albumCount))
                )
                if (insights.artists.isNotEmpty() && insights.albums.isNotEmpty()) {
                    StatsChoiceTabs(labels, if (showAlbums) 1 else 0) { albumsSelected = it == 1 }
                } else Text(labels[if (showAlbums) 1 else 0], style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.stats_rank_description), style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant)
                val visible = if (expanded) ranking else ranking.take(4)
                visible.forEachIndexed { index, rank ->
                    RankInsightRow(rank, index, offlineMode, round = !showAlbums) { detailIndex = index }
                    if (NeriDesign.isClean && index < visible.lastIndex) HorizontalDivider(color = colors.outlineVariant)
                }
                if (ranking.size > 4) {
                    HapticTextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
                        Text(if (expanded) stringResource(R.string.stats_rank_less)
                            else stringResource(R.string.stats_rank_more, ranking.size))
                    }
                }
            }
        }
    }
    detailIndex?.let { index ->
        StatsRankDetailDialog(ranking[index], index + 1, showAlbums, offlineMode) { detailIndex = null }
    }
}

@Composable
private fun RankInsightRow(
    rank: PlaybackStatsRank, index: Int, offlineMode: Boolean, round: Boolean, onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    Row(
        Modifier.fillMaxWidth().clip(NeriDesign.tokens.coverSmallShape).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 54.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(statsNumber((index + 1).toLong()), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        StatsArtwork(rank.coverUrl, offlineMode, 36.dp, round)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(rank.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(if (clean) 3.dp else 6.dp)
                .background(colors.surfaceContainerHighest, CircleShape)) {
                Box(Modifier.fillMaxWidth(rank.share.toFloat().coerceIn(0f, 1f))
                    .height(if (clean) 3.dp else 6.dp).background(colors.primary, CircleShape))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(statsPercent(rank.share), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(statsDuration(rank.totalListenMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StatsArtwork(url: String?, offlineMode: Boolean, size: Dp, round: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val shape = if (round) CircleShape else NeriDesign.tokens.coverSmallShape
    Box(Modifier.size(size).clip(shape).background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Album, null, tint = colors.onSecondaryContainer.copy(alpha = 0.76f),
            modifier = Modifier.size(size * 0.54f))
        if (!url.isNullOrBlank()) {
            AsyncImage(model = offlineCachedImageRequest(LocalContext.current, url, offlineMode = offlineMode),
                contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun StatsInsightCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = NeriDesign.tokens.groupShape,
        color = if (NeriDesign.isClean) colors.surfaceContainerLow else colors.surfaceContainer,
        contentColor = colors.onSurface) {
        Column(Modifier.padding(if (NeriDesign.isClean) 16.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun StatsChoiceTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (NeriDesign.isClean) {
        CleanSegmentedTabs(labels, selected, onSelect, Modifier.fillMaxWidth())
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                FilterChip(index == selected, onClick = { onSelect(index) }, label = { Text(label) })
            }
        }
    }
}

@Composable
internal fun StatsHeading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() },
        style = if (NeriDesign.isClean) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        fontWeight = if (NeriDesign.isClean) FontWeight.Bold else FontWeight.SemiBold)
}

@Composable
internal fun statsPercent(value: Double, precise: Boolean = false): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale, precise) {
        NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = if (precise) 2 else 1 }
    }
    return formatter.format(value.coerceIn(0.0, 1.0))
}
