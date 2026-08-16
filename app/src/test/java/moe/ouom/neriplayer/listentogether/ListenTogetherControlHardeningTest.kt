package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.control.buildListenTogetherForwardedControlSyntheticState
import moe.ouom.neriplayer.listentogether.playback.clampListenTogetherPositionMs
import moe.ouom.neriplayer.listentogether.playback.currentTrack
import moe.ouom.neriplayer.listentogether.playback.expectedPositionMs
import moe.ouom.neriplayer.listentogether.playback.wrapListenTogetherSingleTrackRepeatPosition
import moe.ouom.neriplayer.listentogether.playback.resolveListenTogetherQueueIndex
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherPlaybackState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueOperation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueReference
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import moe.ouom.neriplayer.listentogether.session.ListenTogetherForwardedRequestDeduper
import moe.ouom.neriplayer.listentogether.session.shouldRejectForwardedListenTogetherMemberControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherControlHardeningTest {

    // HIGH-1: 控制端服务端侧鉴权
    @Test
    fun `member control allowed passes any requester`() {
        assertFalse(
            shouldRejectForwardedListenTogetherMemberControl(
                requesterUuid = "listener-1",
                controllerUserUuid = "controller",
                allowMemberControl = true
            )
        )
    }

    @Test
    fun `member control disabled rejects listener requests`() {
        assertTrue(
            shouldRejectForwardedListenTogetherMemberControl(
                requesterUuid = "listener-1",
                controllerUserUuid = "controller",
                allowMemberControl = false
            )
        )
    }

    @Test
    fun `member control disabled still allows controller self request`() {
        assertFalse(
            shouldRejectForwardedListenTogetherMemberControl(
                requesterUuid = "controller",
                controllerUserUuid = "controller",
                allowMemberControl = false
            )
        )
    }

    @Test
    fun `member control disabled rejects blank or unknown requester`() {
        assertTrue(
            shouldRejectForwardedListenTogetherMemberControl(
                requesterUuid = null,
                controllerUserUuid = "controller",
                allowMemberControl = false
            )
        )
        assertTrue(
            shouldRejectForwardedListenTogetherMemberControl(
                requesterUuid = "   ",
                controllerUserUuid = "controller",
                allowMemberControl = false
            )
        )
    }

    // MEDIUM: 转发请求去重, 按 (requesterUuid, sequence), 无序号用事件 id
    @Test
    fun `deduper keeps per requester sequence independent`() {
        val deduper = ListenTogetherForwardedRequestDeduper()

        assertTrue(deduper.shouldProcess("A", sequence = 1L, eventId = null))
        assertFalse(deduper.shouldProcess("A", sequence = 1L, eventId = null))
        assertTrue(deduper.shouldProcess("A", sequence = 2L, eventId = null))
        assertFalse(deduper.shouldProcess("A", sequence = 1L, eventId = null))

        // 另一请求者的首个较小序号不应被 A 的进度误判为过期
        assertTrue(deduper.shouldProcess("B", sequence = 1L, eventId = null))
    }

    @Test
    fun `deduper falls back to event id when sequence missing`() {
        val deduper = ListenTogetherForwardedRequestDeduper()

        assertTrue(deduper.shouldProcess("A", sequence = 0L, eventId = "e1"))
        assertFalse(deduper.shouldProcess("A", sequence = null, eventId = "e1"))
        assertTrue(deduper.shouldProcess("A", sequence = null, eventId = "e2"))
    }

    @Test
    fun `deduper passes through when no sequence and no event id`() {
        val deduper = ListenTogetherForwardedRequestDeduper()

        assertTrue(deduper.shouldProcess("A", sequence = 0L, eventId = null))
        assertTrue(deduper.shouldProcess("A", sequence = null, eventId = "  "))
    }

    @Test
    fun `deduper clear resets state`() {
        val deduper = ListenTogetherForwardedRequestDeduper()

        assertTrue(deduper.shouldProcess("A", sequence = 5L, eventId = null))
        deduper.clear()
        assertTrue(deduper.shouldProcess("A", sequence = 5L, eventId = null))
    }

    // MEDIUM: position duration 上限钳制
    @Test
    fun `clamp floors negative and caps at duration`() {
        assertEquals(0L, clampListenTogetherPositionMs(-5L, 1_000L))
        assertEquals(1_000L, clampListenTogetherPositionMs(5_000L, 1_000L))
        assertEquals(500L, clampListenTogetherPositionMs(500L, 1_000L))
    }

    @Test
    fun `clamp only floors when duration unknown`() {
        assertEquals(5_000L, clampListenTogetherPositionMs(5_000L, 0L))
        assertEquals(0L, clampListenTogetherPositionMs(-1L, 0L))
    }

    // MEDIUM: baseTimestampMs<=0 回退当前时钟, 避免位置爆炸
    @Test
    fun `expected position falls back when base timestamp invalid`() {
        val playback = ListenTogetherPlaybackState(
            state = "playing",
            basePositionMs = 30_000L,
            baseTimestampMs = 0L,
            playbackRate = 1.0
        )

        val expected = playback.expectedPositionMs(nowMs = 1_700_000_000_000L)

        assertEquals(30_000L, expected)
    }

    @Test
    fun `expected position advances normally with valid base timestamp`() {
        val nowMs = 1_700_000_000_000L
        val playback = ListenTogetherPlaybackState(
            state = "playing",
            basePositionMs = 1_000L,
            baseTimestampMs = nowMs - 2_000L,
            playbackRate = 1.0
        )

        assertEquals(3_000L, playback.expectedPositionMs(nowMs = nowMs))
    }

    @Test
    fun `single repeat expected position wraps at track duration`() {
        val playback = ListenTogetherPlaybackState(
            state = "playing",
            basePositionMs = 58_000L,
            baseTimestampMs = 1_000L,
            repeatMode = 1
        )

        assertEquals(
            3_000L,
            playback.expectedPositionMs(
                nowMs = 6_000L,
                durationMs = 60_000L
            )
        )
    }

    @Test
    fun `single repeat wraps a supplied server position at track duration`() {
        assertEquals(
            3_000L,
            wrapListenTogetherSingleTrackRepeatPosition(
                positionMs = 63_000L,
                repeatMode = 1,
                durationMs = 60_000L
            )
        )
    }

    @Test
    fun `single repeat mode snapshot wraps an exact track end to zero`() {
        assertEquals(
            0L,
            wrapListenTogetherSingleTrackRepeatPosition(
                positionMs = 60_000L,
                repeatMode = 1,
                durationMs = 60_000L
            )
        )
    }

    @Test
    fun `expected position for paused returns base position`() {
        val playback = ListenTogetherPlaybackState(
            state = "paused",
            basePositionMs = 12_345L,
            baseTimestampMs = 0L
        )

        assertEquals(12_345L, playback.expectedPositionMs(nowMs = 1_700_000_000_000L))
    }

    // MEDIUM: 空 queue 的转发请求视为"不改动队列", 回退当前房间队列而非清空
    @Test
    fun `forwarded control with empty queue retains current queue`() {
        val track1 = listenTogetherTrack("k1")
        val track2 = listenTogetherTrack("k2")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 3L,
            queue = listOf(track1, track2),
            currentIndex = 0,
            track = track1
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queue = emptyList(),
            currentIndex = 1
        )
        val committedEvent = ListenTogetherEvent(type = "PLAY", positionMs = 5_000L)

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = committedEvent,
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf(track1, track2), next.queue)
        assertEquals("playing", next.playback.state)
    }

    @Test
    fun `legacy set queue with explicit empty snapshot clears the queue`() {
        val only = listenTogetherTrack("only")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 3L,
            queue = listOf(only),
            currentIndex = 0,
            track = only,
            playback = ListenTogetherPlaybackState(state = "playing")
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queue = emptyList(),
            currentIndex = -1
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = ListenTogetherEvent(type = "SET_QUEUE"),
            nowMs = 1_700_000_000_000L
        )

        assertTrue(next.queue.isEmpty())
        assertEquals(-1, next.currentIndex)
        assertEquals(null, next.track)
        assertEquals("paused", next.playback.state)
    }

    @Test
    fun `forwarded control with non-empty queue adopts requested queue`() {
        val current = listenTogetherTrack("k1")
        val incoming1 = listenTogetherTrack("k2")
        val incoming2 = listenTogetherTrack("k3")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 3L,
            queue = listOf(current),
            currentIndex = 0,
            track = current
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queue = listOf(incoming1, incoming2),
            currentIndex = 1
        )
        val committedEvent = ListenTogetherEvent(type = "PAUSE")

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = committedEvent,
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf(incoming1, incoming2), next.queue)
        assertEquals("paused", next.playback.state)
    }

    @Test
    fun `forwarded queue reorder keeps current track and playback state`() {
        val first = listenTogetherTrack("k1")
        val current = listenTogetherTrack("k2")
        val last = listenTogetherTrack("k3")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 3L,
            queue = listOf(first, current, last),
            currentIndex = 1,
            track = current,
            playback = ListenTogetherPlaybackState(
                state = "playing",
                basePositionMs = 12_000L
            )
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queue = listOf(current, first, last),
            currentIndex = 0,
            track = current,
            positionMs = 12_000L,
            shouldPlay = true,
            stateName = "playing"
        )
        val committedEvent = ListenTogetherEvent(
            type = "SET_QUEUE",
            positionMs = 12_000L
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = committedEvent,
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf(current, first, last), next.queue)
        assertEquals(0, next.currentIndex)
        assertEquals(current, next.track)
        assertEquals("playing", next.playback.state)
        assertEquals(12_000L, next.playback.basePositionMs)
    }

    @Test
    fun `forwarded queue index follows stable current track when numeric index is stale`() {
        val first = listenTogetherTrack("k1")
        val current = listenTogetherTrack("k2")

        assertEquals(
            1,
            resolveListenTogetherQueueIndex(
                queue = listOf(first, current),
                requestedIndex = 0,
                preferredStableKey = current.stableKey
            )
        )
    }

    @Test
    fun `forwarded queue reorder resolves a stale numeric index from its explicit stable key`() {
        val first = listenTogetherTrack("k1")
        val current = listenTogetherTrack("k2")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 3L,
            queue = listOf(first, current),
            currentIndex = 1,
            track = current
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queue = listOf(first, current),
            currentIndex = 0,
            track = current,
            requestTrackStableKey = current.stableKey
        )
        val committedEvent = ListenTogetherEvent(
            type = "SET_QUEUE",
            requestTrackStableKey = current.stableKey
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = committedEvent,
            nowMs = 1_700_000_000_000L
        )

        assertEquals(1, next.currentIndex)
        assertEquals(current, next.track)
    }

    @Test
    fun `forwarded v2 mutation replays move and preserves a remote insertion`() {
        val first = listenTogetherTrack("a")
        val remoteInsert = listenTogetherTrack("remote")
        val current = listenTogetherTrack("b")
        val last = listenTogetherTrack("c")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 7L,
            queue = listOf(first, remoteInsert, current, last),
            currentIndex = 2,
            track = current
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queue = listOf(first, current, last),
            queueMutation = queueMutation(
                baseRoomVersion = 6L,
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "move",
                        target = reference("c"),
                        anchor = reference("a"),
                        placement = "before"
                    )
                ),
                targetCurrent = reference("c")
            ),
            currentIndex = 0,
            track = last
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = ListenTogetherEvent(type = "SET_QUEUE"),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf(last, first, remoteInsert, current), next.queue)
        assertEquals(3, next.currentIndex)
        assertEquals(current, next.track)
    }

    @Test
    fun `forwarded mutation-only set track selects the requested duplicate occurrence`() {
        val firstDuplicate = listenTogetherTrack("dup", audioId = "first")
        val secondDuplicate = listenTogetherTrack("dup", audioId = "second")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 7L,
            queue = listOf(firstDuplicate, secondDuplicate),
            currentIndex = 0,
            track = firstDuplicate
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queueMutation = queueMutation(
                baseRoomVersion = 7L,
                operations = emptyList(),
                targetCurrent = reference("dup", occurrence = 1)
            ),
            currentIndex = 1,
            track = secondDuplicate
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = ListenTogetherEvent(type = "SET_TRACK"),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf(firstDuplicate, secondDuplicate), next.queue)
        assertEquals(1, next.currentIndex)
        assertEquals(secondDuplicate, next.track)
    }

    @Test
    fun `forwarded v2 mutation replays insert before an existing anchor`() {
        val first = listenTogetherTrack("a")
        val current = listenTogetherTrack("b")
        val inserted = listenTogetherTrack("x")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 7L,
            queue = listOf(first, current),
            currentIndex = 1,
            track = current
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queueMutation = queueMutation(
                baseRoomVersion = 7L,
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "insert",
                        anchor = reference("b"),
                        placement = "before",
                        track = inserted
                    )
                ),
                targetCurrent = reference("b")
            ),
            currentIndex = 2,
            track = current
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = ListenTogetherEvent(type = "SET_QUEUE"),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf(first, inserted, current), next.queue)
        assertEquals(2, next.currentIndex)
        assertEquals(current, next.track)
    }

    @Test
    fun `forwarded v2 mutation removes one track and selects target current`() {
        val first = listenTogetherTrack("a")
        val current = listenTogetherTrack("b")
        val last = listenTogetherTrack("c")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 7L,
            queue = listOf(first, current, last),
            currentIndex = 1,
            track = current
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queueMutation = queueMutation(
                baseRoomVersion = 7L,
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "remove",
                        target = reference("b")
                    )
                ),
                targetCurrent = reference("c")
            ),
            currentIndex = 1,
            track = last
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = ListenTogetherEvent(type = "SET_QUEUE"),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf(first, last), next.queue)
        assertEquals(1, next.currentIndex)
        assertEquals(last, next.track)
    }

    @Test
    fun `forwarded v2 mutation supports reorder without changing duplicate occurrences`() {
        val firstDuplicate = listenTogetherTrack("dup", audioId = "first")
        val middle = listenTogetherTrack("middle")
        val secondDuplicate = listenTogetherTrack("dup", audioId = "second")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 7L,
            queue = listOf(firstDuplicate, middle, secondDuplicate),
            currentIndex = 2,
            track = secondDuplicate
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queueMutation = queueMutation(
                baseRoomVersion = 7L,
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "reorder",
                        order = listOf(
                            reference("dup", occurrence = 1),
                            reference("middle"),
                            reference("dup", occurrence = 0)
                        )
                    )
                ),
                targetCurrent = reference("dup", occurrence = 1)
            ),
            currentIndex = 0,
            track = secondDuplicate
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = ListenTogetherEvent(type = "SET_QUEUE"),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(
            listOf(secondDuplicate, middle, firstDuplicate),
            next.queue
        )
        assertEquals(0, next.currentIndex)
        assertEquals(secondDuplicate, next.track)
    }

    @Test
    fun `forwarded v2 remove many can clear a single track queue`() {
        val only = listenTogetherTrack("only")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 7L,
            queue = listOf(only),
            currentIndex = 0,
            track = only,
            playback = ListenTogetherPlaybackState(state = "playing")
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queueMutation = queueMutation(
                baseRoomVersion = 7L,
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "remove_many",
                        order = listOf(reference("only"))
                    )
                )
            ),
            currentIndex = -1,
            track = null
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = ListenTogetherEvent(type = "SET_QUEUE"),
            nowMs = 1_700_000_000_000L
        )

        assertTrue(next.queue.isEmpty())
        assertEquals(-1, next.currentIndex)
        assertEquals(null, next.track)
        assertEquals("paused", next.playback.state)
    }

    @Test
    fun `forwarded v2 mutation ignores unknown references without losing queue`() {
        val first = listenTogetherTrack("a")
        val current = listenTogetherTrack("b")
        val currentState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 7L,
            queue = listOf(first, current),
            currentIndex = 1,
            track = current
        )
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            queueMutation = queueMutation(
                baseRoomVersion = 7L,
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "remove",
                        target = reference("missing")
                    )
                ),
                targetCurrent = reference("b")
            ),
            currentIndex = 1,
            track = current
        )

        val next = buildListenTogetherForwardedControlSyntheticState(
            currentState = currentState,
            message = message,
            committedEvent = ListenTogetherEvent(type = "SET_QUEUE"),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf(first, current), next.queue)
        assertEquals(1, next.currentIndex)
        assertEquals(current, next.track)
    }

    @Test
    fun `current track follows player index clamping for negative and high indexes`() {
        val first = listenTogetherTrack("a")
        val last = listenTogetherTrack("b")
        val state = ListenTogetherRoomState(
            roomId = "room-1",
            version = 7L,
            queue = listOf(first, last),
            currentIndex = -1,
            track = last
        )

        assertEquals(first, state.currentTrack())
        assertEquals(last, state.copy(currentIndex = 99, track = first).currentTrack())
    }

    private fun queueMutation(
        baseRoomVersion: Long,
        operations: List<ListenTogetherQueueOperation>,
        targetCurrent: ListenTogetherQueueReference? = null
    ) = moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueMutation(
        baseRoomVersion = baseRoomVersion,
        operations = operations,
        targetCurrent = targetCurrent
    )

    private fun reference(
        stableKey: String,
        occurrence: Int = 0
    ) = ListenTogetherQueueReference(
        stableKey = "channel:$stableKey",
        occurrence = occurrence
    )

    private fun listenTogetherTrack(
        stableKey: String,
        audioId: String = "audio-$stableKey"
    ) = ListenTogetherTrack(
        stableKey = "channel:$stableKey",
        channelId = "channel",
        audioId = audioId,
        name = "name-$stableKey",
        artist = "artist"
    )
}
