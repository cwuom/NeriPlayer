package moe.ouom.neriplayer.listentogether.session.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.listentogether.protocol.model.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.protocol.model.room.ListenTogetherMember
import moe.ouom.neriplayer.listentogether.protocol.model.room.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.model.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.listentogether.protocol.model.session.ListenTogetherSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherConnectionRecoveryOwnerTest {
    @Test
    fun `duplicate failures schedule one reconnect and socket open resets its attempt`() = runTest {
        val port = FakePort(controller = true)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        owner.beginConnect()
        owner.scheduleReconnect("closed")
        owner.scheduleReconnect("failure")
        assertEquals(2, port.keepAliveReasons.count { it.startsWith("reconnect_scheduled") })

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, port.connects)
        owner.socketOpened()
        owner.scheduleReconnect("another_failure")
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, port.connects)
        owner.stop()
    }

    @Test
    fun `stopping cancels queued reconnect and prevents an abandoned room from connecting`() = runTest {
        val port = FakePort(controller = true)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        owner.beginConnect()
        owner.scheduleReconnect("closed")
        owner.stop()
        advanceTimeBy(20_000L)
        runCurrent()
        assertFalse(owner.enabled)
        assertEquals(0, port.connects)
    }

    @Test
    fun `reconnect rejects missing targets and caps repeated failures`() = runTest {
        val port = FakePort(controller = true)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        owner.scheduleReconnect("disabled")
        owner.beginConnect()
        port.currentSession = port.currentSession.copy(wsUrl = null)
        owner.scheduleReconnect("missing_socket")
        port.currentSession = port.currentSession.copy(wsUrl = "wss://example.test/room", roomId = null)
        owner.scheduleReconnect("missing_room")
        port.currentSession = port.currentSession.copy(
            roomId = "room", connectionState = ListenTogetherConnectionState.CONNECTING
        )
        owner.scheduleReconnect("connecting")
        assertEquals(0, port.connects)

        port.currentSession = port.currentSession.copy(connectionState = ListenTogetherConnectionState.DISCONNECTED)
        repeat(15) {
            owner.scheduleReconnect("failed")
            advanceTimeBy(15_000L)
            runCurrent()
        }
        owner.scheduleReconnect("max_attempts")
        assertEquals(15, port.connects)
        assertEquals(listOf("reconnect_max_attempts_exceeded"), port.closedReasons)
        owner.stop()
    }

    @Test
    fun `missing listener membership rejoins once and reconnects with fresh credentials`() = runTest {
        val port = FakePort(controller = false)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        val room = ListenTogetherRoomState(roomId = "room", version = 1L)
        owner.recoverMissingListenerMembership(room, "state_update")
        owner.recoverMissingListenerMembership(room, "duplicate_state")
        runCurrent()
        assertEquals(1, port.rejoins)
        assertEquals(1, port.connects)
        assertEquals(1, port.recoveryStarts)
        assertTrue(owner.enabled)
        owner.stop()
    }

    @Test
    fun `present or closed membership and controller never start rejoin`() = runTest {
        val port = FakePort(controller = false)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        val room = ListenTogetherRoomState(roomId = "room", version = 1L)
        val member = ListenTogetherMember(userUuid = "listener", role = "listener", joinedAt = 0L)
        owner.recoverMissingListenerMembership(room.copy(members = listOf(member)), "present")
        owner.recoverMissingListenerMembership(room.copy(roomStatus = ListenTogetherRoomStatuses.CLOSED), "closed")
        port.controller = true
        owner.recoverMissingListenerMembership(room, "controller")
        assertFalse(owner.recoverFromMembershipError("unrelated", "socket_error"))
        assertFalse(owner.recoverFromMembershipError(null, "socket_error"))
        assertEquals(0, port.recoveryStarts)
        owner.stop()
    }

    @Test
    fun `scheduled listener reconnect first recovers membership and nonterminal failure retries`() = runTest {
        val port = FakePort(controller = false)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        owner.beginConnect()
        owner.scheduleReconnect("closed")
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, port.rejoins)
        assertEquals(1, port.connects)
        owner.stop()

        val retryPort = FakePort(controller = false)
        retryPort.rejoinError = IllegalStateException("temporary failure")
        val retryOwner = ListenTogetherConnectionRecoveryOwner(this, retryPort)
        assertTrue(retryOwner.recoverFromMembershipError("member missing", "socket_error"))
        runCurrent()
        assertEquals("temporary failure", retryPort.currentSession.lastError)
        assertTrue(retryPort.keepAliveReasons.any { it.startsWith("reconnect_scheduled") })
        retryOwner.stop()
    }

    @Test
    fun `terminal membership failure closes the room without retrying`() = runTest {
        val port = FakePort(controller = false)
        port.rejoinError = IllegalStateException("unauthorized")
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        assertTrue(owner.recoverFromMembershipError("member not in room", "socket_error"))
        runCurrent()
        assertEquals(listOf("unauthorized"), port.closedReasons)
        assertEquals(0, port.connects)
        owner.stop()
    }

    @Test
    fun `stop between membership snapshot and lock cannot restart the old session`() = runTest {
        val port = FakePort(controller = false)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        port.onSessionRead = { owner.stop() }
        assertFalse(owner.recoverFromMembershipError("member missing", "stale_callback"))
        runCurrent()
        assertEquals(0, port.recoveryStarts)
        assertEquals(0, port.rejoins)
        assertFalse(owner.enabled)
    }

    @Test
    fun `stop during recovery preparation does not launch a rejoin`() = runTest {
        val port = FakePort(controller = false)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        port.onRecoveryStart = { owner.stop() }
        assertFalse(owner.recoverFromMembershipError("member missing", "stale_callback"))
        runCurrent()
        assertEquals(0, port.rejoins)
        assertFalse(owner.enabled)
    }

    @Test
    fun `foreground connect between reconnect checks prevents an obsolete job`() = runTest {
        val port = FakePort(controller = true)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        owner.beginConnect()
        port.onKeepAlive = {
            port.currentSession = port.currentSession.copy(connectionState = ListenTogetherConnectionState.CONNECTING)
            owner.beginConnect()
        }
        owner.scheduleReconnect("stale_close")
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(0, port.connects)
        owner.stop()
    }

    @Test
    fun `delayed reconnect ignores a changed socket target or an in progress connection`() = runTest {
        val port = FakePort(controller = true)
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        owner.beginConnect()
        owner.scheduleReconnect("old_target")
        port.currentSession = port.currentSession.copy(wsUrl = "wss://example.test/replaced")
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(0, port.connects)

        owner.scheduleReconnect("now_connecting")
        port.currentSession = port.currentSession.copy(connectionState = ListenTogetherConnectionState.CONNECTING)
        advanceTimeBy(3_500L)
        runCurrent()
        assertEquals(0, port.connects)
        owner.stop()
    }

    @Test
    fun `stopped suspended rejoin cannot reconnect or publish a stale error`() = runTest {
        val port = FakePort(controller = false)
        val gate = CompletableDeferred<Unit>()
        port.rejoinGate = gate
        port.rejoinError = IllegalStateException("stale failure")
        val owner = ListenTogetherConnectionRecoveryOwner(this, port)
        assertTrue(owner.recoverFromMembershipError("member missing", "old_room"))
        runCurrent()
        owner.stop()
        port.currentSession = port.currentSession.copy(roomId = "new_room", lastError = null)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, port.connects)
        assertEquals(null, port.currentSession.lastError)
    }

    private class FakePort(var controller: Boolean) : ListenTogetherConnectionRecoveryPort {
        var currentSession = ListenTogetherSessionState(
            baseUrl = "https://example.test",
            roomId = "room",
            userUuid = "listener",
            nickname = "listener",
            wsUrl = "wss://example.test/room",
            connectionState = ListenTogetherConnectionState.DISCONNECTED
        )
        val keepAliveReasons = mutableListOf<String>()
        val closedReasons = mutableListOf<String>()
        var recoveryStarts = 0
        var rejoins = 0
        var connects = 0
        var rejoinError: Throwable? = null
        var rejoinGate: CompletableDeferred<Unit>? = null
        var onSessionRead: (() -> Unit)? = null
        var onKeepAlive: (() -> Unit)? = null
        var onRecoveryStart: (() -> Unit)? = null

        override fun session(): ListenTogetherSessionState {
            val snapshot = currentSession
            onSessionRead?.let { action ->
                onSessionRead = null
                action()
            }
            return snapshot
        }
        override fun isController(session: ListenTogetherSessionState): Boolean = controller
        override fun updateBackgroundKeepAlive(reason: String) {
            keepAliveReasons += reason
            onKeepAlive?.let { action ->
                onKeepAlive = null
                action()
            }
        }
        override fun connectWebSocket() { connects++ }
        override fun closeRoomLocally(reason: String) { closedReasons += reason }
        override fun beginMembershipRecovery(session: ListenTogetherSessionState) {
            recoveryStarts++
            currentSession = session.copy(connectionState = ListenTogetherConnectionState.CONNECTING)
            onRecoveryStart?.let { action ->
                onRecoveryStart = null
                action()
            }
        }
        override suspend fun rejoinRoom(identity: ListenTogetherRejoinIdentity) {
            rejoins++
            withContext(NonCancellable) {
                rejoinGate?.await()
                rejoinError?.let { throw it }
            }
        }
        override fun membershipRecoveryFailed(errorMessage: String) {
            currentSession = currentSession.copy(
                connectionState = ListenTogetherConnectionState.DISCONNECTED,
                lastError = errorMessage
            )
        }
    }
}
