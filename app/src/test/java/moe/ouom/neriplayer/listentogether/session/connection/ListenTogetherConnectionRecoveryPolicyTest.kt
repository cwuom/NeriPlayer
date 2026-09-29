package moe.ouom.neriplayer.listentogether.session.connection

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherMember
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherConnectionRecoveryPolicyTest {
    private val session = ListenTogetherSessionState(
        baseUrl = "https://example.test",
        roomId = "room",
        userUuid = "listener",
        nickname = "listener",
        wsUrl = "wss://example.test/room",
        connectionState = ListenTogetherConnectionState.DISCONNECTED
    )

    @Test
    fun `reconnect requires an enabled active target outside connecting state`() {
        assertTrue(shouldScheduleListenTogetherReconnect(session, true))
        assertFalse(shouldScheduleListenTogetherReconnect(session, false))
        assertFalse(shouldScheduleListenTogetherReconnect(session.copy(wsUrl = null), true))
        assertFalse(shouldScheduleListenTogetherReconnect(session.copy(roomId = null), true))
        assertFalse(shouldScheduleListenTogetherReconnect(
            session.copy(connectionState = ListenTogetherConnectionState.CONNECTING), true
        ))
        assertTrue(hasListenTogetherReconnectTarget(
            session.copy(connectionState = ListenTogetherConnectionState.CONNECTING), true
        ))
    }

    @Test
    fun `listener rejoin needs every identity field and never runs for controller`() {
        assertTrue(hasListenTogetherListenerRejoinIdentity(session, false))
        assertTrue(listenTogetherRejoinIdentity(session, false) == ListenTogetherRejoinIdentity(
            "https://example.test", "room", "listener", "listener"
        ))
        assertFalse(hasListenTogetherListenerRejoinIdentity(session, true))
        assertTrue(listenTogetherRejoinIdentity(session, true) == null)
        assertFalse(hasListenTogetherListenerRejoinIdentity(session.copy(baseUrl = null), false))
        assertTrue(listenTogetherRejoinIdentity(session.copy(baseUrl = null), false) == null)
        assertFalse(hasListenTogetherListenerRejoinIdentity(session.copy(roomId = null), false))
        assertTrue(listenTogetherRejoinIdentity(session.copy(roomId = null), false) == null)
        assertFalse(hasListenTogetherListenerRejoinIdentity(session.copy(userUuid = null), false))
        assertTrue(listenTogetherRejoinIdentity(session.copy(userUuid = null), false) == null)
        assertFalse(hasListenTogetherListenerRejoinIdentity(session.copy(nickname = null), false))
        assertTrue(listenTogetherRejoinIdentity(session.copy(nickname = null), false) == null)
    }

    @Test
    fun `membership recovery ignores present members controllers and closed rooms`() {
        val emptyRoom = ListenTogetherRoomState(roomId = "room", version = 1L)
        assertTrue(needsListenTogetherListenerMembershipRecovery(session, emptyRoom, false))
        assertFalse(needsListenTogetherListenerMembershipRecovery(session.copy(userUuid = null), emptyRoom, false))
        assertFalse(needsListenTogetherListenerMembershipRecovery(session, emptyRoom, true))
        assertFalse(needsListenTogetherListenerMembershipRecovery(
            session, emptyRoom.copy(roomStatus = ListenTogetherRoomStatuses.CLOSED), false
        ))
        val member = ListenTogetherMember(userUuid = "listener", role = "listener", joinedAt = 0L)
        assertFalse(needsListenTogetherListenerMembershipRecovery(
            session, emptyRoom.copy(members = listOf(member)), false
        ))
        assertFalse(needsListenTogetherListenerMembershipRecovery(
            session, emptyRoom.copy(members = listOf(member.copy(userUuid = "", userId = "listener"))), false
        ))
    }

    @Test
    fun `only missing member errors trigger a rejoin`() {
        assertFalse(isListenTogetherMissingMemberError(null))
        assertFalse(isListenTogetherMissingMemberError("temporary failure"))
        assertTrue(isListenTogetherMissingMemberError("Member Not In Room"))
        assertTrue(isListenTogetherMissingMemberError("member missing"))
    }

    @Test
    fun `recovery identity survives credential refresh but rejects a changed target`() {
        assertTrue(sameListenTogetherMembership(session, session.copy(wsUrl = "wss://new.example.test")))
        assertFalse(sameListenTogetherReconnectTarget(session, session.copy(wsUrl = "wss://new.example.test")))
        assertFalse(sameListenTogetherMembership(session, session.copy(roomId = "other_room")))
        assertFalse(sameListenTogetherMembership(session, session.copy(userUuid = "other_user")))
        assertTrue(sameListenTogetherReconnectTarget(session, session.copy(lastError = "transient")))
    }

    @Test
    fun `generation and target guard stale reconnect and membership work`() {
        assertTrue(canRunListenTogetherReconnect(session, session, true, true))
        assertFalse(canRunListenTogetherReconnect(session, session, true, false))
        assertFalse(canRunListenTogetherReconnect(session, session, false, true))
        assertFalse(canRunListenTogetherReconnect(session, session.copy(wsUrl = "wss://other"), true, true))
        assertFalse(canRunListenTogetherReconnect(
            session, session.copy(connectionState = ListenTogetherConnectionState.CONNECTING), true, true
        ))
        assertTrue(canStartListenTogetherMembershipRecovery(session, session, false, true))
        assertFalse(canStartListenTogetherMembershipRecovery(session, session, true, true))
        assertFalse(canStartListenTogetherMembershipRecovery(session, session, false, false))
        assertFalse(canStartListenTogetherMembershipRecovery(
            session, session.copy(roomId = "other_room"), false, true
        ))
    }
}
