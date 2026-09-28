package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.ui.dialog.formatTrafficRiskDownloadMessage
import moe.ouom.neriplayer.ui.dialog.trafficRiskNetworkLabelResource
import org.junit.Assert.assertEquals
import org.junit.Test

class AppGlobalDialogsPolicyTest {
    @Test
    fun riskMessageUsesTheSongNameForOneDownloadAndCountForBatches() {
        val song = SongItem(1, "Track", "Artist", "Album", 1, 1_000, null)
        fun request(songs: List<SongItem>) = GlobalDownloadManager.TrafficRiskDownloadRequest(
            id = 1,
            songs = songs,
            networkType = TrafficNetworkType.MOBILE,
            isBatch = songs.size > 1
        )
        fun message(songs: List<SongItem>) = formatTrafficRiskDownloadMessage(
            request(songs),
            "mobile",
            singleMessage = { network, name -> "$network:$name" },
            batchMessage = { count, network -> "$network:$count" }
        )

        assertEquals("mobile:Track", message(listOf(song)))
        assertEquals("mobile:", message(emptyList()))
        assertEquals("mobile:2", message(listOf(song, song.copy(id = 2))))
    }

    @Test
    fun trafficRiskNetworkLabelsCoverEveryTransport() {
        assertEquals(
            R.string.traffic_risk_network_roaming,
            trafficRiskNetworkLabelResource(TrafficNetworkType.ROAMING)
        )
        assertEquals(
            R.string.traffic_risk_network_mobile,
            trafficRiskNetworkLabelResource(TrafficNetworkType.MOBILE)
        )
        assertEquals(
            R.string.traffic_risk_network_wifi,
            trafficRiskNetworkLabelResource(TrafficNetworkType.WIFI)
        )
    }
}
