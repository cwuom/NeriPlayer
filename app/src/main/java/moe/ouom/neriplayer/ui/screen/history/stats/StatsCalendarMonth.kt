package moe.ouom.neriplayer.ui.screen.history.stats

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun StatsCalendarMonth(
    month: YearMonth, today: LocalDate, start: LocalDate?, end: LocalDate?, firstWeekday: DayOfWeek,
    locale: Locale, onMonthChange: (YearMonth) -> Unit, onPick: (LocalDate) -> Unit
) {
    val monthFormatter = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)
    }
    val spoken = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val colors = MaterialTheme.colorScheme
    val endpoint = if (NeriDesign.isClean) colors.onSurface else colors.primary
    val onEndpoint = if (NeriDesign.isClean) colors.surface else colors.onPrimary
    val leading = (month.atDay(1).dayOfWeek.value - firstWeekday.value + 7) % 7
    val cells = leading + month.lengthOfMonth()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(month.format(monthFormatter), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            HapticIconButton(onClick = { onMonthChange(month.minusMonths(1)) },
                modifier = Modifier.testTag("statsDatePreviousMonth"), enabled = month > YearMonth.from(FirstStatsDate)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.stats_date_previous_month))
            }
            HapticIconButton(onClick = { onMonthChange(month.plusMonths(1)) },
                modifier = Modifier.testTag("statsDateNextMonth"), enabled = month < YearMonth.from(today)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.stats_date_next_month))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            repeat(7) { index ->
                val day = firstWeekday.plus(index.toLong())
                Text(day.getDisplayName(TextStyle.NARROW_STANDALONE, locale), Modifier.weight(1f),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
        repeat((cells + 6) / 7) { week ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                repeat(7) { index ->
                    val number = week * 7 + index - leading + 1
                    if (number !in 1..month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val day = month.atDay(number)
                        val edge = day == start || day == end
                        val inRange = start != null && end != null && day > start && day < end
                        val enabled = day <= today
                        val label = day.format(spoken)
                        Box(
                            modifier = Modifier.weight(1f).padding(vertical = 2.dp)
                                .clip(CircleShape)
                                .background(when {
                                    edge -> endpoint
                                    inRange -> endpoint.copy(alpha = 0.1f)
                                    else -> Color.Transparent
                                })
                                .then(if (day == today && !edge) Modifier.border(1.dp, endpoint.copy(alpha = 0.55f), CircleShape) else Modifier)
                                .clickable(enabled = enabled, role = Role.Button) { onPick(day) }
                                .semantics {
                                    contentDescription = label
                                    selected = edge || inRange
                                }.testTag("statsDateDay:$day").heightIn(min = 36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(numbers.format(number), maxLines = 1, softWrap = false,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (edge || day == today) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    edge -> onEndpoint
                                    !enabled -> colors.onSurface.copy(alpha = 0.35f)
                                    else -> colors.onSurface
                                })
                        }
                    }
                }
            }
        }
    }
}
