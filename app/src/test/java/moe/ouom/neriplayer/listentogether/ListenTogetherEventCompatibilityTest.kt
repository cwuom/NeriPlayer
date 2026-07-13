package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.compat.buildTrackFinishedLegacyFallbackEvent
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherMemberControlTargetCurrent
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherPendingMemberControlSatisfied
import moe.ouom.neriplayer.listentogether.compat.isUnsupportedTrackFinishedEventError
import moe.ouom.neriplayer.listentogether.compat.resolveListenTogetherLinkReadyState
import moe.ouom.neriplayer.listentogether.compat.resolveListenTogetherPlaybackCommandShouldPlay
import moe.ouom.neriplayer.listentogether.compat.shouldSuppressListenerControlWhileAwaitingStream
import moe.ouom.neriplayer.listentogether.mapping.withStreamUrl
import moe.ouom.neriplayer.listentogether.playback.boundedAroundStableKey
import moe.ouom.neriplayer.listentogether.playback.currentStableKey
import moe.ouom.neriplayer.listentogether.playback.expectedPositionMs
import moe.ouom.neriplayer.listentogether.playback.hasSameTrackSequenceAs
import moe.ouom.neriplayer.listentogether.playback.LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE
import moe.ouom.neriplayer.listentogether.playback.indexOfTrack
import moe.ouom.neriplayer.listentogether.playback.isListenTogetherSeekControlSatisfied
import moe.ouom.neriplayer.listentogether.playback.requestedStableKey
import moe.ouom.neriplayer.listentogether.playback.sameTrackAs
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherPlaybackState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherEventCompatibilityTest {

    @Test
    fun `unsupported track finished error is detected for legacy workers`() {
        assertTrue(
            isUnsupportedTrackFinishedEventError("unsupported event type: TRACK_FINISHED")
        )
        assertTrue(
            isUnsupportedTrackFinishedEventError("unsuppported event type: TRACK_FINISHED")
        )
        assertFalse(
            isUnsupportedTrackFinishedEventError("unsupported event type: SEEK")
        )
    }

    @Test
    fun `controller track finished fallback advances with legacy set track`() {
        val firstTrack = track("netease:1", "1")
        val nextTrack = track("netease:2", "2")
        val fallback = buildTrackFinishedLegacyFallbackEvent(
            event = ListenTogetherEvent(
                type = "TRACK_FINISHED",
                eventId = "evt-finished",
                clientTimeMs = 900L,
                positionMs = 188_000L,
                currentIndex = 1,
                nextIndex = 1,
                track = nextTrack,
                queue = listOf(firstTrack, nextTrack),
                shouldPlay = true,
                finishedTrackStableKey = firstTrack.stableKey
            ),
            isController = true,
            nowMs = 1_000L,
            eventIdFactory = { "evt-legacy" }
        )

        assertEquals("SET_TRACK", fallback?.type)
        assertEquals("evt-legacy", fallback?.eventId)
        assertEquals(1_000L, fallback?.clientTimeMs)
        assertEquals(0L, fallback?.positionMs)
        assertEquals(1, fallback?.currentIndex)
        assertNull(fallback?.nextIndex)
        assertEquals(nextTrack, fallback?.track)
        assertEquals(true, fallback?.shouldPlay)
        assertEquals("playing", fallback?.state)
        assertNull(fallback?.finishedTrackStableKey)
    }

    @Test
    fun `controller track finished fallback pauses at queue end`() {
        val lastTrack = track("netease:1", "1")
        val fallback = buildTrackFinishedLegacyFallbackEvent(
            event = ListenTogetherEvent(
                type = "TRACK_FINISHED",
                eventId = "evt-finished",
                positionMs = 205_000L,
                currentIndex = 0,
                nextIndex = 0,
                queue = listOf(lastTrack),
                shouldPlay = false,
                finishedTrackStableKey = lastTrack.stableKey
            ),
            isController = true,
            nowMs = 1_000L,
            eventIdFactory = { "evt-pause" }
        )

        assertEquals("PAUSE", fallback?.type)
        assertEquals("evt-pause", fallback?.eventId)
        assertEquals(205_000L, fallback?.positionMs)
        assertEquals(0, fallback?.currentIndex)
        assertEquals(false, fallback?.shouldPlay)
        assertEquals("paused", fallback?.state)
        assertNull(fallback?.finishedTrackStableKey)
    }

    @Test
    fun `listener track finished does not create legacy control fallback`() {
        val fallback = buildTrackFinishedLegacyFallbackEvent(
            event = ListenTogetherEvent(
                type = "TRACK_FINISHED",
                eventId = "evt-listener",
                shouldPlay = true
            ),
            isController = false,
            nowMs = 1_000L,
            eventIdFactory = { "evt-legacy" }
        )

        assertNull(fallback)
    }

    @Test
    fun `playback command keeps playing intent while media is still loading`() {
        assertTrue(
            resolveListenTogetherPlaybackCommandShouldPlay(
                commandType = "PLAY_FROM_QUEUE",
                commandShouldPlay = null,
                localTransportActive = true,
                localPlaying = false
            )
        )
    }

    @Test
    fun `explicit command should play wins over transport snapshot`() {
        assertFalse(
            resolveListenTogetherPlaybackCommandShouldPlay(
                commandType = "TRACK_FINISHED",
                commandShouldPlay = false,
                localTransportActive = true,
                localPlaying = true
            )
        )
    }

    @Test
    fun `link ready does not pause a room that is already playing`() {
        assertEquals(
            "playing",
            resolveListenTogetherLinkReadyState(
                roomPlaybackState = "playing",
                localTransportActive = false,
                localPlaying = false
            )
        )
    }

    @Test
    fun `link ready keeps pending local playback intent`() {
        assertEquals(
            "playing",
            resolveListenTogetherLinkReadyState(
                roomPlaybackState = "paused",
                localTransportActive = true,
                localPlaying = false
            )
        )
    }

    @Test
    fun `shareable queue keeps current track inside two thousand item window`() {
        val tracks = (0 until 2_500).map { index ->
            track("netease:$index", index.toString())
        }
        val bounded = tracks.boundedAroundStableKey("netease:2100")

        assertEquals(LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE, bounded.size)
        assertTrue(bounded.any { it.stableKey == "netease:2100" })
        assertEquals("netease:500", bounded.first().stableKey)
        assertEquals("netease:2499", bounded.last().stableKey)
    }

    @Test
    fun `listen together blocks local file stream url`() {
        val track = biliTrack().withStreamUrl(
            "file:///storage/emulated/0/Android/data/moe.ouom.neriplayer/files/Music/song.m4a"
        )

        assertNull(track.streamUrl)
    }

    @Test
    fun `listen together accepts trusted bili stream url`() {
        val url = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/audio.m4a"
        val track = biliTrack().withStreamUrl(url)

        assertEquals(url, track.streamUrl)
    }

    @Test
    fun `member seek request is satisfied by committed base position while playback advances`() {
        val playback = ListenTogetherPlaybackState(
            state = "playing",
            basePositionMs = 60_000L,
            baseTimestampMs = 1_000L
        )

        assertTrue(
            isListenTogetherSeekControlSatisfied(
                playback = playback,
                requestedPositionMs = 60_700L,
                satisfiedDriftMs = 1_500L
            )
        )
        assertFalse(
            isListenTogetherSeekControlSatisfied(
                playback = playback,
                requestedPositionMs = 63_000L,
                satisfiedDriftMs = 1_500L
            )
        )
    }

    @Test
    fun `playing expected position uses server clock offset and playback rate`() {
        val playback = ListenTogetherPlaybackState(
            state = "playing",
            basePositionMs = 10_000L,
            baseTimestampMs = 1_000L,
            playbackRate = 1.5
        )

        assertEquals(
            14_500L,
            playback.expectedPositionMs(
                nowMs = 3_000L,
                serverClockOffsetMs = 1_000L
            )
        )
    }

    @Test
    fun `paused expected position keeps base position`() {
        val playback = ListenTogetherPlaybackState(
            state = "paused",
            basePositionMs = 10_000L,
            baseTimestampMs = 1_000L
        )

        assertEquals(10_000L, playback.expectedPositionMs(nowMs = 20_000L))
    }

    @Test
    fun `room current stable key prefers explicit track over queue`() {
        val state = ListenTogetherRoomState(
            roomId = "ABC234",
            version = 1L,
            currentIndex = 1,
            track = track("netease:explicit", "explicit"),
            queue = listOf(
                track("netease:0", "0"),
                track("netease:queue", "queue")
            )
        )

        assertEquals("netease:explicit", state.currentStableKey())
    }

    @Test
    fun `event requested stable key falls back to indexed queue`() {
        val event = ListenTogetherEvent(
            type = "REQUEST_SEEK",
            currentIndex = 1,
            queue = listOf(
                track("netease:0", "0"),
                track("netease:target", "target")
            )
        )

        assertEquals("netease:target", event.requestedStableKey())
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
    fun `member control must target current room track`() {
        assertTrue(
            isListenTogetherMemberControlTargetCurrent(
                eventType = "REQUEST_PAUSE",
                requestedStableKey = "netease:1",
                currentStableKey = "netease:1"
            )
        )
        assertFalse(
            isListenTogetherMemberControlTargetCurrent(
                eventType = "REQUEST_PAUSE",
                requestedStableKey = "netease:old",
                currentStableKey = "netease:1"
            )
        )
    }

    @Test
    fun `listener play pause is suppressed while waiting for authoritative stream`() {
        assertTrue(
            shouldSuppressListenerControlWhileAwaitingStream(
                eventType = "REQUEST_PAUSE",
                awaitingAuthoritativeStream = true,
                localTrackHasDirectStream = false
            )
        )
        assertFalse(
            shouldSuppressListenerControlWhileAwaitingStream(
                eventType = "REQUEST_PAUSE",
                awaitingAuthoritativeStream = true,
                localTrackHasDirectStream = true
            )
        )
    }

    @Test
    fun `listener set track request is not suppressed by missing stream`() {
        assertFalse(
            shouldSuppressListenerControlWhileAwaitingStream(
                eventType = "REQUEST_SET_TRACK",
                awaitingAuthoritativeStream = true,
                localTrackHasDirectStream = false
            )
        )
    }

    @Test
    fun `listener play request is preserved while waiting for authoritative stream`() {
        assertFalse(
            shouldSuppressListenerControlWhileAwaitingStream(
                eventType = "REQUEST_PLAY",
                awaitingAuthoritativeStream = true,
                localTrackHasDirectStream = false
            )
        )
    }

    @Test
    fun `playback mode request does not require current track target`() {
        assertTrue(
            isListenTogetherMemberControlTargetCurrent(
                eventType = "REQUEST_PLAYBACK_MODE",
                requestedStableKey = null,
                currentStableKey = "netease:1"
            )
        )
    }

    @Test
    fun `playback mode is carried in room playback state`() {
        val state = ListenTogetherRoomState(
            roomId = "ABC234",
            version = 1L,
            playback = ListenTogetherPlaybackState(
                state = "playing",
                repeatMode = 2,
                shuffleEnabled = true
            )
        )

        assertEquals(2, state.playback.repeatMode)
        assertTrue(state.playback.shuffleEnabled == true)
    }

    @Test
    fun `legacy room playback state leaves playback mode unspecified`() {
        val state = ListenTogetherRoomState(
            roomId = "ABC234",
            version = 1L,
            playback = ListenTogetherPlaybackState(state = "playing")
        )

        assertNull(state.playback.repeatMode)
        assertNull(state.playback.shuffleEnabled)
    }

    @Test
    fun `playback mode request is satisfied by matching room playback mode`() {
        val state = ListenTogetherRoomState(
            roomId = "ABC234",
            version = 1L,
            playback = ListenTogetherPlaybackState(
                repeatMode = 1,
                shuffleEnabled = true
            )
        )
        val event = ListenTogetherEvent(
            type = "REQUEST_PLAYBACK_MODE",
            repeatMode = 1,
            shuffleEnabled = true
        )

        assertTrue(isListenTogetherPendingMemberControlSatisfied(event, state))
    }

    @Test
    fun `join auto pause flag alone does not pause a playing room`() {
        val state = roomState(playbackState = "playing")

        assertNull(
            resolveListenTogetherJoinAutoPauseCause(
                autoPauseOnJoin = true,
                role = "listener",
                state = state
            )
        )
    }

    @Test
    fun `join auto pause cause only marks listener state that is already paused`() {
        val state = roomState(playbackState = "paused")

        assertEquals(
            "JOIN_AUTO_PAUSE",
            resolveListenTogetherJoinAutoPauseCause(
                autoPauseOnJoin = true,
                role = "listener",
                state = state
            )
        )
        assertNull(
            resolveListenTogetherJoinAutoPauseCause(
                autoPauseOnJoin = true,
                role = "controller",
                state = state
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

    private fun biliTrack(): ListenTogetherTrack {
        return ListenTogetherTrack(
            stableKey = "bilibili:116843561884341:24547973984",
            channelId = ListenTogetherChannels.BILIBILI,
            audioId = "116843561884341",
            subAudioId = "24547973984",
            name = "暗号",
            artist = "周杰伦"
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

    private fun roomState(playbackState: String): ListenTogetherRoomState {
        return ListenTogetherRoomState(
            roomId = "ABC123",
            version = 1L,
            playback = ListenTogetherPlaybackState(
                state = playbackState,
                basePositionMs = 1_000L,
                baseTimestampMs = 2_000L
            )
        )
    }
}
