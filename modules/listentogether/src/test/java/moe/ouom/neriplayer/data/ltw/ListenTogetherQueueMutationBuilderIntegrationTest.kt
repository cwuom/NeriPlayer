package moe.ouom.neriplayer.data.ltw

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.ltw.control.ListenTogetherEventFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import moe.ouom.neriplayer.data.ltw.testing.FakeListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.ltw.testing.TestSongMapper
import org.junit.Test

class ListenTogetherQueueMutationBuilderIntegrationTest {

    @Test
    fun `schema two queue event omits stale full snapshot`() {
        val first = songItem("1")
        val second = songItem("2")
        val state = roomState(
            queue = listOf(track("1"), track("2")),
            currentIndex = 0
        )
        val factory = ListenTogetherEventFactory(
            playback = FakeListenTogetherPlaybackHost(),
            songMapper = TestSongMapper,
            roomStateProvider = { state },
            isControllerProvider = { true },
            eventIdFactory = { "event" },
            clientInstanceIdProvider = { "client" },
            clientSequenceFactory = { 1L },
            localPlaybackStateNameProvider = { "paused" },
            localTransportActiveProvider = { false }
        )

        val event = factory.buildSetQueueEvent(
            queue = listOf(first, second),
            currentIndex = 1,
            positionMs = 0L,
            commandShouldPlay = false
        )

        assertTrue(event?.queue == null)
        assertTrue(event?.queueMutation != null)
        assertEquals(7L, event?.queueMutation?.baseRoomVersion)
    }

    private fun roomState(
        queue: List<ListenTogetherTrack>,
        currentIndex: Int
    ): ListenTogetherRoomState {
        return ListenTogetherRoomState(
            roomId = "ABC234",
            version = 7L,
            schemaVersion = 2,
            queue = queue,
            currentIndex = currentIndex,
            playback = ListenTogetherPlaybackState(state = "paused")
        )
    }

    private fun track(stableKey: String): ListenTogetherTrack {
        return ListenTogetherTrack(
            stableKey = "netease:$stableKey",
            channelId = ListenTogetherChannels.NETEASE,
            audioId = stableKey,
            name = stableKey,
            artist = "artist"
        )
    }

    private fun songItem(audioId: String): SongItem {
        return SongItem(
            id = audioId.toLong(),
            name = audioId,
            artist = "artist",
            album = "",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = ListenTogetherChannels.NETEASE,
            audioId = audioId
        )
    }
}
