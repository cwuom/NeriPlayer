package moe.ouom.neriplayer.core.download.network

import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNetworkCallbackOrderingTest {
    @Test
    fun `unknown and still active loss callbacks cannot pause or advance the generation`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed("wifi", TrafficNetworkType.WIFI, initialGeneration = 10L)
        assertFalse(tracker.onDefaultNetworkLost("wifi", null, activeNetworkKnown = false))
        assertFalse(tracker.onDefaultNetworkLost("wifi", "wifi"))
        assertFalse(tracker.onDefaultNetworkLost("old", null))
        assertEquals(10L, tracker.currentGeneration())
        assertTrue(tracker.onDefaultNetworkLost("wifi", "mobile"))
        assertEquals(11L, tracker.currentGeneration())
        assertFalse(tracker.onDefaultNetworkLost("wifi", null))
    }

    @Test
    fun `handled wifi loss and mobile loss do not trigger a second pause`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed("wifi", TrafficNetworkType.WIFI)
        tracker.markWifiLossHandled()
        assertFalse(tracker.onDefaultNetworkLost("wifi"))
        tracker.seed("mobile", TrafficNetworkType.MOBILE)
        assertFalse(tracker.onDefaultNetworkLost("mobile"))
    }

    @Test
    fun `stale capabilities and duplicate observation keep one coherent generation`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed("wifi", TrafficNetworkType.WIFI, initialGeneration = -1L)
        assertEquals(0L, tracker.currentGeneration())
        val unknown = tracker.observeDefaultNetwork("wifi", TrafficNetworkType.WIFI, null, false)
        val stale = tracker.observeDefaultNetwork("old", TrafficNetworkType.MOBILE, "wifi")
        val repeated = tracker.observeDefaultNetwork("wifi", TrafficNetworkType.WIFI, "wifi")
        for (result in listOf(unknown, stale, repeated)) {
            assertFalse(result.changed)
            assertFalse(result.shouldPause)
            assertFalse(result.becameWifi)
            assertEquals(0L, result.generation)
        }
        assertTrue(tracker.onDefaultNetworkLost("wifi"))
        val recovery = tracker.observeDefaultNetwork("new-wifi", TrafficNetworkType.WIFI, "new-wifi")
        assertTrue(recovery.changed)
        assertTrue(recovery.becameWifi)
        assertFalse(recovery.shouldPause)
        assertEquals(2L, recovery.generation)
    }
}
