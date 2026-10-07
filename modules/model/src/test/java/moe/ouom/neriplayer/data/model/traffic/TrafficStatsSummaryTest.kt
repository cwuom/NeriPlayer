package moe.ouom.neriplayer.data.model.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficStatsSummaryTest {

    @Test
    fun `traffic data exists once network or cache bytes were measured`() {
        assertFalse(TrafficStatsSummary().hasTrafficData)
        assertTrue(TrafficStatsSummary(roamingBytes = 1L).hasTrafficData)
        assertTrue(TrafficStatsSummary(cacheHitBytes = 1L).hasTrafficData)
    }

    @Test
    fun `cache hit rate is measured against playback bytes`() {
        val summary = TrafficStatsSummary(wifiBytes = 10L, mobileBytes = 20L, roamingBytes = 30L, playbackNetworkBytes = 300L, cacheHitBytes = 100L)

        assertEquals(60L, summary.networkBytes)
        assertEquals(400L, summary.measuredPlaybackBytes)
        assertEquals(0.25f, summary.cacheHitRate, 0f)
        assertEquals(0f, TrafficStatsSummary(wifiBytes = 10L).cacheHitRate, 0f)
    }
}
