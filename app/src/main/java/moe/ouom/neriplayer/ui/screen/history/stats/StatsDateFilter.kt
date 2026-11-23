package moe.ouom.neriplayer.ui.screen.history.stats

import android.os.Parcelable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsTimeRange

@Parcelize
internal data class StatsDateFilter(
    val period: PlaybackStatsPeriod = PlaybackStatsPeriod.ALL,
    val anchorDay: Long? = null,
    val trailingDays: Int = 0,
    val customEndDay: Long? = null
) : Parcelable {
    fun dates(today: LocalDate, firstWeekday: DayOfWeek): ClosedRange<LocalDate>? {
        val anchor = anchorDay?.let(LocalDate::ofEpochDay) ?: today
        val start = when (period) {
            PlaybackStatsPeriod.ALL -> when {
                customEndDay != null -> return anchor..LocalDate.ofEpochDay(customEndDay)
                trailingDays > 0 -> return today.minusDays(trailingDays - 1L)..today
                else -> return null
            }
            PlaybackStatsPeriod.DAY -> anchor
            PlaybackStatsPeriod.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(firstWeekday))
            PlaybackStatsPeriod.MONTH -> anchor.withDayOfMonth(1)
            PlaybackStatsPeriod.YEAR -> anchor.withDayOfYear(1)
        }
        val end = shiftDate(start, 1).minusDays(1)
        return start..minOf(end, today)
    }

    fun shifted(direction: Long, today: LocalDate, firstWeekday: DayOfWeek): StatsDateFilter {
        if (period == PlaybackStatsPeriod.ALL) return this
        val start = requireNotNull(dates(today, firstWeekday)).start
        val next = minOf(shiftDate(start, direction), today)
        val currentStart = copy(anchorDay = null).dates(today, firstWeekday)?.start
        return copy(anchorDay = next.toEpochDay().takeUnless { next == currentStart || next == today })
    }

    private fun shiftDate(date: LocalDate, distance: Long): LocalDate = when (period) {
        PlaybackStatsPeriod.WEEK -> date.plusWeeks(distance)
        PlaybackStatsPeriod.MONTH -> date.plusMonths(distance)
        PlaybackStatsPeriod.YEAR -> date.plusYears(distance)
        else -> date.plusDays(distance)
    }
}

internal val ClosedRange<LocalDate>?.isSingleDay: Boolean
    get() = this != null && start == endInclusive

internal fun ClosedRange<LocalDate>.toStatsTimeRange(zone: ZoneId) = PlaybackStatsTimeRange(
    startInclusive = start.atStartOfDay(zone).toInstant().toEpochMilli(),
    endExclusive = endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
)
