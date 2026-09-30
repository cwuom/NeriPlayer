package moe.ouom.neriplayer.data.ltw.session.liveness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.ltw.testing.FakeListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.ltw.testing.testRoom
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherStateResponse
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherSafetyPauseResumeOwnerTest {
    @Test
    fun `inactive or controller session retries without fetching room`() = runTest {
        val f = Fixture(this)
        for (session in listOf(ListenTogetherSessionState(), f.port.current.copy(baseUrl = " "), f.port.current.copy(roomId = " "), f.port.current.copy(role = "controller"))) {
            f.port.current = session
            f.owner.resume()
        }
        runCurrent()
        assertEquals(0, f.port.refreshes)
        assertEquals(4, f.player.calls.count { it == "safety:retry" })
    }

    @Test
    fun `resume uses newest matching room and drops stale response position`() = runTest {
        val f = Fixture(this)
        f.port.latest = testRoom(version = 6)
        f.owner.resume()
        runCurrent()
        assertEquals(6L, f.port.applied.single().first.version)
        assertNull(f.port.applied.single().second)
        assertEquals(listOf("safety:complete"), f.player.calls)
        f.port.latest = testRoom(version = 5)
        f.owner.resume()
        runCurrent()
        assertEquals(123L, f.port.applied.last().second)
        f.port.accepted = false
        f.owner.resume()
        runCurrent()
        assertEquals("safety:retry", f.player.calls.last())
    }

    @Test
    fun `room change or controller promotion during refresh clears obsolete safety pause`() = runTest {
        val f = Fixture(this)
        f.port.onRefresh = { f.port.current = f.port.current.copy(roomId = "OTHER1") }
        f.owner.resume()
        runCurrent()
        assertEquals(listOf("safety:clear"), f.player.calls)
        assertTrue(f.port.applied.isEmpty())
        f.port.current = f.port.current.copy(roomId = "ABC234", role = "listener")
        f.port.onRefresh = { f.port.current = f.port.current.copy(role = "controller") }
        f.owner.resume()
        runCurrent()
        assertTrue(f.port.applied.isEmpty())
    }

    @Test
    fun `missing room and server failures remain retryable with precise error`() = runTest {
        val f = Fixture(this)
        f.port.response = ListenTogetherStateResponse(ok = false, error = "offline")
        f.owner.resume(); runCurrent()
        assertEquals("offline", f.port.errors.last())
        f.port.response = ListenTogetherStateResponse(ok = true)
        f.owner.resume(); runCurrent()
        assertEquals("listener_safety_resume_state_unavailable", f.port.errors.last())
        f.port.failure = IllegalStateException("failure")
        f.owner.resume(); runCurrent()
        assertEquals("failure", f.port.errors.last())
        assertEquals(3, f.player.calls.count { it == "safety:retry" })
    }

    @Test
    fun `response room is used when no matching cached room exists`() = runTest {
        val f = Fixture(this)
        f.port.latest = testRoom().copy(roomId = "OTHER1")
        f.owner.resume(); runCurrent()
        assertEquals("ABC234", f.port.applied.single().first.roomId)
        f.port.latest = null
        f.owner.resume(); runCurrent()
        assertEquals(2, f.port.applied.size)
    }

    private class Fixture(scope: TestScope) {
        val player = FakeListenTogetherPlaybackHost()
        val port = FakePort()
        val owner = ListenTogetherSafetyPauseResumeOwner(scope, scope, player, port)
    }

    private class FakePort : ListenTogetherSafetyPauseResumePort {
        var current = ListenTogetherSessionState(baseUrl = "https://listen.test", roomId = "ABC234", role = "listener")
        var latest: ListenTogetherRoomState? = testRoom()
        var response = ListenTogetherStateResponse(ok = true, state = testRoom(), expectedPositionMs = 123L)
        var failure: Throwable? = null
        var onRefresh: () -> Unit = {}
        var accepted = true
        var refreshes = 0
        val errors = mutableListOf<String>()
        val applied = mutableListOf<Pair<ListenTogetherRoomState, Long?>>()
        override fun session() = current
        override fun isController(session: ListenTogetherSessionState) = session.role == "controller"
        override fun room() = latest
        override suspend fun refresh(baseUrl: String, roomId: String): ListenTogetherStateResponse {
            refreshes++
            onRefresh()
            failure?.let { throw it }
            return response
        }
        override fun apply(state: ListenTogetherRoomState, cause: String, expectedPositionMs: Long?): Boolean { applied += state to expectedPositionMs; return accepted }
        override fun setError(error: String) { errors += error }
    }
}
