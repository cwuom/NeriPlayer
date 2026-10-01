package moe.ouom.neriplayer.data.ltw.session.socket

import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherLocalControlOwner
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherLocalControlPort
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherRecentEventTracker
import moe.ouom.neriplayer.data.ltw.session.link.ListenTogetherControllerLinkOwner
import moe.ouom.neriplayer.data.ltw.session.link.ListenTogetherLinkEventPort
import moe.ouom.neriplayer.data.ltw.session.link.ListenTogetherLinkPlaybackPort
import moe.ouom.neriplayer.data.ltw.session.link.ListenTogetherLinkSessionPort
import moe.ouom.neriplayer.data.model.ltw.session.AcceptedRoomState
import moe.ouom.neriplayer.data.model.ltw.session.RoomStateSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherStreamResolution
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherRoomSocketEventOwnerTest {
    private val track = ListenTogetherTrack(
        stableKey = "netease:track", channelId = "netease", audioId = "track",
        name = "Track", artist = "Artist"
    )

    @Test
    fun `listener accepts room state and requests its missing controller link`() = runTest {
        val fixture = fixture()
        val state = fixture.port.room.copy(version = 2L)
        fixture.owner.onRoomState(envelope(state, "PLAY", "host", "hello"))
        runCurrent()
        assertEquals(listOf("PLAY"), fixture.port.applied)
        assertEquals("hello", fixture.port.notice)
        assertEquals(listOf("REQUEST_LINK"), fixture.sent.map { it.type })

        fixture.port.reject = true
        fixture.owner.onRoomState(envelope(state.copy(version = 1L), "PLAY", "host"))
        assertEquals(1, fixture.port.applied.size)
    }

    @Test
    fun `controller local echo skips player apply while a remote control applies`() = runTest {
        val fixture = fixture(controller = true)
        fixture.owner.onRoomState(envelope(fixture.port.room.copy(version = 2L), "PLAY", "host"))
        assertTrue(fixture.port.applied.isEmpty())

        fixture.owner.onRoomState(envelope(fixture.port.room.copy(version = 3L), "PLAY", "listener"))
        assertEquals(listOf("PLAY"), fixture.port.applied)

        val nextTrack = track.copy(stableKey = "netease:next", audioId = "next")
        fixture.owner.onRoomState(envelope(
            fixture.port.room.copy(version = 4L, queue = listOf(nextTrack), track = nextTrack),
            "SET_QUEUE", "host"
        ))
        assertEquals(listOf("PLAY", "SET_QUEUE"), fixture.port.applied)

        fixture.owner.onRoomState(ListenTogetherSocketEnvelope(type = "room_state_updated"))
        assertEquals(2, fixture.port.applied.size)
    }

    @Test
    fun `suspend then resume restores listener notice and clears transient error`() = runTest {
        val fixture = fixture()
        val suspended = fixture.port.room.copy(
            roomStatus = ListenTogetherRoomStatuses.CONTROLLER_OFFLINE,
            controllerOfflineSince = System.currentTimeMillis(), version = 2L
        )
        fixture.owner.onRoomSuspended(envelope(suspended, "MEMBER_LEFT", "host"))
        assertTrue(fixture.port.notice?.startsWith("controller_offline:") == true)
        assertTrue(fixture.port.applied.isEmpty())

        fixture.port.error = "temporary"
        val resumed = suspended.copy(roomStatus = ListenTogetherRoomStatuses.ACTIVE, version = 3L)
        fixture.owner.onRoomResumed(envelope(resumed, "PLAY", "host", "controller_reconnected"))
        assertEquals("controller_reconnected", fixture.port.notice)
        assertEquals(null, fixture.port.error)
        assertEquals(listOf("controller_reconnected"), fixture.port.applied)

        fixture.owner.reset()
        fixture.owner.onRoomResumed(envelope(resumed.copy(version = 4L), "PLAY", "host", "controller_reconnected"))
        assertEquals(null, fixture.port.notice)

        fixture.port.reject = true
        fixture.owner.onRoomSuspended(envelope(suspended, "PLAY", "host"))
        fixture.owner.onRoomResumed(envelope(resumed, "PLAY", "host"))
        assertEquals(2, fixture.port.applied.size)
    }

    @Test
    fun `closed room pauses only accepted active playback and closes with server reason`() = runTest {
        val fixture = fixture()
        val closed = fixture.port.room.copy(
            version = 2L,
            roomStatus = ListenTogetherRoomStatuses.CLOSED,
            closedReason = "controller_timeout"
        )
        fixture.owner.onRoomClosed(envelope(closed, "CLOSE", "host", "fallback"))
        assertEquals(listOf("controller_timeout"), fixture.port.closed)
        assertEquals(1, fixture.port.pauses)

        fixture.port.reject = true
        fixture.owner.onRoomClosed(envelope(closed.copy(closedReason = null), "CLOSE", "host", "fallback"))
        assertEquals(listOf("controller_timeout", "fallback"), fixture.port.closed)
        assertEquals(1, fixture.port.pauses)

        fixture.owner.onRoomClosed(ListenTogetherSocketEnvelope(type = "room_closed", message = "socket closed"))
        assertEquals("socket closed", fixture.port.closed.last())
    }

    @Test
    fun `controller responds to remote recovery signal only when link sharing is enabled`() = runTest {
        val fixture = fixture(controller = true)
        fixture.owner.onRoomState(envelope(fixture.port.room.copy(version = 2L), "REQUEST_PLAY", "listener"))
        assertEquals(listOf("recovery:REQUEST_PLAY"), fixture.port.heartbeats)

        val disabled = fixture.port.room.copy(
            version = 3L,
            settings = fixture.port.room.settings.copy(shareAudioLinks = false)
        )
        fixture.owner.onRoomState(envelope(disabled, "REQUEST_PLAY", "listener"))
        assertEquals(1, fixture.port.heartbeats.size)

        fixture.owner.onRoomState(envelope(disabled.copy(version = 4L), "REQUEST_LINK", "listener"))
        assertEquals(1, fixture.port.heartbeats.size)

        val sharing = disabled.copy(version = 5L, settings = disabled.settings.copy(shareAudioLinks = true))
        fixture.owner.onRoomState(envelope(sharing, "REQUEST_LINK", "listener"))
        fixture.owner.onRoomState(envelope(sharing.copy(version = 6L), "REQUEST_PLAY", "host"))
        assertEquals(1, fixture.port.heartbeats.size)
    }

    @Test
    fun `changed track and unavailable signals use link owner confirmation`() = runTest {
        val fixture = fixture()
        val replacement = track.copy(stableKey = "netease:replacement", audioId = "replacement")
        val changed = fixture.port.room.copy(version = 2L, queue = listOf(replacement), track = replacement)
        fixture.owner.onRoomState(envelope(changed, "SET_TRACK", "host"))
        assertEquals(listOf("SET_TRACK"), fixture.port.applied)

        val unavailable = changed.copy(version = 3L)
        fixture.owner.onRoomState(envelope(unavailable, "LINK_UNAVAILABLE", "host"))
        fixture.owner.onRoomState(envelope(unavailable.copy(version = 4L), "LINK_UNAVAILABLE", "host"))
        assertTrue(fixture.sent.any { it.type == "REQUEST_LINK" })
    }

    @Test
    fun `controller resume does not reapply its own state`() = runTest {
        val fixture = fixture(controller = true)
        val resumed = fixture.port.room.copy(version = 2L)
        fixture.owner.onRoomResumed(envelope(resumed, "PLAY", "host", "controller_reconnected"))
        assertTrue(fixture.port.applied.isEmpty())
        assertEquals(null, fixture.port.notice)
    }

    private fun envelope(
        state: ListenTogetherRoomState,
        type: String,
        userUuid: String,
        message: String? = null
    ) = ListenTogetherSocketEnvelope(
        type = "room_state_updated",
        state = state,
        causedBy = ListenTogetherCause(userUuid = userUuid, type = type, eventId = "$type:${state.version}"),
        message = message
    )

    private fun TestScope.fixture(controller: Boolean = false): Fixture {
        val port = FakePort(track, controller)
        val sent = mutableListOf<ListenTogetherEvent>()
        val localControl = ListenTogetherLocalControlOwner(
            this,
            object : ListenTogetherLocalControlPort {
                override fun currentRoomId(): String = "room"
                override fun isController(): Boolean = controller
                override fun nextEventId(): String = "event"
                override fun markOutbound(eventId: String?) = Unit
                override fun noteOutboundSync() = Unit
                override fun send(event: ListenTogetherEvent, reason: String): Boolean = true
            },
            elapsedRealtimeMs = { 1_000L }, wallTimeMs = { 1_000L }
        )
        val link = ListenTogetherControllerLinkOwner(
            scope = this,
            session = object : ListenTogetherLinkSessionPort {
                override fun sessionState(): ListenTogetherSessionState = port.session()
                override fun roomState(): ListenTogetherRoomState? = port.room()
                override fun publish(event: ListenTogetherEvent, reason: String, noteSync: Boolean): Boolean {
                    sent += event
                    return true
                }
                override fun publishControllerHeartbeat(reason: String) = port.publishControllerHeartbeat(reason)
            },
            playback = object : ListenTogetherLinkPlaybackPort {
                override fun currentSong(): SongItem? = null
                override fun stableKey(song: SongItem): String? = null
                override fun playbackPositionMs(): Long = 0L
                override fun playbackResolutionPending(): Boolean = false
                override suspend fun resolveShareableStreamUrls(song: SongItem) =
                    ListenTogetherStreamResolution(emptyList(), false)
                override fun hasUsableLocalDirectStream(track: ListenTogetherTrack): Boolean = false
            },
            events = object : ListenTogetherLinkEventPort {
                override fun requestLink(
                    stableKey: String, currentIndex: Int, track: ListenTogetherTrack, forceRefresh: Boolean
                ) = ListenTogetherEvent(type = "REQUEST_LINK", requestTrackStableKey = stableKey)
                override fun linkReady(stableKey: String, positionMs: Long, streamUrlsOverride: List<String>): ListenTogetherEvent? = null
                override fun linkUnavailable(stableKey: String): ListenTogetherEvent? = null
            },
            elapsedRealtimeMs = { 1_000L }
        )
        return Fixture(
            port,
            ListenTogetherRoomSocketEventOwner(port, localControl, link, ListenTogetherRecentEventTracker()),
            sent
        )
    }

    private data class Fixture(
        val port: FakePort,
        val owner: ListenTogetherRoomSocketEventOwner,
        val sent: MutableList<ListenTogetherEvent>
    )

    private class FakePort(track: ListenTogetherTrack, controller: Boolean) : ListenTogetherRoomSocketEventPort {
        var snapshot = ListenTogetherSessionState(
            roomId = "room", userUuid = if (controller) "host" else "listener",
            role = if (controller) "controller" else "listener",
            connectionState = ListenTogetherConnectionState.CONNECTED
        )
        var room = ListenTogetherRoomState(
            roomId = "room", version = 1L, controllerUserUuid = "host", queue = listOf(track), track = track
        )
        var reject = false
        var notice: String? = null
        var error: String? = null
        val applied = mutableListOf<String?>()
        val heartbeats = mutableListOf<String>()
        val closed = mutableListOf<String?>()
        var pauses = 0

        override fun session(): ListenTogetherSessionState = snapshot
        override fun room(): ListenTogetherRoomState = room
        override fun accept(
            state: ListenTogetherRoomState,
            expectedPositionMs: Long?,
            source: RoomStateSource,
            cause: ListenTogetherCause?
        ): AcceptedRoomState? {
            if (reject) return null
            room = state
            return AcceptedRoomState(state, expectedPositionMs)
        }
        override fun updateNotice(notice: String?, clearError: Boolean) {
            this.notice = notice
            if (clearError) error = null
        }
        override fun applyToPlayer(state: ListenTogetherRoomState, causeType: String?, expectedPositionMs: Long?) {
            applied += causeType
        }
        override fun publishControllerHeartbeat(reason: String) { heartbeats += reason }
        override fun pauseClosedRoomPlayback() { pauses++ }
        override fun closeRoomLocally(reason: String?) { closed += reason }
    }
}
