package moe.ouom.neriplayer.ui.screen.history.stats

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import kotlin.math.roundToInt
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.stats.PlaybackStatsInsights
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun PlaybackStatsRhythm(
    insights: PlaybackStatsInsights,
    hourListenMs: List<Long>,
    singleDay: Boolean,
    modifier: Modifier = Modifier
) {
    if (insights.summary.trackCount == 0L) return
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val hours = hourListenMs.takeIf { it.size == 24 && it.any { ms -> ms > 0 } }.orEmpty()
    val weekdays = insights.weekdayListenMs.takeIf { !singleDay && it.size == 7 && it.any { ms -> ms > 0 } }.orEmpty()
    var hourly by rememberSaveable { mutableStateOf(false) }
    val showHours = hours.isNotEmpty() && (hourly || weekdays.isEmpty())
    val buckets = if (showHours) hours else weekdays
    val showListeningDays = !singleDay && insights.activeDays > 0
    val showPlayAverage = insights.summary.totalPlayCount > 1 && insights.averageListenMsPerPlay > 0
    val hasMetrics = showListeningDays || showPlayAverage
    val firstListen = insights.earliestListenAt.takeUnless { singleDay }
    if (!hasMetrics && buckets.isEmpty() && firstListen == null) return
    StatsInsightCard(modifier.testTag("statsRhythm")) {
        StatsHeading(stringResource(R.string.stats_rhythm))
        if (hasMetrics) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showListeningDays) {
                    RhythmMetric(stringResource(R.string.stats_active_days), statsNumber(insights.activeDays))
                    if (insights.activeDays > 1 && insights.averageDailyListenMs > 0) {
                        RhythmMetric(stringResource(R.string.stats_daily_average), statsDuration(insights.averageDailyListenMs))
                    }
                }
                if (showPlayAverage) {
                    RhythmMetric(stringResource(R.string.stats_play_average), statsDuration(insights.averageListenMsPerPlay))
                }
            }
        }
        if (buckets.isNotEmpty()) {
            if (hasMetrics) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
            val modes = listOf(stringResource(R.string.stats_weekday_mode), stringResource(R.string.stats_hour_mode))
            if (weekdays.isNotEmpty() && hours.isNotEmpty()) {
                StatsChoiceTabs(modes, if (showHours) 1 else 0) { hourly = it == 1 }
            } else Text(modes[if (showHours) 1 else 0], style = MaterialTheme.typography.titleSmall)
            val clock24 = DateFormat.is24HourFormat(context)
            val hourFormat = remember(locale, clock24) {
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (clock24) "Hm" else "hm"), locale)
            }
            val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
            val labels = remember(buckets.size, locale, hourFormat) {
                buckets.indices.map { i ->
                    if (showHours) LocalTime.of(i, 0).format(hourFormat)
                    else DayOfWeek.of(i + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale)
                }
            }
            val axis = remember(buckets.size, locale) {
                buckets.indices.map { index ->
                    if (!showHours) DayOfWeek.of(index + 1).getDisplayName(TextStyle.NARROW_STANDALONE, locale)
                    else if (index % 6 == 0 || index == 23) number.format(index)
                    else ""
                }
            }
            RhythmChart(buckets, labels, axis, hourly = showHours)
        }
        firstListen?.let { earliest ->
            val date = remember(earliest, locale) {
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(Date(earliest))
            }
            Text(stringResource(R.string.stats_since_date, date), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun RhythmMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge,
            fontWeight = if (NeriDesign.isClean) FontWeight.Bold else FontWeight.Medium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RhythmChart(values: List<Long>, labels: List<String>, axis: List<String>, hourly: Boolean) {
    val peak = values.maxOrNull() ?: return
    if (peak <= 0L) return
    var chosen by rememberSaveable(hourly) { mutableIntStateOf(-1) }
    val selected = chosen.takeIf { it in values.indices } ?: values.indexOf(peak)
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val accent = colors.primary
    val bar = if (clean) colors.primary.copy(alpha = 0.32f) else colors.secondary
    val guide = colors.outlineVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val duration = statsDuration(values[selected], exact = true)
    val current = stringResource(R.string.stats_chart_selected, labels[selected], duration)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(labels[selected], style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (values[selected] == peak) {
                        Text(stringResource(R.string.stats_peak), Modifier.background(colors.primaryContainer, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer)
                    }
                }
                Text(duration, style = MaterialTheme.typography.bodyMedium)
            }
            HapticIconButton(onClick = { chosen = selected - 1 }, enabled = selected > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.stats_previous_slot))
            }
            HapticIconButton(onClick = { chosen = selected + 1 }, enabled = selected < values.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.stats_next_slot))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(if (hourly) 84.dp else 92.dp)
            .testTag("statsRhythmChart")
            .semantics {
                contentDescription = current
                progressBarRangeInfo = ProgressBarRangeInfo(selected.toFloat(), 0f..values.lastIndex.toFloat(), values.size - 2)
                setProgress { position ->
                    chosen = position.roundToInt().coerceIn(values.indices)
                    true
                }
            }
            .pointerInput(values.size, rtl) {
                detectTapGestures { tap ->
                    val slot = (tap.x / size.width * values.size).toInt().coerceIn(values.indices)
                    chosen = if (rtl) values.lastIndex - slot else slot
                }
            }) {
            val slot = size.width / values.size
            val width = slot * (if (clean) 0.55f else 0.76f)
            val radius = CornerRadius(minOf(width * 0.45f, if (clean) 3.dp.toPx() else 8.dp.toPx()))
            repeat(3) { line ->
                val y = size.height * (line + 1) / 3f
                drawLine(guide.copy(alpha = if (line == 2) 0.8f else 0.38f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            values.forEachIndexed { index, value ->
                if (value > 0L) {
                    val height = (size.height * (value.toDouble() / peak)).toFloat().coerceAtLeast(1.dp.toPx())
                    val indexOnScreen = if (rtl) values.lastIndex - index else index
                    val left = (indexOnScreen + 0.5f) * slot - width / 2
                    drawRoundRect(if (selected == index) accent else bar, Offset(left, size.height - height),
                        Size(width, height), radius)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            axis.forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (label.isNotEmpty()) {
                        Text(label, Modifier.wrapContentWidth(unbounded = true), maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
