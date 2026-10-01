package moe.ouom.neriplayer.data.ltw.session.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherAppliedEvent
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherControlResponse
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.AcceptedRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherHttpControlFallbackOwnerTest {
    @Test
    fun `listener response acknowledges intent and applies accepted authoritative state`() = runTest {
        val port = FakePort()
        val accepted = port.state.copy(version = 8L)
        port.accepted = AcceptedRoomState(accepted, 12L)
        val owner = ListenTogetherHttpControlFallbackOwner(this, port)
        owner.send(event, "test", "room")
        runCurrent()
        assertEquals(listOf(event.eventId), port.acknowledged)
        assertEquals(listOf(accepted), port.applied)
        assertEquals(listOf("PLAY"), port.linkCauses)
        assertEquals(listOf(null), port.errors)
    }

    @Test
    fun `controller response accepts state without replaying local playback`() = runTest {
        val port = FakePort().apply { controller = true }
        ListenTogetherHttpControlFallbackOwner(this, port).send(event, "test", "room")
        runCurrent()
        assertEquals(1, port.acceptCalls)
        assertTrue(port.applied.isEmpty())
        assertTrue(port.linkCauses.isEmpty())
    }

    @Test
    fun `missing applied state and rejected acceptance never reach player`() = runTest {
        val port = FakePort()
        val owner = ListenTogetherHttpControlFallbackOwner(this, port)
        for (response in listOf(
            ListenTogetherControlResponse(ok = true),
            ListenTogetherControlResponse(ok = true, applied = ListenTogetherAppliedEvent(type = "PLAY", version = 2L))
        )) {
            port.response = response
            owner.send(event, "test", "room")
            runCurrent()
        }
        port.response = successfulResponse(port.state)
        port.accepted = null
        owner.send(event, "test", "room")
        runCurrent()
        assertEquals(3, port.acknowledged.size)
        assertEquals(1, port.acceptCalls)
        assertTrue(port.applied.isEmpty())
    }

    @Test
    fun `missing session credentials and mismatched room never send a request`() = runTest {
        val port = FakePort()
        val owner = ListenTogetherHttpControlFallbackOwner(this, port)
        val original = port.current
        for (session in listOf(
            original.copy(baseUrl = null), original.copy(baseUrl = " "),
            original.copy(roomId = null), original.copy(roomId = " "),
            original.copy(token = null), original.copy(token = " ")
        )) {
            port.current = session
            owner.send(event, "test", "room")
        }
        port.current = original
        owner.send(event, "test", null)
        owner.send(event, "test", "other")
        runCurrent()
        assertEquals(0, port.sendCalls)
    }

    @Test
    fun `same room response is discarded when server token or user has changed`() = runTest {
        val port = FakePort()
        val owner = ListenTogetherHttpControlFallbackOwner(this, port)
        val original = port.current
        for (changed in listOf(
            original.copy(baseUrl = "https://other.example"),
            original.copy(token = "replacement"),
            original.copy(userUuid = "other-user"),
            original.copy(roomId = "other-room")
        )) {
            val response = CompletableDeferred<ListenTogetherControlResponse>()
            port.current = original
            port.sendResponse = { response.await() }
            owner.send(event, "test", "room")
            runCurrent()
            port.current = changed
            response.complete(port.response)
            runCurrent()
        }
        assertEquals(4, port.sendCalls)
        assertTrue(port.errors.isEmpty())
        assertTrue(port.acknowledged.isEmpty())
        assertEquals(0, port.acceptCalls)
    }

    @Test
    fun `session change before coroutine starts discards the scheduled request`() = runTest {
        val port = FakePort()
        ListenTogetherHttpControlFallbackOwner(this, port).send(event, "test", "room")
        port.current = port.current.copy(token = "replacement")
        runCurrent()
        assertEquals(0, port.sendCalls)
    }

    @Test
    fun `reset cancels pending transport without recording an error`() = runTest {
        val port = FakePort()
        var cancelled = false
        port.sendResponse = {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val owner = ListenTogetherHttpControlFallbackOwner(this, port)
        owner.send(event, "test", "room")
        runCurrent()
        owner.reset()
        runCurrent()
        assertTrue(cancelled)
        assertTrue(port.errors.isEmpty())
        assertTrue(port.recoveryReasons.isEmpty())
    }

    @Test
    fun `reset discards non cooperative late response after rejoining identical session`() = runTest {
        val port = FakePort()
        val response = CompletableDeferred<ListenTogetherControlResponse>()
        port.sendResponse = { withContext(NonCancellable) { response.await() } }
        val owner = ListenTogetherHttpControlFallbackOwner(this, port)
        owner.send(event, "old", "room")
        runCurrent()
        owner.reset()
        response.complete(port.response)
        runCurrent()
        assertTrue(port.acknowledged.isEmpty())
        assertTrue(port.errors.isEmpty())
        port.sendResponse = { port.response }
        owner.send(event, "new", "room")
        runCurrent()
        assertEquals(listOf(event.eventId), port.acknowledged)
    }

    @Test
    fun `transport cancellation is propagated without reconnecting`() = runTest {
        val port = FakePort().apply { sendResponse = { throw CancellationException("cancelled") } }
        ListenTogetherHttpControlFallbackOwner(this, port).send(event, "test", "room")
        runCurrent()
        assertTrue(port.errors.isEmpty())
        assertTrue(port.recoveryReasons.isEmpty())
    }

    @Test
    fun `stale transport failure cannot change current session error`() = runTest {
        val port = FakePort()
        val pending = CompletableDeferred<Unit>()
        port.sendResponse = { pending.await(); throw IllegalStateException("late failure") }
        ListenTogetherHttpControlFallbackOwner(this, port).send(event, "test", "room")
        runCurrent()
        port.current = port.current.copy(token = "replacement")
        pending.complete(Unit)
        runCurrent()
        assertTrue(port.errors.isEmpty())
        assertTrue(port.recoveryReasons.isEmpty())
    }

    @Test
    fun `transport failure enters terminal or membership recovery once`() = runTest {
        val port = FakePort().apply { sendResponse = { throw IllegalStateException() } }
        val owner = ListenTogetherHttpControlFallbackOwner(this, port)
        owner.send(event, "test", "room")
        runCurrent()
        assertEquals(listOf("IllegalStateException"), port.errors)
        assertEquals(listOf("http_control_fallback"), port.recoveryReasons)
        port.terminal = true
        owner.send(event, "test", "room")
        runCurrent()
        assertEquals(2, port.terminalCalls)
        assertEquals(1, port.recoveryReasons.size)
    }

    @Test
    fun `rejection tries queue compatibility before track compatibility and recovery`() = runTest {
        val port = FakePort().apply {
            response = ListenTogetherControlResponse(ok = false, error = "rejected")
            queueFallback = true
        }
        val owner = ListenTogetherHttpControlFallbackOwner(this, port)
        owner.send(event, "test", "room")
        runCurrent()
        assertEquals(0, port.trackFallbackCalls)
        port.queueFallback = false
        port.trackFallback = true
        owner.send(event, "test", "room")
        runCurrent()
        assertEquals(1, port.trackFallbackCalls)
        assertEquals(0, port.terminalCalls)
        port.trackFallback = false
        port.response = ListenTogetherControlResponse(ok = true, error = "error despite ok")
        owner.send(event, "test", "room")
        runCurrent()
        assertEquals(listOf("http_control_fallback_response"), port.recoveryReasons)
        assertTrue(port.acknowledged.isEmpty())
    }

    @Test
    fun `rejection without message uses default and successful response allows absent cause`() = runTest {
        val port = FakePort().apply { response = ListenTogetherControlResponse(ok = false) }
        val owner = ListenTogetherHttpControlFallbackOwner(this, port)
        owner.send(event, "test", "room")
        runCurrent()
        assertEquals(listOf("control event rejected"), port.errors)
        port.response = port.response.copy(ok = true, error = " ", applied = ListenTogetherAppliedEvent(type = "PLAY", version = 2L, state = port.state))
        owner.send(event, "test", "room")
        runCurrent()
        assertEquals(listOf(null), port.linkCauses)
        assertFalse(port.applied.isEmpty())
    }

    private class FakePort : ListenTogetherHttpControlFallbackPort {
        var current = ListenTogetherSessionState(baseUrl = "https://test.example", roomId = "room", token = "test-token", userUuid = "user")
        val state = ListenTogetherRoomState(roomId = "room", version = 2L)
        var response = successfulResponse(state)
        var accepted: AcceptedRoomState? = AcceptedRoomState(state, 10L)
        var sendResponse: suspend () -> ListenTogetherControlResponse = { response }
        var controller = false
        var terminal = false
        var queueFallback = false
        var trackFallback = false
        var sendCalls = 0
        var acceptCalls = 0
        var terminalCalls = 0
        var trackFallbackCalls = 0
        val errors = mutableListOf<String?>()
        val acknowledged = mutableListOf<String?>()
        val applied = mutableListOf<ListenTogetherRoomState>()
        val linkCauses = mutableListOf<String?>()
        val recoveryReasons = mutableListOf<String>()
        override fun session() = current
        override suspend fun send(baseUrl: String, roomId: String, token: String, event: ListenTogetherEvent): ListenTogetherControlResponse {
            sendCalls++
            assertEquals(current.baseUrl, baseUrl)
            assertEquals(current.roomId, roomId)
            assertEquals(current.token, token)
            return sendResponse()
        }
        override fun setLastError(error: String?) { errors += error }
        override fun acknowledge(eventId: String?) { acknowledged += eventId }
        override fun tryQueueMutationLegacyFallback(error: String, eventId: String?) = queueFallback
        override fun tryTrackFinishedLegacyFallback(error: String): Boolean { trackFallbackCalls++; return trackFallback }
        override fun handleTerminalFailure(error: String, reason: String): Boolean { terminalCalls++; return terminal }
        override fun recoverFromMembershipError(error: String, reason: String) { recoveryReasons += reason }
        override fun accept(state: ListenTogetherRoomState, expectedPositionMs: Long?, cause: ListenTogetherCause?): AcceptedRoomState? {
            acceptCalls++
            return accepted
        }
        override fun isController() = controller
        override fun applyToPlayer(state: ListenTogetherRoomState, causeType: String?, expectedPositionMs: Long?) { applied += state }
        override fun requestControllerLink(state: ListenTogetherRoomState, causeType: String?) { linkCauses += causeType }
    }

    private companion object {
        val event = ListenTogetherEvent(type = "PLAY", eventId = "event")
        fun successfulResponse(state: ListenTogetherRoomState) = ListenTogetherControlResponse(
            ok = true,
            applied = ListenTogetherAppliedEvent(type = "PLAY", version = state.version, state = state, causedBy = ListenTogetherCause(type = "PLAY"))
        )
    }
}
