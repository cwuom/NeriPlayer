package moe.ouom.neriplayer.core.player.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `mobile confirmation consumes a Wi-Fi loss that crossed an unknown network gap`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertTrue(
            tracker.onDefaultNetworkLost(
                networkKey = "wifi",
                activeNetworkKey = null,
                activeNetworkKnown = true
            )
        )
        val mobile = tracker.observeDefaultNetwork(
            networkKey = "mobile",
            networkType = TrafficNetworkType.MOBILE,
            activeNetworkKey = "mobile"
        )

        assertTrue(mobile.shouldPause)
        assertFalse(
            tracker.observeDefaultNetwork(
                networkKey = "mobile",
                networkType = TrafficNetworkType.MOBILE,
                activeNetworkKey = "mobile"
            ).shouldPause
        )
    }

    @Test
    fun `Wi-Fi recovery clears a pending loss without requesting mobile protection`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi-old", networkType = TrafficNetworkType.WIFI)

        assertTrue(tracker.onDefaultNetworkLost(networkKey = "wifi-old"))
        val recovered = tracker.observeDefaultNetwork(
            networkKey = "wifi-new",
            networkType = TrafficNetworkType.WIFI,
            activeNetworkKey = "wifi-new"
        )

        assertTrue(recovered.becameWifi)
        assertFalse(recovered.shouldPause)
    }
}
