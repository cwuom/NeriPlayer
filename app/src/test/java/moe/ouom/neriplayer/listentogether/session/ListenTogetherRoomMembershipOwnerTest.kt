package moe.ouom.neriplayer.listentogether.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherLeaveRoomResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomSettings
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherRoomMembershipOwnerTest {
    private val song = SongItem(
        id = 42L, name = "Track", artist = "Artist", album = "Album", albumId = 1L,
        durationMs = 1_000L, coverUrl = null, channelId = "netease", audioId = "42"
    )

    @Test
    fun `create captures normalized shareable playback snapshot and commits response`() = runTest {
        val fixture = fixture()
        fixture.port.shuffle = true
        val response = fixture.owner.createRoom(
            BASE_URL, USER_UUID.uppercase(), " Host ", listOf(song), 0, -50L, true,
            ListenTogetherRoomSettings(shareAudioLinks = true), song
        )
        val call = requireNotNull(fixture.transport.created)
        assertEquals(USER_UUID, call.userUuid)
        assertEquals("Host", call.nickname)
        assertEquals(0L, call.initialSnapshot.positionMs)
        assertEquals(2, call.initialSnapshot.repeatMode)
        assertTrue(call.initialSnapshot.shuffleEnabled)
        assertEquals(1, call.initialSnapshot.queue.size)
        assertEquals(call.initialSnapshot.queue, call.initialSnapshot.shuffleRestoreQueue)
        assertEquals(call.initialSnapshot.queue.first(), call.initialSnapshot.track)
        assertEquals(response, fixture.port.applied.single().second)

        fixture.port.shuffle = false
        fixture.owner.createRoom(BASE_URL, USER_UUID, "Host", listOf(song), 0, 100L, false, ListenTogetherRoomSettings(), song)
        assertFalse(requireNotNull(fixture.transport.created).initialSnapshot.shuffleEnabled)
        assertEquals(null, fixture.transport.created?.initialSnapshot?.shuffleRestoreQueue)

        fixture.port.shuffle = true
        fixture.port.restoreSongs = null
        fixture.owner.createRoom(BASE_URL, USER_UUID, "Host", listOf(song), 0, 100L, false, ListenTogetherRoomSettings(), song)
        assertEquals(null, fixture.transport.created?.initialSnapshot?.shuffleRestoreQueue)
        fixture.port.restoreSongs = emptyList()
        fixture.owner.createRoom(BASE_URL, USER_UUID, "Host", listOf(song), 0, 100L, false, ListenTogetherRoomSettings(), song)
        assertEquals(null, fixture.transport.created?.initialSnapshot?.shuffleRestoreQueue)
    }

    @Test
    fun `join reuses matching retained membership and explicit secret overrides it`() = runTest {
        val fixture = fixture()
        fixture.port.session = activeSession()
        fixture.owner.retainCurrentCredential()
        fixture.port.session = ListenTogetherSessionState()

        fixture.owner.joinRoom(BASE_URL, ROOM_ID.lowercase(), USER_UUID.uppercase(), " Listener ", null, null)
        val reused = requireNotNull(fixture.transport.joined)
        assertEquals(ROOM_ID, reused.roomId)
        assertEquals(USER_UUID, reused.userUuid)
        assertEquals("Listener", reused.nickname)
        assertEquals("member-secret", reused.memberSecret)
        assertEquals("join-secret", reused.joinSecret)
        assertEquals("bearer-token", reused.bearerToken)

        fixture.owner.joinRoom(BASE_URL, ROOM_ID, USER_UUID, "Listener", "explicit-member", "explicit-join")
        val explicit = requireNotNull(fixture.transport.joined)
        assertEquals("explicit-member", explicit.memberSecret)
        assertEquals("explicit-join", explicit.joinSecret)
        assertEquals("bearer-token", explicit.bearerToken)

        fixture.owner.joinRoom(BASE_URL, "ABC235", USER_UUID, "Listener", null, "new-join")
        val different = requireNotNull(fixture.transport.joined)
        assertEquals(null, different.memberSecret)
        assertEquals(null, different.bearerToken)
        assertEquals("new-join", different.joinSecret)
        assertEquals(3, fixture.port.applied.size)
    }

    @Test
    fun `join requires a secret without membership and accepts either existing membership credential`() = runTest {
        val fixture = fixture()
        assertTrue(runCatching {
            fixture.owner.joinRoom(BASE_URL, ROOM_ID, USER_UUID, "Listener", null, null)
        }.isFailure)

        fixture.port.session = activeSession().copy(memberSecret = null)
        fixture.owner.joinRoom(BASE_URL, ROOM_ID, USER_UUID, "Listener", null, " ")
        assertEquals(null, fixture.transport.joined?.memberSecret)
        assertEquals("bearer-token", fixture.transport.joined?.bearerToken)
        assertEquals("join-secret", fixture.transport.joined?.joinSecret)

        fixture.port.session = activeSession().copy(token = null)
        fixture.owner.joinRoom(BASE_URL, ROOM_ID, USER_UUID, "Listener", null, null)
        assertEquals("member-secret", fixture.transport.joined?.memberSecret)
        assertEquals(null, fixture.transport.joined?.bearerToken)
    }

    @Test
    fun `leave pauses eligible room and clears credentials before notifying server`() = runTest {
        val fixture = fixture()
        val session = activeSession()
        val room = ListenTogetherRoomState(roomId = ROOM_ID, version = 1L)
        fixture.port.session = session
        fixture.owner.retainCurrentCredential()
        fixture.port.session = ListenTogetherSessionState()

        fixture.owner.pauseBeforeLeave(session, room)
        assertEquals(1, fixture.port.pauses)
        fixture.owner.pauseBeforeLeave(session, room.copy(settings = room.settings.copy(autoPauseOnMemberChange = false)))
        fixture.owner.pauseBeforeLeave(session.copy(roomId = null), room)
        assertEquals(1, fixture.port.pauses)

        fixture.owner.notifyAndClearCredential(session)
        runCurrent()
        assertEquals(listOf(ListenTogetherLeaveMembershipCall(BASE_URL, ROOM_ID, "bearer-token")), fixture.transport.left)
        assertTrue(runCatching {
            fixture.owner.joinRoom(BASE_URL, ROOM_ID, USER_UUID, "Listener", null, null)
        }.isFailure)
        assertEquals(0, fixture.port.applied.size)

        fixture.owner.notifyAndClearCredential(session.copy(baseUrl = null))
        fixture.owner.notifyAndClearCredential(session.copy(roomId = null))
        fixture.owner.notifyAndClearCredential(session.copy(token = null))
        fixture.owner.notifyAndClearCredential(session.copy(baseUrl = " "))
        fixture.owner.notifyAndClearCredential(session.copy(roomId = " "))
        fixture.owner.notifyAndClearCredential(session.copy(token = " "))
        runCurrent()
        assertEquals(1, fixture.transport.left.size)
    }

    @Test
    fun `leave notification handles server rejection and transport failure`() = runTest {
        val fixture = fixture()
        val session = activeSession()
        fixture.transport.leaveResponse = ListenTogetherLeaveRoomResponse(ok = false, error = "denied")
        fixture.owner.notifyAndClearCredential(session)
        runCurrent()
        assertEquals(1, fixture.transport.left.size)

        fixture.transport.leaveFailure = IllegalStateException("offline")
        fixture.owner.notifyAndClearCredential(session)
        runCurrent()
        assertEquals(2, fixture.transport.left.size)
    }

    private fun TestScope.fixture(): Fixture {
        val transport = FakeTransport()
        val port = FakePort(listOf(song))
        return Fixture(ListenTogetherRoomMembershipOwner(this, transport, port), transport, port)
    }

    private fun activeSession() = ListenTogetherSessionState(
        baseUrl = BASE_URL, roomId = ROOM_ID, userUuid = USER_UUID,
        token = "bearer-token", memberSecret = "member-secret", joinSecret = "join-secret"
    )

    private data class Fixture(
        val owner: ListenTogetherRoomMembershipOwner,
        val transport: FakeTransport,
        val port: FakePort
    )

    private class FakePort(var restoreSongs: List<SongItem>?) : ListenTogetherRoomMembershipPort {
        var session = ListenTogetherSessionState()
        var shuffle = false
        var pauses = 0
        val applied = mutableListOf<Pair<String, ListenTogetherRoomResponse>>()
        override fun currentSession(): ListenTogetherSessionState = session
        override fun repeatMode(): Int = 2
        override fun shuffleEnabled(): Boolean = shuffle
        override fun shuffleRestoreSongs(): List<SongItem>? = restoreSongs
        override fun applyRoomResponse(baseUrl: String, response: ListenTogetherRoomResponse) {
            applied += baseUrl to response
        }
        override fun pauseForDeparture() { pauses++ }
    }

    private class FakeTransport : ListenTogetherMembershipTransport {
        var created: ListenTogetherCreateMembershipCall? = null
        var joined: ListenTogetherJoinMembershipCall? = null
        val left = mutableListOf<ListenTogetherLeaveMembershipCall>()
        var leaveResponse = ListenTogetherLeaveRoomResponse(ok = true)
        var leaveFailure: Throwable? = null
        override suspend fun create(call: ListenTogetherCreateMembershipCall): ListenTogetherRoomResponse {
            created = call
            return ListenTogetherRoomResponse(ok = true, roomId = ROOM_ID)
        }
        override suspend fun join(call: ListenTogetherJoinMembershipCall): ListenTogetherRoomResponse {
            joined = call
            return ListenTogetherRoomResponse(ok = true, roomId = call.roomId)
        }
        override suspend fun leave(call: ListenTogetherLeaveMembershipCall): ListenTogetherLeaveRoomResponse {
            left += call
            leaveFailure?.let { throw it }
            return leaveResponse
        }
    }

    private companion object {
        const val BASE_URL = "https://listen.test"
        const val ROOM_ID = "ABC234"
        const val USER_UUID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
