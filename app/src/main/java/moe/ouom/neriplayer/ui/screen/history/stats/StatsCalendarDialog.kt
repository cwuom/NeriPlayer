package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun StatsCalendarDialog(
    dateRange: Boolean,
    initialStart: LocalDate?,
    initialEnd: LocalDate?,
    today: LocalDate,
    firstWeekday: DayOfWeek,
    onDismiss: () -> Unit,
    onApply: (LocalDate, LocalDate) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { StatsCalendarFormat(locale) }
    var start by rememberSaveable { mutableStateOf(initialStart?.coerceIn(FirstStatsDate, today)) }
    var end by rememberSaveable { mutableStateOf(initialEnd?.coerceIn(FirstStatsDate, today).takeIf { dateRange }) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(start ?: today)) }
    var input by rememberSaveable { mutableStateOf(false) }
    var startInput by rememberSaveable(locale.toLanguageTag()) { mutableStateOf(start?.let(format::format).orEmpty()) }
    var endInput by rememberSaveable(locale.toLanguageTag()) { mutableStateOf(end?.let(format::format).orEmpty()) }
    val chosenStart = if (input) format.parse(startInput) else start
    val chosenEnd = if (input && dateRange) format.parse(endInput) else end
    val startError = when {
        input && startInput.isBlank() && endInput.isNotBlank() -> stringResource(R.string.stats_date_missing_start)
        input -> dateInputError(startInput, chosenStart, today, format)
        else -> null
    }
    val endError = if (input && dateRange) {
        dateInputError(endInput, chosenEnd, today, format) ?: if (chosenStart != null && chosenEnd != null && chosenEnd < chosenStart) {
            stringResource(R.string.stats_date_reversed)
        } else null
    } else null
    val valid = chosenStart != null && startError == null && endError == null
    DensityScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (dateRange) R.string.stats_custom_dates else R.string.stats_jump_to_date),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
                )
                HapticIconButton(modifier = Modifier.testTag("statsDateInputMode"), onClick = {
                    if (input && valid) {
                        start = chosenStart
                        end = chosenEnd
                        month = YearMonth.from(chosenEnd ?: chosenStart)
                    } else if (!input) {
                        startInput = start?.let(format::format).orEmpty()
                        endInput = end?.let(format::format).orEmpty()
                    }
                    input = !input
                }) {
                    Icon(if (input) Icons.Outlined.CalendarMonth else Icons.Outlined.Edit,
                        stringResource(if (input) R.string.stats_date_calendar else R.string.stats_date_keyboard))
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (input) {
                    val example = stringResource(R.string.stats_date_format_example, format.format(today))
                    DateInput(startInput, { startInput = it }, dateRange, false, example, startError)
                    if (dateRange) DateInput(endInput, { endInput = it }, true, true, example, endError)
                } else {
                    DateSummary(start, end, dateRange, format)
                    StatsCalendarMonth(month, today, start, end, firstWeekday, locale, { month = it }) { date ->
                        val first = start
                        if (!dateRange || first == null || end != null || date < first) {
                            start = date
                            end = null
                        } else end = date
                    }
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = { chosenStart?.let { onApply(it, chosenEnd ?: it) } }, enabled = valid) {
                Text(stringResource(CommonR.string.action_apply))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.action_cancel)) }
        }
    )
}

@Composable
private fun dateInputError(raw: String, value: LocalDate?, today: LocalDate, format: StatsCalendarFormat): String? = when {
    raw.isBlank() -> null
    value == null -> stringResource(R.string.stats_date_invalid)
    value < FirstStatsDate -> stringResource(R.string.stats_date_too_early, format.format(FirstStatsDate))
    value > today -> stringResource(R.string.stats_date_future)
    else -> null
}

@Composable
private fun DateInput(
    value: String, onValueChange: (String) -> Unit, dateRange: Boolean, isEnd: Boolean, example: String, message: String?
) {
    Column {
        MiuixSettingsTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            modifier = Modifier.testTag(if (isEnd) "statsDateEndInput" else "statsDateStartInput")
                .semantics { if (message != null) error(message) },
            label = { Text(dateLabel(dateRange, isEnd)) },
            placeholder = { Text(example) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
        )
        if (message != null) Text(message, Modifier.padding(start = 8.dp, top = 4.dp),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun dateLabel(range: Boolean, end: Boolean): String = stringResource(when {
    end -> R.string.stats_date_end
    range -> R.string.stats_date_start
    else -> R.string.stats_date_single
})

@Composable
private fun DateSummary(start: LocalDate?, end: LocalDate?, dateRange: Boolean, format: StatsCalendarFormat) {
    val placeholder = stringResource(R.string.stats_date_choose)
    val first = start?.let(format::format) ?: placeholder
    val last = end?.let(format::format) ?: placeholder
    val color = if (NeriDesign.isClean) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f)
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().background(color, RoundedCornerShape(14.dp)).padding(12.dp)) {
        val minWidth = maxOf(measure.measure(first, style).size.width, measure.measure(last, style).size.width)
        val stacked = dateRange && with(density) { 2 * minWidth + 16.dp.roundToPx() > maxWidth.roundToPx() }
        @Composable fun endpoint(isEnd: Boolean, modifier: Modifier) {
            Column(modifier) {
                Text(dateLabel(dateRange, isEnd), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (isEnd) last else first,
                    Modifier.fillMaxWidth().testTag(if (isEnd) "statsDateEnd" else "statsDateStart"),
                    style = style, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
            }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            endpoint(false, Modifier.fillMaxWidth())
            endpoint(true, Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            endpoint(false, Modifier.weight(1f))
            if (dateRange) endpoint(true, Modifier.weight(1f))
        }
    }
}
