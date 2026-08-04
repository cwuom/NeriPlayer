package moe.ouom.neriplayer.listentogether

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.listentogether.compat.buildTrackFinishedLegacyFallbackEvent
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherMemberControlTargetCurrent
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherPendingMemberControlSatisfied
import moe.ouom.neriplayer.listentogether.compat.isUnsupportedTrackFinishedEventError
import moe.ouom.neriplayer.listentogether.compat.resolveListenTogetherLinkReadyState
import moe.ouom.neriplayer.listentogether.compat.resolveListenTogetherPlaybackCommandShouldPlay
import moe.ouom.neriplayer.listentogether.compat.shouldSuppressListenerControlWhileAwaitingStream
import moe.ouom.neriplayer.listentogether.control.ListenTogetherEventFactory
import moe.ouom.neriplayer.listentogether.control.controlledPlaybackCommandTypes
import moe.ouom.neriplayer.listentogether.control.requestControlEventTypes
import moe.ouom.neriplayer.listentogether.control.resolveListenTogetherPlaybackCommandSnapshot
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommand
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.mapping.withStreamUrl
import moe.ouom.neriplayer.listentogether.mapping.withStreamUrls
import moe.ouom.neriplayer.listentogether.playback.boundedAroundStableKey
import moe.ouom.neriplayer.listentogether.playback.currentStableKey
import moe.ouom.neriplayer.listentogether.playback.expectedPositionMs
import moe.ouom.neriplayer.listentogether.playback.hasSameTrackMultisetAs
import moe.ouom.neriplayer.listentogether.playback.hasSameTrackSequenceAs
import moe.ouom.neriplayer.listentogether.playback.LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE
import moe.ouom.neriplayer.listentogether.playback.indexOfTrack
import moe.ouom.neriplayer.listentogether.playback.isListenTogetherSeekControlSatisfied
import moe.ouom.neriplayer.listentogether.playback.requestedStableKey
import moe.ouom.neriplayer.listentogether.playback.sameTrackAs
import moe.ouom.neriplayer.listentogether.playback.shouldApplyListenTogetherQueueUpdateWithoutReload
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
    fun `controller track finish publishes the refreshed shuffle queue`() {
        val nextFirst = songItem(ListenTogetherChannels.NETEASE, "1")
        val nextSecond = songItem(ListenTogetherChannels.NETEASE, "2")
        val completed = songItem(ListenTogetherChannels.NETEASE, "3")
        val refreshedQueue = listOf(nextFirst, nextSecond, completed)
        val factory = ListenTogetherEventFactory(
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
    fun `stream candidates preserve trusted order and legacy primary`() {
        val primary = "https://m701.music.126.net/primary.mp3"
        val backupA = "https://m702.music.126.net/backup-a.mp3"
        val backupB = "https://m703.music.126.net/backup-b.mp3"
        val ignored = "https://untrusted.example.com/ignored.mp3"

        val track = track("netease:1", "1").withStreamUrls(
            listOf(ignored, primary, backupA, primary, backupB)
        )

        assertEquals(primary, track.streamUrl)
        assertEquals(listOf(primary, backupA, backupB), track.streamUrls)
    }

    @Test
    fun `stream candidates survive protocol serialization and old primary remains optional`() {
        val primary = "https://m701.music.126.net/primary.mp3"
        val backup = "https://m702.music.126.net/backup.mp3"
        val decoded = Json.decodeFromString<ListenTogetherTrack>(
            Json.encodeToString(
                track("netease:1", "1").copy(
                    streamUrl = primary,
                    streamUrls = listOf(primary, backup)
                )
            )
        )
        val legacy = Json.decodeFromString<ListenTogetherTrack>(
            """{"stableKey":"netease:1","channelId":"netease","audioId":"1","streamUrl":"$primary","name":"Song 1","artist":"Artist"}"""
        )

        assertEquals(listOf(primary, backup), decoded.streamUrls)
        assertEquals(primary, decoded.streamUrl)
        assertTrue(legacy.streamUrls.isEmpty())
        assertEquals(primary, legacy.streamUrl)
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
    fun `event client sequence survives json round trip`() {
        val event = ListenTogetherEvent(
            type = "PLAY",
            eventId = "event-play",
            clientInstanceId = "client-instance",
            clientSequence = 42L
        )

        val decoded = Json.decodeFromString<ListenTogetherEvent>(
            Json.encodeToString(event)
        )

        assertEquals("client-instance", decoded.clientInstanceId)
        assertEquals(42L, decoded.clientSequence)
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

    @Test
    fun `empty queue update survives protocol serialization`() {
        val event = ListenTogetherEvent(
            type = "SET_QUEUE",
            currentIndex = -1,
            queue = emptyList(),
            shouldPlay = false,
            state = "paused"
        )

        val decoded = Json.decodeFromString<ListenTogetherEvent>(Json.encodeToString(event))

        assertEquals(-1, decoded.currentIndex)
        assertEquals(emptyList<ListenTogetherTrack>(), decoded.queue)
        assertFalse(decoded.shouldPlay ?: true)
    }

    @Test
    fun `queue update events participate in local and member control paths`() {
        assertTrue("SET_QUEUE" in controlledPlaybackCommandTypes)
        assertTrue("REQUEST_SET_QUEUE" in requestControlEventTypes)
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
    fun `queue update request may replace the current track`() {
        assertTrue(
            isListenTogetherMemberControlTargetCurrent(
                eventType = "REQUEST_SET_QUEUE",
                requestedStableKey = "netease:next",
                currentStableKey = "netease:current"
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
    fun `member queue update is satisfied only by the committed order`() {
        val first = track("netease:1", "1")
        val current = track("netease:2", "2")
        val reordered = listOf(current, first)
        val event = ListenTogetherEvent(
            type = "REQUEST_SET_QUEUE",
            currentIndex = 0,
            track = current,
            queue = reordered,
            requestTrackStableKey = current.stableKey
        )
        val committed = ListenTogetherRoomState(
            roomId = "ABC234",
            version = 2L,
            currentIndex = 0,
            track = current,
            queue = reordered
        )
        val previousOrder = committed.copy(
            queue = listOf(first, current),
            currentIndex = 1,
            track = current
        )

        assertTrue(isListenTogetherPendingMemberControlSatisfied(event, committed))
        assertFalse(isListenTogetherPendingMemberControlSatisfied(event, previousOrder))
    }

    @Test
    fun `member queue removal is satisfied by the committed queue`() {
        val first = track("netease:1", "1")
        val current = track("netease:2", "2")
        val next = track("netease:3", "3")
        val remaining = listOf(first, next)
        val event = ListenTogetherEvent(
            type = "REQUEST_SET_QUEUE",
            currentIndex = 1,
            track = next,
            queue = remaining,
            requestTrackStableKey = next.stableKey
        )
        val committed = ListenTogetherRoomState(
            roomId = "ABC234",
            version = 2L,
            currentIndex = 1,
            track = next,
            queue = remaining
        )
        val unmodified = committed.copy(
            currentIndex = 1,
            track = current,
            queue = listOf(first, current, next)
        )

        assertTrue(isListenTogetherPendingMemberControlSatisfied(event, committed))
        assertFalse(isListenTogetherPendingMemberControlSatisfied(event, unmodified))
    }

    @Test
    fun `member queue clear is satisfied by an empty committed queue`() {
        val event = ListenTogetherEvent(
            type = "REQUEST_SET_QUEUE",
            currentIndex = -1,
            queue = emptyList(),
            shouldPlay = false
        )
        val committed = ListenTogetherRoomState(
            roomId = "ABC234",
            version = 2L,
            currentIndex = -1,
            queue = emptyList()
        )

        assertTrue(isListenTogetherPendingMemberControlSatisfied(event, committed))
        assertFalse(
            isListenTogetherPendingMemberControlSatisfied(
                event,
                committed.copy(
                    currentIndex = 0,
                    track = track("netease:1", "1"),
                    queue = listOf(track("netease:1", "1"))
                )
            )
        )
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
