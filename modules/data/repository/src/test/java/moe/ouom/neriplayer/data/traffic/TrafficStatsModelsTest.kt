package moe.ouom.neriplayer.data.traffic

import java.util.Calendar
import java.util.GregorianCalendar
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.traffic.TrafficStatsBucket
import moe.ouom.neriplayer.data.model.traffic.TrafficStatsSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficStatsModelsTest {
    @Test
    fun `daily totals include the start boundary and exclude the next day`() {
        val start = GregorianCalendar(2026, Calendar.OCTOBER, 1).timeInMillis
        val noon = GregorianCalendar(2026, Calendar.OCTOBER, 1, 12, 0).timeInMillis
        val nextDay = GregorianCalendar(2026, Calendar.OCTOBER, 2).timeInMillis
        val buckets = listOf(bucket(start - 1L, 40), bucket(start, 1),
            bucket(noon, 2), bucket(nextDay, 80))

        assertEquals(summary(3), aggregateTrafficStatsForPeriod(buckets, PlaybackStatsPeriod.DAY, noon))
    }

    @Test
    fun `all time includes every stored bucket without applying date boundaries`() {
        val buckets = listOf(bucket(-1L, 1), bucket(Long.MAX_VALUE, 2))

        assertEquals(summary(3), aggregateTrafficStatsForPeriod(buckets, PlaybackStatsPeriod.ALL, 0L))
    }

    @Test
    fun `an empty period returns zero for every total`() {
        assertEquals(TrafficStatsSummary(),
            aggregateTrafficStatsForPeriod(emptyList(), PlaybackStatsPeriod.WEEK, 0L))
    }

    private fun bucket(day: Long, value: Int) = TrafficStatsBucket(dayStartAt = day,
        wifiBytes = value.toLong(), mobileBytes = value.toLong(), roamingBytes = value.toLong(),
        playbackNetworkBytes = value.toLong(), downloadNetworkBytes = value.toLong(),
        cacheHitBytes = value.toLong(), requestCount = value, cacheHitCount = value)

    private fun summary(value: Int) = TrafficStatsSummary(wifiBytes = value.toLong(),
        mobileBytes = value.toLong(), roamingBytes = value.toLong(),
        playbackNetworkBytes = value.toLong(), downloadNetworkBytes = value.toLong(),
        cacheHitBytes = value.toLong(), requestCount = value, cacheHitCount = value)
}
