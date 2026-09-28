package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTogetherSerializationTest {
    @Test
    fun `initial shuffle restore queue survives protocol serialization`() {
        val originalQueue = listOf(track("netease:1", "1"), track("netease:2", "2"))
        val snapshot = ListenTogetherInitialSnapshot(
            queue = originalQueue.reversed(),
            currentIndex = 0,
            shuffleEnabled = true,
            shuffleRestoreQueue = originalQueue
        )

        val decoded = Json.decodeFromString<ListenTogetherInitialSnapshot>(
            Json.encodeToString(snapshot)
        )

        assertEquals(originalQueue, decoded.shuffleRestoreQueue)
    }

    private fun track(stableKey: String, audioId: String) = ListenTogetherTrack(
        stableKey = stableKey,
        channelId = ListenTogetherChannels.NETEASE,
        audioId = audioId,
        name = "Song $audioId",
        artist = "Artist"
    )
}
