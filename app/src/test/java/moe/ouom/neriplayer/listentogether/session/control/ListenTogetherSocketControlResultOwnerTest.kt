package moe.ouom.neriplayer.listentogether.session.control

import moe.ouom.neriplayer.listentogether.session.connection.ListenTogetherConnectionRecoveryOwner
import moe.ouom.neriplayer.listentogether.session.connection.ListenTogetherConnectionRecoveryPort
import moe.ouom.neriplayer.listentogether.session.connection.ListenTogetherRejoinIdentity
import moe.ouom.neriplayer.listentogether.session.connection.ListenTogetherSocketHealthOwner
import moe.ouom.neriplayer.listentogether.session.connection.ListenTogetherSocketHealthPort
import moe.ouom.neriplayer.listentogether.session.link.ListenTogetherControllerLinkOwner
import moe.ouom.neriplayer.listentogether.session.link.ListenTogetherLinkEventPort
import moe.ouom.neriplayer.listentogether.session.link.ListenTogetherLinkPlaybackPort
import moe.ouom.neriplayer.listentogether.session.link.ListenTogetherLinkSessionPort
import moe.ouom.neriplayer.listentogether.session.state.AcceptedRoomState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.url.ShareableListenTogetherStreamResolution
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherAppliedEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherControlResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherSocketControlResultOwnerTest {
    @Test
    fun `successful committed settings state applies to listener`() = runTest {
        val fixture = fixture()
        fixture.owner.onControlResult(reply("UPDATE_SETTINGS", "host"))
        assertEquals(1, fixture.port.accepted)
        assertEquals(listOf("UPDATE_SETTINGS"), fixture.port.applied)

        fixture.owner.onControlResult(reply("REQUEST_PLAY", "other"))
        assertEquals(1, fixture.port.accepted)
        fixture.owner.onControlResult(reply("REQUEST_PLAY", "listener"))
        assertEquals(2, fixture.port.accepted)
        assertEquals(listOf("UPDATE_SETTINGS", "REQUEST_PLAY"), fixture.port.applied)

        fixture.port.controller = true
        fixture.owner.onControlResult(reply("TRACK_FINISHED", "listener"))
        assertEquals(3, fixture.port.accepted)
        assertEquals(2, fixture.port.applied.size)

        fixture.port.reject = true
        fixture.owner.onControlResult(reply("UPDATE_SETTINGS", "host"))
        assertEquals(3, fixture.port.accepted)
    }

    @Test
    fun `rejected control result records errors after legacy fallbacks`() = runTest {
        val fixture = fixture()
        fixture.port.trackFallback = true
        fixture.owner.onControlResult(ListenTogetherSocketEnvelope(
            type = "control_result", result = ListenTogetherControlResponse(ok = false, error = "legacy track finish")
        ))
        assertEquals(null, fixture.port.observedError)
        assertEquals(1, fixture.port.trackFallbackCalls)

        fixture.port.trackFallback = false
        fixture.owner.onControlResult(ListenTogetherSocketEnvelope(
            type = "control_result", ok = false, message = "temporary failure"
        ))
        assertEquals("temporary failure", fixture.port.observedError)
        assertTrue(fixture.recovery.enabled.not())

        fixture.owner.onControlResult(ListenTogetherSocketEnvelope(
            type = "ack", result = ListenTogetherControlResponse(ok = false, error = "unauthorized")
        ))
        assertEquals(listOf("unauthorized"), fixture.recoveryPort.closed)
    }

    @Test
    fun `socket error and pong use health and recovery owners`() = runTest {
        val fixture = fixture()
        fixture.owner.onSocketError(ListenTogetherSocketEnvelope(type = "error", message = "Unsupported event type: np_ping"))
        assertEquals(1, fixture.healthPort.legacyPings)
        assertEquals(null, fixture.port.observedError)

        fixture.owner.onSocketError(ListenTogetherSocketEnvelope(type = "error", message = "temporary network error"))
        assertEquals("temporary network error", fixture.port.observedError)
        fixture.owner.onPong(ListenTogetherSocketEnvelope(type = "np_pong", nowMs = 10_050L, t = 1_000L))
        assertEquals(null, fixture.port.observedError)
        fixture.owner.onPong(ListenTogetherSocketEnvelope(type = "pong"))
    }

    @Test
    fun `committed result policy respects actor and event type`() {
        assertTrue(shouldApplyListenTogetherCommittedControlState(ListenTogetherCause(type = "UPDATE_SETTINGS"), "listener"))
        assertTrue(shouldApplyListenTogetherCommittedControlState(ListenTogetherCause(type = "TRACK_FINISHED", userUuid = "listener"), "listener"))
        assertFalse(shouldApplyListenTogetherCommittedControlState(ListenTogetherCause(type = "TRACK_FINISHED", userUuid = "host"), "listener"))
        assertTrue(shouldApplyListenTogetherCommittedControlState(ListenTogetherCause(type = "REQUEST_PLAY", userUuid = "listener"), "listener"))
        assertFalse(shouldApplyListenTogetherCommittedControlState(ListenTogetherCause(type = "REQUEST_PLAY", userUuid = "host"), "listener"))
        assertFalse(shouldApplyListenTogetherCommittedControlState(ListenTogetherCause(type = "PLAY", userUuid = "listener"), "listener"))
        assertFalse(shouldApplyListenTogetherCommittedControlState(ListenTogetherCause(type = null), "listener"))
    }

    @Test
    fun `control result policy separates acknowledgement from rejection`() {
        val success = ListenTogetherSocketEnvelope(type = "ack", ok = true)
        assertTrue(isSuccessfulListenTogetherControlResult(success))
        assertFalse(isRejectedListenTogetherControlResult(success))
        val rejected = success.copy(ok = false)
        assertFalse(isSuccessfulListenTogetherControlResult(rejected))
        assertTrue(isRejectedListenTogetherControlResult(rejected))
        val error = success.copy(result = ListenTogetherControlResponse(ok = false, error = "denied"))
        assertFalse(isSuccessfulListenTogetherControlResult(error))
        assertTrue(isRejectedListenTogetherControlResult(error))
    }

    @Test
    fun `missing applied state and cause leave the room unchanged`() = runTest {
        val fixture = fixture()
        fixture.owner.onControlResult(ListenTogetherSocketEnvelope(type = "ack"))
        fixture.owner.onControlResult(ListenTogetherSocketEnvelope(
            type = "ack",
            result = ListenTogetherControlResponse(ok = true, applied = ListenTogetherAppliedEvent(type = "PLAY"))
        ))
        fixture.owner.onControlResult(ListenTogetherSocketEnvelope(
            type = "ack",
            result = ListenTogetherControlResponse(
                ok = true,
                applied = ListenTogetherAppliedEvent(
                    type = "PLAY", state = ListenTogetherRoomState(roomId = "room", version = 2L)
                )
            )
        ))
        assertEquals(0, fixture.port.accepted)
    }

    private fun reply(type: String, actor: String) = ListenTogetherSocketEnvelope(
        type = "control_result",
        ok = true,
        result = ListenTogetherControlResponse(
            ok = true,
            applied = ListenTogetherAppliedEvent(
                type = type,
                state = ListenTogetherRoomState(roomId = "room", version = 2L),
                causedBy = ListenTogetherCause(type = type, userUuid = actor, eventId = "$type:$actor")
            )
        )
    )

    private fun TestScope.fixture(): Fixture {
        val port = FakeControlPort()
        val local = ListenTogetherLocalControlOwner(
            this,
            object : ListenTogetherLocalControlPort {
                override fun currentRoomId(): String = "room"
                override fun isController(): Boolean = false
                override fun nextEventId(): String = "event"
                override fun markOutbound(eventId: String?) = Unit
                override fun noteOutboundSync() = Unit
                override fun send(event: ListenTogetherEvent, reason: String): Boolean = true
            },
            elapsedRealtimeMs = { 1_000L }, wallTimeMs = { 10_000L }
        )
        val link = ListenTogetherControllerLinkOwner(
            scope = this,
            session = object : ListenTogetherLinkSessionPort {
                override fun sessionState(): ListenTogetherSessionState = ListenTogetherSessionState()
                override fun roomState(): ListenTogetherRoomState? = port.currentRoom()
                override fun publish(event: ListenTogetherEvent, reason: String, noteSync: Boolean): Boolean = true
                override fun publishControllerHeartbeat(reason: String) = Unit
            },
            playback = object : ListenTogetherLinkPlaybackPort {
                override fun currentSong(): SongItem? = null
                override fun playbackPositionMs(): Long = 0L
                override fun playbackResolutionPending(): Boolean = false
                override suspend fun resolveShareableStreamUrls(song: SongItem) =
                    ShareableListenTogetherStreamResolution(emptyList(), false)
                override fun hasUsableLocalDirectStream(track: ListenTogetherTrack): Boolean = false
            },
            events = object : ListenTogetherLinkEventPort {
                override fun requestLink(
                    stableKey: String, currentIndex: Int, track: ListenTogetherTrack, forceRefresh: Boolean
                ) = ListenTogetherEvent(type = "REQUEST_LINK")
                override fun linkReady(stableKey: String, positionMs: Long, streamUrlsOverride: List<String>): ListenTogetherEvent? = null
                override fun linkUnavailable(stableKey: String): ListenTogetherEvent? = null
            },
            elapsedRealtimeMs = { 1_000L }
        )
        val healthPort = FakeHealthPort()
        val health = ListenTogetherSocketHealthOwner(this, healthPort, { 1_000L }, { 10_000L })
        val recoveryPort = FakeRecoveryPort()
        val recovery = ListenTogetherConnectionRecoveryOwner(this, recoveryPort)
        return Fixture(
            port, healthPort, recoveryPort, recovery,
            ListenTogetherSocketControlResultOwner(port, local, link, health, recovery)
        )
    }

    private data class Fixture(
        val port: FakeControlPort,
        val healthPort: FakeHealthPort,
        val recoveryPort: FakeRecoveryPort,
        val recovery: ListenTogetherConnectionRecoveryOwner,
        val owner: ListenTogetherSocketControlResultOwner
    )

    private class FakeControlPort : ListenTogetherSocketControlResultPort {
        var accepted = 0
        val applied = mutableListOf<String?>()
        var observedError: String? = null
        var trackFallback = false
        var trackFallbackCalls = 0
        var controller = false
        var reject = false
        override fun currentUserUuid(): String = "listener"
        override fun currentRoom(): ListenTogetherRoomState = ListenTogetherRoomState(roomId = "room", version = 1L)
        override fun accept(
            state: ListenTogetherRoomState, expectedPositionMs: Long?, cause: ListenTogetherCause
        ): AcceptedRoomState? {
            if (reject) return null
            accepted++
            return AcceptedRoomState(state, expectedPositionMs)
        }
        override fun isController(): Boolean = controller
        override fun applyToPlayer(state: ListenTogetherRoomState, causeType: String?, expectedPositionMs: Long?) {
            applied += causeType
        }
        override fun setLastError(error: String?) { observedError = error }
        override fun trySendTrackFinishedLegacyFallback(error: String): Boolean {
            trackFallbackCalls++
            return trackFallback
        }
    }

    private class FakeHealthPort : ListenTogetherSocketHealthPort {
        var legacyPings = 0
        override fun session(): ListenTogetherSessionState = ListenTogetherSessionState()
        override fun reconnectEnabled(): Boolean = false
        override fun sendPing(sentAtElapsedMs: Long): Boolean = true
        override fun sendLegacyPing(): Boolean { legacyPings++; return true }
        override fun scheduleReconnect(reason: String) = Unit
        override fun connectWebSocket() = Unit
        override fun updateBackgroundKeepAlive(reason: String) = Unit
    }

    private class FakeRecoveryPort : ListenTogetherConnectionRecoveryPort {
        val closed = mutableListOf<String>()
        override fun session(): ListenTogetherSessionState = ListenTogetherSessionState()
        override fun isController(session: ListenTogetherSessionState): Boolean = false
        override fun updateBackgroundKeepAlive(reason: String) = Unit
        override fun connectWebSocket() = Unit
        override fun closeRoomLocally(reason: String) { closed += reason }
        override fun beginMembershipRecovery(session: ListenTogetherSessionState) = Unit
        override suspend fun rejoinRoom(identity: ListenTogetherRejoinIdentity) = Unit
        override fun membershipRecoveryFailed(errorMessage: String) = Unit
    }
}
