package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.session.ListenTogetherForegroundRecoveryAction
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherForegroundRecoveryAction
import moe.ouom.neriplayer.listentogether.session.shouldReconnectListenTogetherForegroundSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherForegroundRecoveryPolicyTest {

    @Test
    fun `foreground recovery reconnects an active disconnected room`() {
        assertEquals(
            ListenTogetherForegroundRecoveryAction.CONNECT,
            resolveListenTogetherForegroundRecoveryAction(
                connectionState = ListenTogetherConnectionState.DISCONNECTED,
                roomId = "ROOM01",
                wsUrl = "wss://example.test/room",
                reconnectEnabled = true
            )
        )
    }

    @Test
    fun `foreground recovery refreshes an active connected room`() {
        assertEquals(
            ListenTogetherForegroundRecoveryAction.REFRESH_ROOM_STATE,
            resolveListenTogetherForegroundRecoveryAction(
                connectionState = ListenTogetherConnectionState.CONNECTED,
                roomId = "ROOM01",
                wsUrl = "wss://example.test/room",
                reconnectEnabled = true
            )
        )
    }

    @Test
    fun `foreground recovery does not duplicate an in progress connection`() {
        assertEquals(
            ListenTogetherForegroundRecoveryAction.NONE,
            resolveListenTogetherForegroundRecoveryAction(
                connectionState = ListenTogetherConnectionState.CONNECTING,
                roomId = "ROOM01",
                wsUrl = "wss://example.test/room",
                reconnectEnabled = true
            )
        )
    }

    @Test
    fun `foreground recovery requires retained active session credentials`() {
        listOf(
            Pair(null, "wss://example.test/room"),
            Pair("ROOM01", null),
            Pair(" ", "wss://example.test/room")
        ).forEach { (roomId, wsUrl) ->
            assertEquals(
                ListenTogetherForegroundRecoveryAction.NONE,
                resolveListenTogetherForegroundRecoveryAction(
                    connectionState = ListenTogetherConnectionState.DISCONNECTED,
                    roomId = roomId,
                    wsUrl = wsUrl,
                    reconnectEnabled = true
                )
            )
        }
        assertEquals(
            ListenTogetherForegroundRecoveryAction.NONE,
            resolveListenTogetherForegroundRecoveryAction(
                connectionState = ListenTogetherConnectionState.DISCONNECTED,
                roomId = "ROOM01",
                wsUrl = "wss://example.test/room",
                reconnectEnabled = false
            )
        )
    }

    @Test
    fun `foreground socket probe reconnects only after the active socket stays silent`() {
        assertTrue(
            shouldReconnectListenTogetherForegroundSocket(
                reconnectEnabled = true,
                connectionState = ListenTogetherConnectionState.CONNECTED,
                expectedRoomId = "ROOM01",
                currentRoomId = "ROOM01",
                lastWebSocketMessageAtElapsedMs = 19_999L,
                probeStartedAtElapsedMs = 20_000L
            )
        )
        assertFalse(
            shouldReconnectListenTogetherForegroundSocket(
                reconnectEnabled = true,
                connectionState = ListenTogetherConnectionState.CONNECTED,
                expectedRoomId = "ROOM01",
                currentRoomId = "ROOM01",
                lastWebSocketMessageAtElapsedMs = 20_000L,
                probeStartedAtElapsedMs = 20_000L
            )
        )
    }

    @Test
    fun `foreground socket probe cannot reconnect a disconnected changed or abandoned room`() {
        val invalidStates = listOf(
            Triple(false, ListenTogetherConnectionState.CONNECTED, "ROOM01"),
            Triple(true, ListenTogetherConnectionState.DISCONNECTED, "ROOM01"),
            Triple(true, ListenTogetherConnectionState.CONNECTED, "ROOM02"),
            Triple(true, ListenTogetherConnectionState.CONNECTED, null)
        )

        invalidStates.forEach { (reconnectEnabled, connectionState, currentRoomId) ->
            assertFalse(
                shouldReconnectListenTogetherForegroundSocket(
                    reconnectEnabled = reconnectEnabled,
                    connectionState = connectionState,
                    expectedRoomId = "ROOM01",
                    currentRoomId = currentRoomId,
                    lastWebSocketMessageAtElapsedMs = 0L,
                    probeStartedAtElapsedMs = 20_000L
                )
            )
        }
    }
}
