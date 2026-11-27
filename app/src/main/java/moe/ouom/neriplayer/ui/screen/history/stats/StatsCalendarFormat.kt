package moe.ouom.neriplayer.ui.screen.history.stats

import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.format.ResolverStyle
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.Locale

internal val FirstStatsDate: LocalDate = LocalDate.of(1900, 1, 1)

internal class StatsCalendarFormat(locale: Locale) {
    private val fields = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale
    ).mapNotNull { part ->
        when (part) {
            'y', 'u' -> ChronoField.YEAR
            'M', 'L' -> ChronoField.MONTH_OF_YEAR
            'd' -> ChronoField.DAY_OF_MONTH
            else -> null
        }
    }.distinct()

    private val numeric = DateTimeFormatterBuilder().apply {
        fields.forEachIndexed { index, field ->
            if (index > 0) appendLiteral('/')
            if (field == ChronoField.YEAR) appendValue(field, 4)
            else appendValue(field, 1, 2, SignStyle.NOT_NEGATIVE)
        }
    }.toFormatter(locale).withResolverStyle(ResolverStyle.STRICT)
    private val compact = DateTimeFormatterBuilder().apply {
        fields.forEach { field -> appendValue(field, if (field == ChronoField.YEAR) 4 else 2) }
    }.toFormatter(locale).withResolverStyle(ResolverStyle.STRICT)
    private val display = numeric.withDecimalStyle(DecimalStyle.of(locale))

    fun format(date: LocalDate): String = date.format(display)

    fun parse(input: String): LocalDate? {
        val normalized = input.trim().map { character ->
            character.digitToIntOrNull()?.digitToChar() ?: character
        }.joinToString("").replace('-', '/').replace('.', '/')
        val pattern = when {
            normalized.length == 8 && '/' !in normalized -> compact
            normalized.indexOf('/') == 4 -> isoNumeric
            else -> numeric
        }
        return try {
            LocalDate.parse(normalized, pattern)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private companion object {
        val isoNumeric: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu/M/d", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
    }
}
