package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.icu.text.DateIntervalFormat
import android.icu.util.DateInterval
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.ui.component.overlay.DropdownMenu
import moe.ouom.neriplayer.ui.component.overlay.DropdownMenuItem
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsDateControls(
    selection: StatsDateFilter,
    today: LocalDate,
    firstWeekday: DayOfWeek,
    onChange: (StatsDateFilter) -> Unit
) {
    val dates = selection.dates(today, firstWeekday)
    val locale = LocalConfiguration.current.locales[0]
    val dateLabel = when {
        selection.trailingDays > 0 -> pluralStringResource(R.plurals.stats_recent_days, selection.trailingDays, selection.trailingDays)
        dates == null -> stringResource(R.string.stats_all_time)
        else -> remember(dates, locale) {
            DateIntervalFormat.getInstance("yMMMd", locale).apply {
                timeZone = android.icu.util.TimeZone.getTimeZone("UTC")
            }.format(DateInterval(dates.start.pickerMillis(), dates.endInclusive.pickerMillis()))
        }
    }
    var menu by remember { mutableStateOf(false) }
    var customPicker by rememberSaveable { mutableStateOf(false) }
    var jumpPicker by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(Modifier.weight(1f)) {
            HapticTextButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                Text(dateLabel, Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.stats_all_time)) }, onClick = {
                    menu = false
                    onChange(StatsDateFilter())
                })
                if (selection.period != PlaybackStatsPeriod.ALL) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.stats_jump_to_date)) }, onClick = {
                        menu = false
                        jumpPicker = true
                    })
                }
                for (days in listOf(7, 30, 90, 180, 365)) {
                    DropdownMenuItem(text = { Text(pluralStringResource(R.plurals.stats_recent_days, days, days)) }, onClick = {
                        menu = false
                        onChange(StatsDateFilter(trailingDays = days))
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.stats_custom_dates)) }, onClick = {
                    menu = false
                    customPicker = true
                })
            }
        }
        if (selection.period != PlaybackStatsPeriod.ALL) {
            HapticIconButton(
                onClick = { onChange(selection.shifted(-1, today, firstWeekday)) },
                enabled = dates != null && dates.start.year >= 1901
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.stats_previous_period))
            }
            HapticIconButton(
                onClick = { onChange(selection.shifted(1, today, firstWeekday)) },
                enabled = dates != null && dates.endInclusive < today
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.stats_next_period))
            }
        }
    }
    if (!customPicker && !jumpPicker) return
    val lastAllowed = today.pickerMillis()
    val selectable = remember(lastAllowed) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= lastAllowed
        }
    }
    if (jumpPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (dates?.start ?: today).pickerMillis(),
            yearRange = 1900..today.year,
            selectableDates = selectable
        )
        DatePickerDialog(
            onDismissRequest = { jumpPicker = false },
            confirmButton = {
                HapticTextButton(enabled = state.selectedDateMillis != null, onClick = {
                    val day = state.selectedDateMillis ?: return@HapticTextButton
                    val chosen = selection.copy(anchorDay = day.pickerDay())
                    val current = StatsDateFilter(selection.period)
                    onChange(if (chosen.dates(today, firstWeekday) == current.dates(today, firstWeekday)) current else chosen)
                    jumpPicker = false
                }) { Text(stringResource(CommonR.string.action_apply)) }
            },
            dismissButton = {
                HapticTextButton(onClick = { jumpPicker = false }) { Text(stringResource(CommonR.string.action_cancel)) }
            }
        ) { DatePicker(state = state) }
    } else {
        val state = rememberDateRangePickerState(
            yearRange = 1900..today.year,
            initialSelectedStartDateMillis = dates?.start?.pickerMillis(),
            initialSelectedEndDateMillis = dates?.endInclusive?.pickerMillis(),
            selectableDates = selectable
        )
        DatePickerDialog(
            onDismissRequest = { customPicker = false },
            confirmButton = {
                HapticTextButton(enabled = state.selectedStartDateMillis != null, onClick = {
                    val start = state.selectedStartDateMillis ?: return@HapticTextButton
                    val end = state.selectedEndDateMillis ?: start
                    onChange(StatsDateFilter(anchorDay = start.pickerDay(), customEndDay = end.pickerDay()))
                    customPicker = false
                }) { Text(stringResource(CommonR.string.action_apply)) }
            },
            dismissButton = {
                HapticTextButton(onClick = { customPicker = false }) { Text(stringResource(CommonR.string.action_cancel)) }
            }
        ) {
            DateRangePicker(state = state, title = {
                Text(stringResource(R.string.stats_custom_dates), Modifier.padding(16.dp), style = MaterialTheme.typography.labelLarge)
            })
        }
    }
}

private fun LocalDate.pickerMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.pickerDay() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
