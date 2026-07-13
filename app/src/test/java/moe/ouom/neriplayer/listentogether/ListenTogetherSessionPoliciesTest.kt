package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.listentogether.session.LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS
import moe.ouom.neriplayer.listentogether.session.LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRecentEventTracker
import moe.ouom.neriplayer.listentogether.session.PendingMemberControlRequest
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherHeartbeatIntervalMs
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherRoomNotice
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherSessionRole
import moe.ouom.neriplayer.listentogether.session.retriedAt
import moe.ouom.neriplayer.listentogether.session.shouldApplyListenTogetherRoomStateToPlayer
import moe.ouom.neriplayer.listentogether.session.shouldDropListenTogetherControllerLocalEcho
import moe.ouom.neriplayer.listentogether.session.shouldRepairListenTogetherListenerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherSessionPoliciesTest {

    @Test
    fun `session role follows room controller identity when available`() {
        val state = roomState(
            controllerUserUuid = "controller-id"
        )

        assertEquals(
            "controller",
            resolveListenTogetherSessionRole(
                sessionUserId = " controller-id ",
                fallbackRole = "listener",
                state = state
            )
        )
        assertEquals(
            "listener",
            resolveListenTogetherSessionRole(
                sessionUserId = "member-id",
                fallbackRole = "controller",
                state = state
            )
        )
    }

    @Test
    fun `session role falls back before room controller identity is known`() {
        assertEquals(
            "listener",
            resolveListenTogetherSessionRole(
                sessionUserId = "member-id",
                fallbackRole = "listener",
                state = roomState()
            )
        )
        assertNull(
            resolveListenTogetherSessionRole(
                sessionUserId = "member-id",
                fallbackRole = null,
                state = roomState()
            )
        )
    }

    @Test
    fun `recent event tracker trims oldest event ids`() {
        val tracker = ListenTogetherRecentEventTracker(maxSize = 2)

        tracker.markOutbound("first")
        tracker.markOutbound("second")
        tracker.markOutbound("third")

        assertFalse(tracker.hasOutbound("first"))
        assertTrue(tracker.hasOutbound("second"))
        assertTrue(tracker.hasOutbound("third"))
    }

    @Test
    fun `recent event tracker ignores blank ids and clears both directions`() {
        val tracker = ListenTogetherRecentEventTracker(maxSize = 2)

        tracker.markOutbound("")
        tracker.markInbound("inbound")
        tracker.markOutbound("outbound")
        tracker.clear()

        assertFalse(tracker.hasOutbound(""))
        assertFalse(tracker.hasOutbound("outbound"))
        assertFalse(tracker.hasInbound("inbound"))
    }

    @Test
    fun `controller local echo drops near self caused state`() {
        val state = roomState(
            version = 4L,
            controllerUserUuid = "controller-id"
        )

        assertTrue(
            shouldDropListenTogetherControllerLocalEcho(
                state = state,
                cause = ListenTogetherCause(
                    userUuid = "controller-id",
                    type = "PLAY",
                    eventId = "event-1"
                ),
                latestVersion = 3L,
                currentUserId = "controller-id",
                lastControllerLocalControlAtElapsedMs = 1_000L,
                nowElapsedMs = 1_800L,
                controllerLocalControlCooldownMs = 1_200L
            )
        )
    }

    @Test
    fun `controller local echo keeps track finished and stale distant events`() {
        val state = roomState(
            version = 4L,
            controllerUserUuid = "controller-id"
        )

        assertFalse(
            shouldDropListenTogetherControllerLocalEcho(
                state = state,
                cause = ListenTogetherCause(userUuid = "controller-id", type = "TRACK_FINISHED"),
                latestVersion = 3L,
                currentUserId = "controller-id",
                lastControllerLocalControlAtElapsedMs = 1_000L,
                nowElapsedMs = 1_800L,
                controllerLocalControlCooldownMs = 1_200L
            )
        )
        assertFalse(
            shouldDropListenTogetherControllerLocalEcho(
                state = state,
                cause = ListenTogetherCause(userUuid = "controller-id", type = "PLAY"),
                latestVersion = 3L,
                currentUserId = "controller-id",
                lastControllerLocalControlAtElapsedMs = 1_000L,
                nowElapsedMs = 3_000L,
                controllerLocalControlCooldownMs = 1_200L
            )
        )
    }

    @Test
    fun `room notice resolves controller offline countdown`() {
        val state = roomState(
            roomStatus = ListenTogetherRoomStatuses.CONTROLLER_OFFLINE,
            controllerOfflineSince = 10_000L
        )

        assertEquals(
            "controller_offline:9",
            resolveListenTogetherRoomNotice(
                state = state,
                nowMs = 12_000L,
                controllerGracePeriodMs = 9 * 60 * 1_000L
            )
        )
    }

    @Test
    fun `room notice keeps fallback and closed reason precedence`() {
        assertEquals(
            "fallback",
            resolveListenTogetherRoomNotice(
                state = roomState(roomStatus = ListenTogetherRoomStatuses.ACTIVE),
                fallbackMessage = "fallback"
            )
        )
        assertEquals(
            "room expired",
            resolveListenTogetherRoomNotice(
                state = roomState(
                    roomStatus = ListenTogetherRoomStatuses.CLOSED,
                    closedReason = "room expired"
                )
            )
        )
    }

    @Test
    fun `player apply requires current matching room and non stale version`() {
        val current = roomState(version = 5L)

        assertTrue(shouldApplyListenTogetherRoomStateToPlayer(roomState(version = 5L), current))
        assertTrue(shouldApplyListenTogetherRoomStateToPlayer(roomState(version = 6L), current))
        assertFalse(shouldApplyListenTogetherRoomStateToPlayer(roomState(version = 4L), current))
        assertFalse(
            shouldApplyListenTogetherRoomStateToPlayer(
                candidateState = roomState(version = 6L).copy(roomId = "OTHER1"),
                currentState = current
            )
        )
        assertFalse(shouldApplyListenTogetherRoomStateToPlayer(current, null))
    }

    @Test
    fun `listener repair waits for websocket silence unless version gap exists`() {
        assertFalse(
            shouldRepairListenTogetherListenerState(
                nowElapsedMs = 100_000L,
                lastWebSocketMessageAtElapsedMs = 90_000L,
                lastRefreshAtElapsedMs = 0L,
                pendingVersionGap = -1L,
                webSocketSilenceTimeoutMs = 45_000L,
                repairMinIntervalMs = 30_000L
            )
        )
        assertTrue(
            shouldRepairListenTogetherListenerState(
                nowElapsedMs = 100_000L,
                lastWebSocketMessageAtElapsedMs = 50_000L,
                lastRefreshAtElapsedMs = 0L,
                pendingVersionGap = -1L,
                webSocketSilenceTimeoutMs = 45_000L,
                repairMinIntervalMs = 30_000L
            )
        )
        assertTrue(
            shouldRepairListenTogetherListenerState(
                nowElapsedMs = 100_000L,
                lastWebSocketMessageAtElapsedMs = 99_000L,
                lastRefreshAtElapsedMs = 0L,
                pendingVersionGap = 8L,
                webSocketSilenceTimeoutMs = 45_000L,
                repairMinIntervalMs = 30_000L
            )
        )
        assertFalse(
            shouldRepairListenTogetherListenerState(
                nowElapsedMs = 100_000L,
                lastWebSocketMessageAtElapsedMs = 50_000L,
                lastRefreshAtElapsedMs = 90_000L,
                pendingVersionGap = 8L,
                webSocketSilenceTimeoutMs = 45_000L,
                repairMinIntervalMs = 30_000L
            )
        )
    }

    @Test
    fun `heartbeat intervals stay below bundled worker controller timeout`() {
        val workerControllerTimeoutMs = 35_000L

        assertEquals(
            LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS,
            resolveListenTogetherHeartbeatIntervalMs(
                isPlaying = true,
                playingIntervalMs = LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS,
                pausedIntervalMs = LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS
            )
        )
        assertEquals(
            LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS,
            resolveListenTogetherHeartbeatIntervalMs(
                isPlaying = false,
                playingIntervalMs = LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS,
                pausedIntervalMs = LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS
            )
        )
        assertTrue(LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS < workerControllerTimeoutMs)
        assertTrue(LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS < workerControllerTimeoutMs)
    }

    @Test
    fun `member control retry preserves event identity and payload`() {
        val event = ListenTogetherEvent(
            type = "REQUEST_SEEK",
            eventId = "stable-event-id",
            clientTimeMs = 123L,
            clientInstanceId = "client",
            clientSequence = 7L,
            positionMs = 8_000L
        )
        val pending = PendingMemberControlRequest(
            event = event,
            createdAtElapsedMs = 1_000L,
            lastSentAtElapsedMs = 1_000L,
            attempts = 1
        )

        val retried = pending.retriedAt(nowElapsedMs = 4_000L)

        assertEquals(event, retried.event)
        assertEquals("stable-event-id", retried.event.eventId)
        assertEquals(4_000L, retried.lastSentAtElapsedMs)
        assertEquals(2, retried.attempts)
    }

    private fun roomState(
        version: Long = 1L,
        controllerUserUuid: String? = null,
        roomStatus: String = ListenTogetherRoomStatuses.ACTIVE,
        controllerOfflineSince: Long? = null,
        closedReason: String? = null
    ): ListenTogetherRoomState {
        return ListenTogetherRoomState(
            roomId = "ABC234",
            version = version,
            controllerUserUuid = controllerUserUuid,
            roomStatus = roomStatus,
            controllerOfflineSince = controllerOfflineSince,
            closedReason = closedReason
        )
    }
}
