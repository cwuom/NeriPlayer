package moe.ouom.neriplayer.data.traffic

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficStatsRecoveryTest {
    @Test
    fun localDeltaIsAddedWithoutReplacingNewerRoomTotals() {
        val baseline = listOf(
            TrafficStatsBucket(
                dayStartAt = 100L,
                wifiBytes = 100L,
                playbackNetworkBytes = 100L,
                requestCount = 1
            )
        )
        val room = listOf(
            TrafficStatsBucket(
                dayStartAt = 100L,
                wifiBytes = 140L,
                playbackNetworkBytes = 140L,
                requestCount = 2
            ),
            TrafficStatsBucket(dayStartAt = 200L, mobileBytes = 50L)
        )
        val current = listOf(
            TrafficStatsBucket(
                dayStartAt = 100L,
                wifiBytes = 130L,
                playbackNetworkBytes = 130L,
                requestCount = 2
            )
        )

        val recovered = mergeTrafficStatsRoomRecovery(
            roomSnapshot = room,
            recoveryBaseline = baseline,
            currentSnapshot = current
        )

        assertEquals(2, recovered.size)
        assertEquals(170L, recovered.first { it.dayStartAt == 100L }.wifiBytes)
        assertEquals(170L, recovered.first { it.dayStartAt == 100L }.playbackNetworkBytes)
        assertEquals(3, recovered.first { it.dayStartAt == 100L }.requestCount)
        assertEquals(50L, recovered.first { it.dayStartAt == 200L }.mobileBytes)
    }

    @Test
    fun explicitClearRemovesRecoveredRoomBuckets() {
        val recovered = mergeTrafficStatsRoomRecovery(
            roomSnapshot = listOf(TrafficStatsBucket(dayStartAt = 100L, wifiBytes = 10L)),
            recoveryBaseline = listOf(TrafficStatsBucket(dayStartAt = 100L, wifiBytes = 5L)),
            currentSnapshot = emptyList()
        )

        assertEquals(emptyList<TrafficStatsBucket>(), recovered)
    }
}
