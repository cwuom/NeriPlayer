package moe.ouom.neriplayer.ui.screen.history.stats

import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriDesign
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.R as AppR
import java.text.NumberFormat

private const val CompactMetricGlassTintStrength = 0.7f

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
    if (NeriDesign.isClean) {
        CleanSegmentedTabs(
            labels = StatsPeriodOptions.map { stringResource(it.labelResId()) },
            selectedIndex = StatsPeriodOptions.indexOf(selectedPeriod).coerceAtLeast(0),
            onSelected = { index -> onPeriodSelected(StatsPeriodOptions[index]) },
            modifier = Modifier.padding(horizontal = 12.dp),
            maxFixedItems = StatsPeriodOptions.size
        )
        return
    }
    val overBackground = LocalHasCustomBackground.current
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
                label = { Text(stringResource(period.labelResId())) },
                // outlines vanish against busy wallpapers; a translucent fill holds up instead
                border = if (overBackground) null else FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedPeriod == period
                ),
                colors = if (overBackground) {
                    FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f)
                    )
                } else {
                    FilterChipDefaults.filterChipColors()
                }
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
    totalPlayCount: Long,
    totalListenMs: Long,
    trackCount: Long,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    stackMetrics: Boolean = false
) {
    if (compact) {
        val metrics: @Composable (Modifier) -> Unit = { metricModifier ->
            CompactStatMetric(
                Icons.Outlined.Headphones, statsNumber(totalPlayCount),
                stringResource(AppR.string.stats_total_plays),
                metricModifier.testTag("statsMetricPlays")
            )
            CompactStatMetric(
                Icons.Outlined.AccessTime, statsDuration(totalListenMs),
                stringResource(AppR.string.stats_total_time),
                metricModifier.testTag("statsMetricListenTime"), hero = true
            )
            CompactStatMetric(
                Icons.Outlined.LibraryMusic, statsNumber(trackCount),
                stringResource(AppR.string.stats_track_count),
                metricModifier.testTag("statsMetricTracks")
            )
        }
        if (stackMetrics) {
            Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                metrics(Modifier.fillMaxWidth())
            }
        } else {
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                metrics(Modifier.weight(1f))
            }
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val container = if (clean) colors.surfaceContainerLow else colors.primaryContainer
    val ink = if (clean) colors.onSurface else colors.onPrimaryContainer
    val groove = ink.copy(alpha = 0.08f)
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = modifier.fillMaxWidth(),
        shape = if (clean) NeriDesign.tokens.cardShape else neriRoundedShape(28.dp),
        fallbackColor = container,
        tintColor = container
    ) {
        if (clean) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(AppR.string.stats_total_time), style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, color = colors.primary)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val duration: @Composable () -> Unit = {
                        Text(statsDuration(totalListenMs), style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold, color = ink)
                    }
                    val metrics: @Composable () -> Unit = {
                        HeroMetric(statsNumber(totalPlayCount), stringResource(AppR.string.stats_total_plays), ink)
                        HeroMetric(statsNumber(trackCount), stringResource(AppR.string.stats_track_count), ink)
                    }
                    if (maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.25f) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { duration() }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { metrics() }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            duration()
                            HorizontalDivider(color = colors.outlineVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { metrics() }
                        }
                    }
                }
            }
            return@AdvancedGlassSurface
        }
        Column(
            Modifier.fillMaxWidth()
                .drawBehind {
                    val disc = Offset(size.width * 1.03f, size.height * 0.08f)
                    for (index in 1..4) {
                        drawCircle(groove, radius = (30 + index * 12).dp.toPx(), center = disc, style = Stroke(1.dp.toPx()))
                    }
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Headphones, null, Modifier.size(18.dp), tint = ink)
                Text(stringResource(AppR.string.stats_total_time), style = MaterialTheme.typography.labelLarge, color = ink)
            }
            Text(
                statsDuration(totalListenMs), style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Medium, color = ink
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HeroMetric(statsNumber(totalPlayCount), stringResource(AppR.string.stats_total_plays), ink)
                HeroMetric(statsNumber(trackCount), stringResource(AppR.string.stats_track_count), ink)
            }
        }
    }
}

@Composable
private fun HeroMetric(value: String, label: String, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ink)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ink)
    }
}

@Composable
private fun CompactStatMetric(icon: ImageVector, value: String, label: String, modifier: Modifier, hero: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val tint = colors.secondaryContainer
    val foreground = if (hero && !clean) colors.onSecondaryContainer else colors.onSurface
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = modifier,
        shape = neriRoundedShape(20.dp),
        fallbackColor = tint.copy(alpha = 0.25f),
        tintColor = tint.copy(alpha = tint.alpha * CompactMetricGlassTintStrength)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (clean) colors.primary else foreground)
                Text(label, style = MaterialTheme.typography.labelMedium, color = foreground)
            }
            Text(value, style = if (hero) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                fontWeight = if (clean) FontWeight.Bold else FontWeight.Medium, color = foreground)
        }
    }
}

@Composable
internal fun statsNumber(number: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    return formatter.format(number)
}

@Composable
internal fun statsDuration(ms: Long, exact: Boolean = false): String {
    val seconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = seconds / 60L % 60
    return when {
        exact && hours > 0L -> stringResource(AppR.string.stats_exact_duration, hours, minutes, seconds % 60L)
        exact && minutes > 0L -> stringResource(AppR.string.stats_exact_minutes, minutes, seconds % 60L)
        hours > 0L -> stringResource(AppR.string.stats_short_hour, hours, minutes)
        minutes > 0L -> stringResource(AppR.string.stats_short_min, minutes)
        else -> stringResource(AppR.string.stats_short_sec, seconds)
    }
}

@Composable
internal fun TopTracksBarChart(
    tracks: List<TrackStat>,
    sortMode: StatsSortMode,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    val maxValue = statsChartMaxValue(tracks, sortMode)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Text(
            stringResource(when (sortMode) {
                StatsSortMode.PLAY_COUNT -> AppR.string.stats_top_tracks
                StatsSortMode.LISTEN_TIME -> AppR.string.stats_top_duration
                StatsSortMode.RECENT -> AppR.string.stats_recent_counts
                StatsSortMode.FIRST_PLAYED -> AppR.string.stats_first_counts
            }),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        tracks.forEachIndexed { index, stat ->
            val valueLabel = if (sortMode == StatsSortMode.LISTEN_TIME) statsDuration(stat.totalListenMs)
            else statsNumber(stat.playCount.toLong())
            val animatedFraction by animateFloatAsState(
                targetValue = statsChartFraction(statsChartValue(stat, sortMode), maxValue),
                animationSpec = tween(600, delayMillis = index * 80),
                label = "bar_$index"
            )

            if (compact) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stat.displayName(), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            valueLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    StatsChartBar(animatedFraction, primaryColor, trackColor, Modifier.fillMaxWidth().height(8.dp))
                }
            } else Row(
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
                StatsChartBar(animatedFraction, primaryColor, trackColor, Modifier.weight(1f).height(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    valueLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp)
                )
            }
        }
    }
}

internal fun statsChartValue(stat: TrackStat, sortMode: StatsSortMode): Float =
    if (sortMode == StatsSortMode.LISTEN_TIME) stat.totalListenMs.toFloat() else stat.playCount.toFloat()

internal fun statsChartMaxValue(tracks: List<TrackStat>, sortMode: StatsSortMode): Float =
    tracks.maxOfOrNull { statsChartValue(it, sortMode) } ?: 1f

internal fun statsChartFraction(value: Float, maxValue: Float): Float =
    if (maxValue > 0f) value / maxValue else 0f

internal fun statsChartValueLabel(stat: TrackStat, sortMode: StatsSortMode): String =
    if (sortMode == StatsSortMode.LISTEN_TIME) formatListenDuration(stat.totalListenMs) else stat.playCount.toString()

@Composable
private fun StatsChartBar(fraction: Float, primaryColor: Color, trackColor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val cornerPx = 6.dp.toPx()
        drawRoundRect(color = trackColor, size = size, cornerRadius = CornerRadius(cornerPx, cornerPx))
        if (fraction > 0f) {
            drawRoundRect(color = primaryColor, size = Size(size.width * fraction, size.height),
                cornerRadius = CornerRadius(cornerPx, cornerPx))
        }
    }
}

@Composable
internal fun StatTrackRow(
    rank: Int,
    stat: TrackStat,
    modifier: Modifier = Modifier,
    offlineMode: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val clean = NeriDesign.isClean
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = neriRoundedShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.widthIn(min = if (clean) 28.dp else 32.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            statsNumber(rank.toLong()), style = MaterialTheme.typography.labelLarge,
                            modifier = if (!clean && rank <= 3) Modifier
                                .background(colors.secondaryContainer, NeriDesign.tokens.chipShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp) else Modifier,
                            fontWeight = if (rank <= 3) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                !clean && rank <= 3 -> colors.onSecondaryContainer
                                rank <= 3 -> colors.primary
                                else -> colors.onSurfaceVariant
                            }
                        )
                    }
                    StatsArtwork(stat.toPlaybackStatsSongItem().displayCoverUrl(), offlineMode, size = 46.dp)
                }
            },
            headlineContent = {
                Text(
                    stat.displayName(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (clean) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall,
                    fontWeight = if (clean) FontWeight.Medium else FontWeight.SemiBold
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
                        pluralStringResource(
                            AppR.plurals.stats_play_count_value,
                            stat.playCount,
                            stat.playCount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        statsDuration(stat.totalListenMs),
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
    PlaybackStatsPeriod.DAY -> AppR.string.stats_period_day
    PlaybackStatsPeriod.WEEK -> AppR.string.stats_period_week
    PlaybackStatsPeriod.MONTH -> AppR.string.stats_period_month
    PlaybackStatsPeriod.YEAR -> AppR.string.stats_period_year
    PlaybackStatsPeriod.ALL -> AppR.string.stats_period_all
}

internal fun formatListenDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}
