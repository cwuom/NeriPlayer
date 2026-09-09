package moe.ouom.neriplayer.core.player.download

import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType

class DownloadNetworkPolicyTrackerTest {
    @Test
    fun `seed preserves persisted network generation`() {
        val tracker = DownloadNetworkPolicyTracker()

        tracker.seed(
            networkKey = "wifi",
            networkType = TrafficNetworkType.WIFI,
            initialGeneration = 41L
        )

        assertEquals(41L, tracker.currentGeneration())
        val observation = tracker.observeDefaultNetwork(
            networkKey = "mobile",
            networkType = TrafficNetworkType.MOBILE,
            activeNetworkKey = "mobile"
        )
        assertEquals(42L, observation.generation)
    }
}
