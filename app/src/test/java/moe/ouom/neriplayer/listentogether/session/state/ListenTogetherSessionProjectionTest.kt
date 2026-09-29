package moe.ouom.neriplayer.listentogether.session.state

import moe.ouom.neriplayer.listentogether.protocol.message.http.ListenTogetherRoomResponse
import moe.ouom.neriplayer.listentogether.protocol.model.session.ListenTogetherSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherSessionProjectionTest {
    private val previous = ListenTogetherSessionState(
        baseUrl = "https://listen.example",
        roomId = "room",
        userUuid = "listener",
        memberSecret = "member-secret",
        joinSecret = "join-secret",
        token = "previous-token"
    )

    @Test
    fun `same identity preserves credentials omitted by refresh`() {
        val response = ListenTogetherRoomResponse(
            ok = true, roomId = "room", userUuid = "listener", role = "listener",
            token = "fresh-token", wsUrl = "wss://listen.example/room"
        )

        val prepared = prepareListenTogetherSessionUpdate("https://listen.example", response, previous)
        val projected = prepared.applyTo(previous.copy(expectedPositionMs = 450L))

        assertFalse(prepared.sessionChanged)
        assertEquals("member-secret", projected.memberSecret)
        assertEquals("join-secret", projected.joinSecret)
        assertEquals("fresh-token", projected.token)
        assertEquals(450L, projected.expectedPositionMs)
        assertEquals("wss://listen.example/room", projected.wsUrl)
    }

    @Test
    fun `room change clears inherited credentials and marks session change`() {
        val response = ListenTogetherRoomResponse(
            ok = true, roomId = "new-room", userUuid = "listener",
            token = "fresh-token"
        )

        val prepared = prepareListenTogetherSessionUpdate("https://listen.example", response, previous)
        val projected = prepared.applyTo(previous)

        assertTrue(prepared.sessionChanged)
        assertNull(projected.memberSecret)
        assertNull(projected.joinSecret)
        assertEquals("new-room", projected.roomId)
        assertTrue(projected.wsUrl.orEmpty().contains("new-room"))
    }

    @Test
    fun `changing user in same room retains only room join secret`() {
        val response = ListenTogetherRoomResponse(
            ok = true, roomId = "room", userId = "another-listener",
            token = "fresh-token", memberSecret = "new-member"
        )

        val prepared = prepareListenTogetherSessionUpdate("https://listen.example", response, previous)
        val projected = prepared.applyTo(previous)

        assertTrue(prepared.sessionChanged)
        assertEquals("another-listener", projected.userUuid)
        assertEquals("new-member", projected.memberSecret)
        assertEquals("join-secret", projected.joinSecret)
    }

    @Test
    fun `internal placeholder websocket URL is replaced by a room URL`() {
        val response = ListenTogetherRoomResponse(
            ok = true, roomId = "room", userUuid = "listener", token = "token",
            wsUrl = "ws://room.internal/socket"
        )

        val prepared = prepareListenTogetherSessionUpdate("https://listen.example", response, previous)

        assertFalse(prepared.resolvedWsUrl.orEmpty().contains("room.internal"))
        assertTrue(prepared.resolvedWsUrl.orEmpty().contains("room"))
    }

    @Test
    fun `unknown user does not mark the same room as a changed session`() {
        val response = ListenTogetherRoomResponse(ok = true, roomId = "room", token = "token")

        val prepared = prepareListenTogetherSessionUpdate("https://listen.example", response, previous)
        val projected = prepared.applyTo(previous)

        assertFalse(prepared.sessionChanged)
        assertNull(projected.memberSecret)
        assertEquals("join-secret", projected.joinSecret)
    }

    @Test
    fun `base URL change is detected and missing token cannot form websocket URL`() {
        val response = ListenTogetherRoomResponse(ok = true, roomId = "room", userUuid = "listener")

        val prepared = prepareListenTogetherSessionUpdate("https://another.example", response, previous)

        assertTrue(prepared.sessionChanged)
        assertNull(prepared.resolvedWsUrl)
    }

    @Test
    fun `unknown previous user and blank new user do not change the session identity`() {
        val response = ListenTogetherRoomResponse(ok = true, roomId = "room", userUuid = "listener")
        assertFalse(prepareListenTogetherSessionUpdate(
            "https://listen.example", response, previous.copy(userUuid = null)
        ).sessionChanged)
        assertFalse(prepareListenTogetherSessionUpdate(
            "https://listen.example", response.copy(userUuid = "  "), previous
        ).sessionChanged)
    }

    @Test
    fun `blank room or token cannot produce a fallback websocket URL`() {
        val response = ListenTogetherRoomResponse(ok = true, roomId = "", token = "token")
        assertNull(prepareListenTogetherSessionUpdate("https://listen.example", response, previous).resolvedWsUrl)
        assertNull(prepareListenTogetherSessionUpdate(
            "https://listen.example", response.copy(roomId = "room", token = ""), previous
        ).resolvedWsUrl)
    }
}
