package moe.ouom.neriplayer.data.ltw

import moe.ouom.neriplayer.api.ltw.ws.shouldReconnectListenTogetherSocket
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.ltw.session.connection.shouldHoldListenTogetherBackgroundKeepAlive
import moe.ouom.neriplayer.data.ltw.session.connection.shouldHoldListenTogetherBackgroundWakeLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherBackgroundKeepAlivePolicyTest {

    @Test
    fun `paused active room keeps the background keep alive enabled`() {
        assertTrue(
            shouldHoldListenTogetherBackgroundKeepAlive(
                sessionActive = true,
                reconnectEnabled = true,
                applicationInForeground = false
            )
        )
    }

    @Test
    fun `foreground room does not hold an unnecessary background keep alive`() {
        assertFalse(
            shouldHoldListenTogetherBackgroundKeepAlive(
                sessionActive = true,
                reconnectEnabled = true,
                applicationInForeground = true
            )
        )
    }

    @Test
    fun `manual disconnect releases the background keep alive`() {
        assertFalse(
            shouldHoldListenTogetherBackgroundKeepAlive(
                sessionActive = true,
                reconnectEnabled = false,
                applicationInForeground = false
            )
        )
    }

    @Test
    fun `connected listener under the playback service lets the cpu sleep outside reconnect windows`() {
        assertFalse(wakeLock(isController = false, playbackServiceForeground = true, reconnecting = false))
        assertTrue(wakeLock(isController = false, playbackServiceForeground = true, reconnecting = true))
        assertTrue(wakeLock(isController = false, playbackServiceForeground = false, reconnecting = false))
        assertTrue(wakeLock(isController = true, playbackServiceForeground = true, reconnecting = false))
        assertFalse(wakeLock(keepAliveNeeded = false, isController = true, playbackServiceForeground = false, reconnecting = true))
    }

    private fun wakeLock(
        keepAliveNeeded: Boolean = true,
        isController: Boolean,
        playbackServiceForeground: Boolean,
        reconnecting: Boolean
    ) = shouldHoldListenTogetherBackgroundWakeLock(keepAliveNeeded, isController, playbackServiceForeground, reconnecting)

    @Test
    fun `silent socket is reconnected after the response timeout`() {
        assertTrue(
            shouldReconnectListenTogetherSocket(
                reconnectEnabled = true,
                connectionState = ListenTogetherConnectionState.CONNECTED,
                lastMessageAtElapsedMs = 10_000L,
                lastPingSentAtElapsedMs = 20_000L,
                nowElapsedMs = 60_001L,
                responseTimeoutMs = 40_000L
            )
        )
    }

    @Test
    fun `recent socket response prevents an unnecessary reconnect`() {
        assertFalse(
            shouldReconnectListenTogetherSocket(
                reconnectEnabled = true,
                connectionState = ListenTogetherConnectionState.CONNECTED,
                lastMessageAtElapsedMs = 55_000L,
                lastPingSentAtElapsedMs = 20_000L,
                nowElapsedMs = 60_000L,
                responseTimeoutMs = 40_000L
            )
        )
    }

    @Test
    fun `socket health check ignores missing ping or inactive connections`() {
        val inactiveStates = listOf(
            Triple(false, ListenTogetherConnectionState.CONNECTED, 20_000L),
            Triple(true, ListenTogetherConnectionState.DISCONNECTED, 20_000L),
            Triple(true, ListenTogetherConnectionState.CONNECTED, 0L)
        )
        inactiveStates.forEach { (reconnectEnabled, connectionState, lastPingSentAtElapsedMs) ->
            assertFalse(
                shouldReconnectListenTogetherSocket(
                    reconnectEnabled = reconnectEnabled,
                    connectionState = connectionState,
                    lastMessageAtElapsedMs = 10_000L,
                    lastPingSentAtElapsedMs = lastPingSentAtElapsedMs,
                    nowElapsedMs = 60_000L,
                    responseTimeoutMs = 40_000L
                )
            )
        }
    }

    @Test
    fun `socket response timeout uses an inclusive boundary`() {
        assertTrue(
            shouldReconnectListenTogetherSocket(
                reconnectEnabled = true,
                connectionState = ListenTogetherConnectionState.CONNECTED,
                lastMessageAtElapsedMs = 10_000L,
                lastPingSentAtElapsedMs = 20_000L,
                nowElapsedMs = 60_000L,
                responseTimeoutMs = 40_000L
            )
        )
        assertFalse(
            shouldReconnectListenTogetherSocket(
                reconnectEnabled = true,
                connectionState = ListenTogetherConnectionState.CONNECTED,
                lastMessageAtElapsedMs = 10_000L,
                lastPingSentAtElapsedMs = 20_000L,
                nowElapsedMs = 59_999L,
                responseTimeoutMs = 40_000L
            )
        )
    }

    @Test
    fun `socket health check rejects a clock rollback`() {
        assertFalse(
            shouldReconnectListenTogetherSocket(
                reconnectEnabled = true,
                connectionState = ListenTogetherConnectionState.CONNECTED,
                lastMessageAtElapsedMs = 10_000L,
                lastPingSentAtElapsedMs = 20_000L,
                nowElapsedMs = 19_999L,
                responseTimeoutMs = 1L
            )
        )
    }
}
