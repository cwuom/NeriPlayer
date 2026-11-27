package moe.ouom.neriplayer.data.stats

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackListeningHoursTest {
    private fun split(end: String, duration: Long, zone: String) =
        playbackListeningHours(Instant.parse(end).toEpochMilli(), duration, ZoneId.of(zone)).toList()

    @Test fun retainsTheActualStartOfPartialLocalHours() {
        val rows = split("2026-11-23T00:15:00Z", 600_000, "Asia/Kolkata")
        assertEquals(Instant.parse("2026-11-23T00:05:00Z").toEpochMilli(), rows.single().startAt)
        assertEquals(Instant.parse("2026-11-22T23:30:00Z").toEpochMilli(), rows.single().hourStartAt)
        assertEquals(5, rows.single().localHour)
    }

    @Test fun splitsAtMidnightUsingTheListeningTimeZone() {
        val hours = split("2026-11-22T16:00:05Z", 15_000, "Asia/Singapore")
        assertEquals(listOf(23, 0), hours.map { it.localHour })
        assertEquals(listOf(10_000L, 5_000L), hours.map { it.durationMs })
    }

    @Test fun skippedAndRepeatedDaylightSavingHoursKeepRealElapsedTime() {
        val spring = split("2026-03-08T07:00:05Z", 15_000, "America/New_York")
        assertEquals(listOf(1, 3), spring.map { it.localHour })
        val fall = split("2026-11-01T06:00:05Z", 15_000, "America/New_York")
        assertEquals(listOf(1, 1), fall.map { it.localHour })
        assertEquals(2, fall.distinctBy { it.startAt }.size)
        assertEquals(3_600_000L, fall[1].hourStartAt - fall[0].hourStartAt)
        assertEquals(15_000L, fall.sumOf { it.durationMs })
    }

    @Test fun halfHourTransitionDoesNotDelayTheFollowingHour() {
        val rows = split("2026-10-03T16:00:05Z", 3_610_000, "Australia/Lord_Howe")
        assertEquals(listOf(1, 2, 3), rows.map { it.localHour })
        assertEquals(Instant.parse("2026-10-03T15:30:00Z").toEpochMilli(), rows[1].hourStartAt)
        assertEquals(listOf(1_805_000L, 1_800_000L, 5_000L), rows.map { it.durationMs })
    }

    @Test fun repeatedHalfHourIsAnchoredToTheActualClockChange() {
        val repeated = split("2026-04-04T15:05:00Z", 5_000, "Australia/Lord_Howe").single()
        assertEquals(1, repeated.localHour)
        assertEquals(Instant.parse("2026-04-04T15:00:00Z").toEpochMilli(), repeated.hourStartAt)
    }
}
