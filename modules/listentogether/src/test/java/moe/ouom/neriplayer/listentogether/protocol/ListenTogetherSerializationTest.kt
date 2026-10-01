package moe.ouom.neriplayer.listentogether.protocol

import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherInitialSnapshot
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherSerializationTest {
    private val json = listenTogetherProtocolJson()

    @Test
    fun `wire format preserves defaults omits nulls and accepts future fields`() {
        val payload = json.encodeToString(ListenTogetherInitialSnapshot())
        assertTrue(payload.contains("\"shuffleEnabled\":false"))
        assertFalse(payload.contains("\"track\""))
        val decoded = json.decodeFromString<ListenTogetherEvent>(
            """{"type":"play","futureField":true}"""
        )
        assertEquals("play", decoded.type)
    }

    @Test
    fun `initial shuffle restore queue survives protocol serialization`() {
        val originalQueue = listOf(track("netease:1", "1"), track("netease:2", "2"))
        val snapshot = ListenTogetherInitialSnapshot(
            queue = originalQueue.reversed(),
            currentIndex = 0,
            shuffleEnabled = true,
            shuffleRestoreQueue = originalQueue
        )

        val decoded = json.decodeFromString<ListenTogetherInitialSnapshot>(
            json.encodeToString(snapshot)
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
