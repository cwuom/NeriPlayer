package moe.ouom.neriplayer.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.TrackStat
import moe.ouom.neriplayer.util.offlineCachedImageRequest

private val StatsPeriodOptions = listOf(
    PlaybackStatsPeriod.DAY,
    PlaybackStatsPeriod.WEEK,
    PlaybackStatsPeriod.MONTH,
    PlaybackStatsPeriod.YEAR,
    PlaybackStatsPeriod.ALL
)

@Composable
internal fun StatsPeriodSelector(
    selectedPeriod: PlaybackStatsPeriod,
    onPeriodSelected: (PlaybackStatsPeriod) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatsPeriodOptions.forEach { period ->
            FilterChip(
                selected = selectedPeriod == period,
                onClick = { onPeriodSelected(period) },
                label = { Text(stringResource(period.labelResId())) }
            )
        }
    }
}

@Composable
internal fun StatsEmptyContent(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.BarChart,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun StatsOverviewCard(
    totalPlayCount: Int,
    totalListenMs: Long,
    trackCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatMetric(
                icon = Icons.Outlined.Headphones,
                value = totalPlayCount.toString(),
                label = stringResource(R.string.stats_total_plays)
            )
            StatMetric(
                icon = Icons.Outlined.AccessTime,
                value = formatListenDuration(totalListenMs),
                label = stringResource(R.string.stats_total_time)
            )
            StatMetric(
                icon = Icons.Outlined.LibraryMusic,
                value = trackCount.toString(),
                label = stringResource(R.string.stats_track_count)
            )
        }
    }
}

@Composable
private fun StatMetric(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun TopTracksBarChart(
    tracks: List<TrackStat>,
    sortMode: StatsSortMode
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    val maxValue = remember(tracks, sortMode) {
        when (sortMode) {
            StatsSortMode.PLAY_COUNT, StatsSortMode.RECENT, StatsSortMode.FIRST_PLAYED ->
                tracks.maxOfOrNull { it.playCount.toFloat() } ?: 1f
            StatsSortMode.LISTEN_TIME ->
                tracks.maxOfOrNull { it.totalListenMs.toFloat() } ?: 1f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Text(
            stringResource(R.string.stats_top_tracks),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        tracks.forEachIndexed { index, stat ->
            val value = when (sortMode) {
                StatsSortMode.PLAY_COUNT, StatsSortMode.RECENT, StatsSortMode.FIRST_PLAYED ->
                    stat.playCount.toFloat()
                StatsSortMode.LISTEN_TIME -> stat.totalListenMs.toFloat()
            }
            val fraction = if (maxValue > 0f) value / maxValue else 0f
            val animatedFraction by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(600, delayMillis = index * 80),
                label = "bar_$index"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stat.displayName(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(100.dp)
                )
                Spacer(Modifier.width(8.dp))
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp)
                ) {
                    val barHeight = size.height
                    val cornerPx = 6.dp.toPx()
                    drawRoundRect(
                        color = trackColor,
                        size = Size(size.width, barHeight),
                        cornerRadius = CornerRadius(cornerPx, cornerPx)
                    )
                    if (animatedFraction > 0f) {
                        drawRoundRect(
                            color = primaryColor,
                            size = Size(size.width * animatedFraction, barHeight),
                            cornerRadius = CornerRadius(cornerPx, cornerPx)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when (sortMode) {
                        StatsSortMode.LISTEN_TIME -> formatListenDuration(stat.totalListenMs)
                        else -> "${stat.playCount}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp)
                )
            }
        }
    }
}

@Composable
internal fun StatTrackRow(
    rank: Int,
    stat: TrackStat,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$rank",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (rank <= 3) FontWeight.Bold else FontWeight.Normal,
                        color = if (rank <= 3) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp)
                    )
                    val coverUrl = stat.customCoverUrl ?: stat.coverUrl
                    if (coverUrl != null) {
                        AsyncImage(
                            model = offlineCachedImageRequest(context, coverUrl),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            headlineContent = {
                Text(
                    stat.displayName(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
            },
            supportingContent = {
                Text(
                    stat.displayArtist(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.stats_play_count_value, stat.playCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        formatListenDuration(stat.totalListenMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

private fun TrackStat.displayName(): String = customName ?: name
private fun TrackStat.displayArtist(): String = customArtist ?: artist

private fun PlaybackStatsPeriod.labelResId(): Int = when (this) {
    PlaybackStatsPeriod.DAY -> R.string.stats_period_day
    PlaybackStatsPeriod.WEEK -> R.string.stats_period_week
    PlaybackStatsPeriod.MONTH -> R.string.stats_period_month
    PlaybackStatsPeriod.YEAR -> R.string.stats_period_year
    PlaybackStatsPeriod.ALL -> R.string.stats_period_all
}

private fun formatListenDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}
