package moe.ouom.neriplayer.ui.screen.history.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsDateFilterTest {
    private val today = LocalDate.of(2024, 3, 12)
    private val monday = DayOfWeek.MONDAY

    @Test
    fun singleDayRhythmFollowsTheLocalDatesCoveredByTheFilter() {
        val day = today.toEpochDay()
        fun isSingleDay(filter: StatsDateFilter, asOf: LocalDate = today) =
            filter.dates(asOf, monday).isSingleDay

        assertEquals(true, isSingleDay(StatsDateFilter(PlaybackStatsPeriod.DAY)))
        assertEquals(true, isSingleDay(StatsDateFilter(PlaybackStatsPeriod.DAY, anchorDay = day - 1)))
        assertEquals(true, isSingleDay(StatsDateFilter(anchorDay = day, customEndDay = day)))
        assertEquals(false, isSingleDay(StatsDateFilter(anchorDay = day - 1, customEndDay = day)))
        assertEquals(true, isSingleDay(StatsDateFilter(trailingDays = 1)))
        assertEquals(false, isSingleDay(StatsDateFilter()))
        val firstOfYear = LocalDate.of(2024, 1, 1)
        for (period in listOf(PlaybackStatsPeriod.WEEK, PlaybackStatsPeriod.MONTH, PlaybackStatsPeriod.YEAR)) {
            assertEquals(true, isSingleDay(StatsDateFilter(period), firstOfYear))
            assertEquals(false, isSingleDay(StatsDateFilter(period), firstOfYear.plusDays(1)))
        }
    }

    @Test
    fun weeksFollowTheLocaleAndNeverExtendPastToday() {
        val week = StatsDateFilter(PlaybackStatsPeriod.WEEK)
        assertEquals(LocalDate.of(2024, 3, 11)..today, week.dates(today, monday))
        assertEquals(LocalDate.of(2024, 3, 10)..today, week.dates(today, DayOfWeek.SUNDAY))
        val previous = week.shifted(-1, today, monday)
        assertEquals(LocalDate.of(2024, 3, 4)..LocalDate.of(2024, 3, 10), previous.dates(today, monday))
        assertEquals(week, previous.shifted(1, today, monday))
    }

    @Test
    fun historicalMonthsAndYearsRespectLeapDaysAndBoundaries() {
        val february = StatsDateFilter(PlaybackStatsPeriod.MONTH).shifted(-1, today, monday)
        assertEquals(LocalDate.of(2024, 2, 1)..LocalDate.of(2024, 2, 29), february.dates(today, monday))
        val year = StatsDateFilter(PlaybackStatsPeriod.YEAR).shifted(-1, today, monday)
        assertEquals(LocalDate.of(2023, 1, 1)..LocalDate.of(2023, 12, 31), year.dates(today, monday))
    }

    @Test
    fun customDatesIncludeTheEntireLastLocalDayAcrossBothDstTransitions() {
        val zone = ZoneId.of("America/New_York")
        for ((day, hours) in listOf(LocalDate.of(2024, 3, 10) to 23, LocalDate.of(2024, 11, 3) to 25)) {
            val selection = StatsDateFilter(anchorDay = day.toEpochDay(), customEndDay = day.toEpochDay())
            val range = requireNotNull(selection.dates(day, monday)).toStatsTimeRange(zone)
            assertEquals(hours * 3_600_000L, range.endExclusive - requireNotNull(range.startInclusive))
        }
    }

    @Test
    fun rollingWindowsMoveWhilePinnedDatesAndAllTimeRetainTheirMeaning() {
        assertNull(StatsDateFilter().dates(today, monday))
        assertEquals(today.minusDays(29)..today, StatsDateFilter(trailingDays = 30).dates(today, monday))
        val selected = StatsDateFilter(anchorDay = today.minusDays(7).toEpochDay(), customEndDay = today.toEpochDay())
        assertEquals(selected.dates(today, monday), selected.dates(today.plusDays(1), monday))
    }
}
