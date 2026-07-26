package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.control.buildListenTogetherForwardedControlSyntheticState
import moe.ouom.neriplayer.listentogether.playback.clampListenTogetherPositionMs
import moe.ouom.neriplayer.listentogether.playback.expectedPositionMs
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherPlaybackState
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

    // MEDIUM: 转发请求去重，按 (requesterUuid, sequence)，无序号用事件 id
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

    // MEDIUM: baseTimestampMs<=0 回退当前时钟，避免位置爆炸
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
    fun `expected position for paused returns base position`() {
        val playback = ListenTogetherPlaybackState(
            state = "paused",
            basePositionMs = 12_345L,
            baseTimestampMs = 0L
        )

        assertEquals(12_345L, playback.expectedPositionMs(nowMs = 1_700_000_000_000L))
    }

    // MEDIUM: 空 queue 的转发请求视为“不改动队列”，回退当前房间队列而非清空
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

    private fun listenTogetherTrack(stableKey: String) = ListenTogetherTrack(
        stableKey = stableKey,
        channelId = "channel",
        audioId = "audio-$stableKey",
        name = "name-$stableKey",
        artist = "artist"
    )
}
