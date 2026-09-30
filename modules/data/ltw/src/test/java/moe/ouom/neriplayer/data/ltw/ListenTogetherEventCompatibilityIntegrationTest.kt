package moe.ouom.neriplayer.data.ltw

import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.ltw.control.ListenTogetherEventFactory
import moe.ouom.neriplayer.data.ltw.control.resolveListenTogetherPlaybackCommandSnapshot
import moe.ouom.neriplayer.data.ltw.testing.toSongItem
import moe.ouom.neriplayer.data.ltw.testing.hasSameTrackMultisetAs
import moe.ouom.neriplayer.data.ltw.testing.hasSameTrackSequenceAs
import moe.ouom.neriplayer.data.ltw.testing.indexOfTrack
import moe.ouom.neriplayer.data.ltw.testing.sameTrackAs
import moe.ouom.neriplayer.data.ltw.testing.shouldApplyListenTogetherQueueUpdateWithoutReload
import moe.ouom.neriplayer.data.ltw.testing.toShareableQueueSnapshot
import moe.ouom.neriplayer.data.ltw.testing.toShareableShuffleRestoreQueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import moe.ouom.neriplayer.data.ltw.testing.FakeListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.ltw.testing.TestSongMapper
import org.junit.Test

class ListenTogetherEventCompatibilityIntegrationTest {

    @Test
    fun `shuffle restore snapshot keeps the active bounded queue in original order`() {
        val originalQueue = listOf(
            songItem(ListenTogetherChannels.NETEASE, "1"),
            songItem(ListenTogetherChannels.NETEASE, "2"),
            songItem(ListenTogetherChannels.NETEASE, "3")
        )
        val activeQueue = listOf(
            track("netease:2", "2"),
            track("netease:3", "3")
        )

        val snapshot = originalQueue.toShareableShuffleRestoreQueueSnapshot(activeQueue)

        assertEquals(listOf("netease:2", "netease:3"), snapshot.map { it.stableKey })
    }

    @Test
    fun `controller track finish publishes the refreshed shuffle queue`() {
        val nextFirst = songItem(ListenTogetherChannels.NETEASE, "1")
        val nextSecond = songItem(ListenTogetherChannels.NETEASE, "2")
        val completed = songItem(ListenTogetherChannels.NETEASE, "3")
        val refreshedQueue = listOf(nextFirst, nextSecond, completed)
        val factory = ListenTogetherEventFactory(
            playback = FakeListenTogetherPlaybackHost(),
            songMapper = TestSongMapper,
            roomStateProvider = { null },
            isControllerProvider = { true },
            eventIdFactory = { "evt-refreshed-shuffle" },
            clientInstanceIdProvider = { "client" },
            clientSequenceFactory = { 1L },
            localPlaybackStateNameProvider = { "paused" },
            localTransportActiveProvider = { false }
        )

        val event = factory.buildTrackFinishedEvent(
            command = PlaybackCommand(
                type = "TRACK_FINISHED",
                source = PlaybackCommandSource.LOCAL,
                currentIndex = 0,
                shouldPlay = true
            ),
            queue = refreshedQueue,
            currentSong = completed,
            positionMs = 180_000L
        )

        assertEquals(0, event?.currentIndex)
        assertEquals(0, event?.nextIndex)
        assertEquals(nextFirst.id.toString(), event?.track?.audioId)
        assertEquals(
            listOf("1", "2", "3"),
            event?.queue?.map { track -> track.audioId }
        )
        assertEquals("netease:3", event?.finishedTrackStableKey)
    }

    @Test
    fun `rapid track commands retain their queue and position snapshot`() {
        val snapshot = resolveListenTogetherPlaybackCommandSnapshot(
            commandQueue = listOf("first-target"),
            commandPositionMs = 0L,
            currentQueue = listOf("second-target"),
            currentPositionMs = 8_000L
        )

        assertEquals(listOf("first-target"), snapshot.queue)
        assertEquals(0L, snapshot.positionMs)
    }

    @Test
    fun `queue snapshot excludes raw track urls until a verified stream is resolved`() {
        val rawPreviewUrl = "https://m701.music.126.net/preview.mp3"
        val source = songItem(ListenTogetherChannels.NETEASE, "1").copy(
            streamUrl = rawPreviewUrl
        )

        val (snapshot, currentIndex) = listOf(source).toShareableQueueSnapshot(
            currentIndex = 0,
            roomSettings = ListenTogetherRoomSettings(shareAudioLinks = true),
            resolvedCurrentStreamUrls = emptyList()
        )

        assertEquals(0, currentIndex)
        assertNull(snapshot.single().streamUrl)
        assertTrue(snapshot.single().streamUrls.isEmpty())
    }

    @Test
    fun `inbound shared stream candidates do not overwrite listener resolver input`() {
        val primary = "https://m701.music.126.net/primary.mp3"
        val backup = "https://m702.music.126.net/backup.mp3"
        val receivedTrack = track("netease:1", "1").copy(
            streamUrl = primary,
            streamUrls = listOf(primary, backup)
        )

        val listenerSong = receivedTrack.toSongItem()

        assertNull(listenerSong.streamUrl)
        assertEquals(listOf(primary, backup), receivedTrack.streamUrls)
    }

    @Test
    fun `same track sequence compares stable media identity`() {
        val first = songItem(
            channelId = ListenTogetherChannels.NETEASE,
            audioId = "1"
        )
        val same = songItem(
            channelId = ListenTogetherChannels.NETEASE,
            audioId = "1"
        )
        val different = songItem(
            channelId = ListenTogetherChannels.NETEASE,
            audioId = "2"
        )

        assertTrue(first.sameTrackAs(same))
        assertFalse(first.sameTrackAs(different))
        assertTrue(listOf(first).hasSameTrackSequenceAs(listOf(same)))
        assertEquals(1, listOf(different, same).indexOfTrack(first))
    }

    @Test
    fun `queue update keeps current song and changes following order`() {
        val first = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "1")
        val current = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "2")
        val last = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "3")
        val originalQueue = listOf(first, current, last)
        val reorderedQueue = listOf(current, first, last)

        assertFalse(originalQueue.hasSameTrackSequenceAs(reorderedQueue))
        assertTrue(originalQueue.hasSameTrackMultisetAs(reorderedQueue))
        assertTrue(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "SET_QUEUE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = reorderedQueue,
                incomingCurrentIndex = 0
            )
        )
        assertTrue(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "REQUEST_SET_QUEUE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = reorderedQueue,
                incomingCurrentIndex = 0
            )
        )
        assertFalse(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "SET_QUEUE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = reorderedQueue,
                incomingCurrentIndex = 1
            )
        )
    }

    @Test
    fun `queue update accepts duplicate tracks without changing membership`() {
        val first = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "1")
        val duplicateA = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "2")
        val duplicateB = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "2")
        val originalQueue = listOf(first, duplicateA, duplicateB)
        val reorderedQueue = listOf(duplicateB, first, duplicateA)

        assertTrue(originalQueue.hasSameTrackMultisetAs(reorderedQueue))
        assertTrue(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "SET_QUEUE",
                currentQueue = originalQueue,
                currentSong = duplicateA,
                incomingQueue = reorderedQueue,
                incomingCurrentIndex = 0
            )
        )
    }

    @Test
    fun `playback mode reorder updates the queue without reloading the current song`() {
        val first = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "1")
        val current = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "2")
        val last = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "3")
        val originalQueue = listOf(first, current, last)
        val reorderedQueue = listOf(current, last, first)

        assertTrue(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "PLAYBACK_MODE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = reorderedQueue,
                incomingCurrentIndex = 0
            )
        )
        assertTrue(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "REQUEST_PLAYBACK_MODE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = reorderedQueue,
                incomingCurrentIndex = 0
            )
        )
    }

    @Test
    fun `playback mode update cannot add remove or switch the current song`() {
        val first = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "1")
        val current = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "2")
        val last = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "3")
        val added = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "4")
        val originalQueue = listOf(first, current, last)

        assertFalse(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "PLAYBACK_MODE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = listOf(current, last, added, first),
                incomingCurrentIndex = 0
            )
        )
        assertFalse(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "REQUEST_PLAYBACK_MODE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = listOf(current, last),
                incomingCurrentIndex = 0
            )
        )
        assertFalse(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "PLAYBACK_MODE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = listOf(first, current, last),
                incomingCurrentIndex = 0
            )
        )
    }

    @Test
    fun `queue update applies additions and removals without reloading the current song`() {
        val first = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "1")
        val current = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "2")
        val last = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "3")
        val added = songItem(channelId = ListenTogetherChannels.NETEASE, audioId = "4")
        val originalQueue = listOf(first, current, last)

        assertTrue(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "SET_QUEUE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = listOf(first, current, added, last),
                incomingCurrentIndex = 1
            )
        )
        assertTrue(
            shouldApplyListenTogetherQueueUpdateWithoutReload(
                causeType = "REQUEST_SET_QUEUE",
                currentQueue = originalQueue,
                currentSong = current,
                incomingQueue = listOf(current, last),
                incomingCurrentIndex = 0
            )
        )
    }

    private fun track(
        stableKey: String,
        audioId: String
    ): ListenTogetherTrack {
        return ListenTogetherTrack(
            stableKey = stableKey,
            channelId = ListenTogetherChannels.NETEASE,
            audioId = audioId,
            name = "Song $audioId",
            artist = "Artist"
        )
    }

    private fun songItem(
        channelId: String,
        audioId: String
    ): moe.ouom.neriplayer.data.model.SongItem {
        return moe.ouom.neriplayer.data.model.SongItem(
            id = audioId.toLong(),
            name = "Song $audioId",
            artist = "Artist",
            album = "",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = channelId,
            audioId = audioId
        )
    }
}
