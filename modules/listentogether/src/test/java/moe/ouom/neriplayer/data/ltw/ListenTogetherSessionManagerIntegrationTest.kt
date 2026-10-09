package moe.ouom.neriplayer.data.ltw

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.api.ltw.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.data.ltw.testing.*
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherRoomResponse
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherStateResponse
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherMember
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.listentogether.protocol.listenTogetherProtocolJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherSessionManagerIntegrationTest {
    private val fixtures = mutableListOf<Fixture>()

    @After
    fun closeSessions() {
        fixtures.forEach { it.manager.close(); it.httpClient.connectionPool.evictAll(); it.httpClient.dispatcher.executorService.shutdown() }
        fixtures.clear()
    }

    private fun fixture(scope: TestScope, role: String = "controller", queueMainWork: Boolean = false) =
        Fixture(scope, role, queueMainWork).also { fixtures += it }

    private fun sessionTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            closeSessions()
        }
    }

    @Test
    fun `session facade preserves transport event types and inactive lifecycle remains harmless`() = sessionTest {
        val f = fixture(this)
        f.manager.onApplicationBackgrounded()
        f.manager.onApplicationForegrounded()
        f.manager.resumeListenerAfterSafetyPause()
        assertTrue(f.player.calls.contains("safety:retry"))
        assertEquals(listOf("PLAY", "PAUSE", "SEEK", "REQUEST_PLAY", "REQUEST_PAUSE", "REQUEST_SEEK"), listOf(
            f.manager.buildPlayEvent(1L), f.manager.buildPauseEvent(1L), f.manager.buildSeekEvent(1L),
            f.manager.buildRequestPlayEvent(1L), f.manager.buildRequestPauseEvent(1L), f.manager.buildRequestSeekEvent(1L)
        ).map { it.type })
        f.player.transportActive = true
        assertEquals("playing", f.manager.buildSeekEvent(1L).state)
        f.manager.disconnectWebSocket()
        assertEquals(ListenTogetherConnectionState.DISCONNECTED, f.manager.sessionState.value.connectionState)
        f.player.playbackCommandFlow.emit(PlaybackCommand("PLAY", PlaybackCommandSource.LOCAL))
        assertTrue(f.events.isEmpty())
        f.player.transportActive = false
        f.player.isPlayingFlow.value = true
        assertEquals("playing", f.manager.buildSeekEvent(1L).state)
        f.player.isPlayingFlow.value = false
        f.player.playWhenReadyFlow.value = true
        f.player.transportFailure = IllegalStateException("player unavailable")
        assertEquals("playing", f.manager.buildSeekEvent(1L).state)
    }

    @Test
    fun `http control rejects missing credentials and sends only complete session credentials`() = sessionTest {
        val empty = fixture(this)
        assertEquals("baseUrl missing", empty.manager.sendControlEvent(ListenTogetherEvent("PLAY")).error)
        for ((room, token, error) in listOf(Triple(null, "token", "roomId missing"), Triple("ABC234", null, "token missing"), Triple("ABC234", " ", "token missing"), Triple("ABC234", "token", null))) {
            val f = fixture(this)
            f.joinResponse = f.joinResponse.copy(roomId = room, token = token, state = null)
            f.join()
            val response = f.manager.sendControlEvent(ListenTogetherEvent("PLAY"))
            assertEquals(error, response.error)
            assertEquals(error == null, response.ok)
        }
    }

    @Test
    fun `socket lifecycle opens active room and isolates closure failure and obsolete callbacks`() = sessionTest {
        val f = fixture(this)
        f.join(); f.connect()
        assertEquals(ListenTogetherConnectionState.CONNECTED, f.manager.sessionState.value.connectionState)
        assertTrue(f.events.any { it.type == "HEARTBEAT" })
        f.listener.onProtocolError("invalid", IllegalArgumentException("bad json"))
        assertEquals("Protocol: bad json", f.manager.sessionState.value.lastError)
        f.listener.onClosed(1006, "temporary")
        assertEquals("temporary", f.manager.sessionState.value.lastError)
        f.listener.onFailure(IllegalStateException())
        assertEquals("IllegalStateException", f.manager.sessionState.value.lastError)
        f.listener.onFailure(IOException("socket offline"))
        assertEquals("socket offline", f.manager.sessionState.value.lastError)
        f.listener.onClosed(1000, "")
        assertNull(f.manager.sessionState.value.lastError)
        f.manager.leaveRoom()
        runCurrent()
        f.listener.onOpen()
        verify(f.socket).disconnect(1000, "inactive_session")
        assertNull(f.manager.roomState.value)
    }

    @Test
    fun `listener state commits repost to main and stale or foreign state cannot change playback`() = sessionTest {
        val f = fixture(this, "listener", queueMainWork = true)
        f.join(); f.connect()
        runCurrent()
        f.player.calls.clear()
        val fresh = f.serverRoom.copy(version = 2L, playback = f.serverRoom.playback.copy(state = "playing"))
        f.listener.onMessage(ListenTogetherSocketEnvelope(type = "room_state_updated", roomId = "ABC234", state = fresh, expectedPositionMs = 10_000L))
        assertEquals(2L, f.manager.roomState.value?.version)
        assertTrue(f.player.commandSources.all { it == PlaybackCommandSource.REMOTE_SYNC })
        f.mainThread = false
        f.manager.applyRoomStateToPlayer(fresh, "SEEK", 20_000L)
        assertNotEquals(20_000L, f.player.playbackPositionFlow.value)
        f.mainThread = true
        runCurrent()
        assertEquals(20_000L, f.player.playbackPositionFlow.value)
        val before = f.player.calls.toList()
        f.manager.applyRoomStateToPlayer(fresh.copy(version = 1L), "PLAY", 0L)
        f.manager.applyRoomStateToPlayer(fresh.copy(roomId = "OTHER1"), "PLAY", 0L)
        assertEquals(before, f.player.calls)
        f.serverRoom = fresh.copy(version = 3L)
        f.manager.refreshRoomState(BASE_URL, "ABC234")
        assertEquals(3L, f.manager.roomState.value?.version)
        assertEquals("Bearer token", f.lastStateAuthorization)
        f.manager.refreshRoomState("https://another.test", "ABC234")
        assertNull(f.lastStateAuthorization)
    }

    @Test
    fun `local commands respect source permissions and suppress stale listener intent`() = sessionTest {
        val f = fixture(this, "listener")
        f.join(); f.connect()
        f.events.clear()
        f.player.playbackCommandFlow.emit(PlaybackCommand("PLAY", PlaybackCommandSource.REMOTE_SYNC))
        f.player.playbackCommandFlow.emit(PlaybackCommand("PLAY", PlaybackCommandSource.LOCAL_SAFETY))
        assertTrue(f.events.isEmpty())
        f.player.currentSongFlow.value = testSong("2")
        f.player.currentQueueFlow.value = listOf(testSong("2"))
        f.player.playbackCommandFlow.emit(PlaybackCommand("PAUSE", PlaybackCommandSource.LOCAL))
        assertTrue(f.events.isEmpty())
        f.player.currentSongFlow.value = testSong()
        f.player.currentQueueFlow.value = listOf(testSong())
        f.player.playbackCommandFlow.emit(PlaybackCommand("SEEK", PlaybackCommandSource.LOCAL, positionMs = 123L))
        advanceTimeBy(250); runCurrent()
        assertTrue(f.events.any { it.type == "REQUEST_SEEK" && it.positionMs == 123L })
        f.player.playbackCommandFlow.emit(PlaybackCommand("UNKNOWN", PlaybackCommandSource.LOCAL))
        val disabled = f.serverRoom.copy(version = 2L, settings = ListenTogetherRoomSettings(allowMemberControl = false))
        f.listener.onMessage(ListenTogetherSocketEnvelope(type = "room_state_updated", state = disabled))
        f.events.clear()
        f.player.playbackCommandFlow.emit(PlaybackCommand("PLAY", PlaybackCommandSource.LOCAL))
        assertTrue(f.events.isEmpty())
        assertNotNull(f.manager.sessionState.value.lastError)
    }

    @Test
    fun `forwarded member controls deduplicate reject stale targets and yield to recent controller action`() = sessionTest {
        val f = fixture(this)
        f.join(); f.connect()
        f.events.clear()
        val request = ListenTogetherSocketEnvelope(type = "member_control_requested", roomId = "ABC234", causedBy = ListenTogetherCause(userUuid = PEER_UUID, type = "REQUEST_SEEK", eventId = "request"), requestSequence = 1L, positionMs = 1_000L, requestTrackStableKey = "netease:1")
        f.listener.onMessage(request)
        assertTrue(f.events.any { it.type == "SEEK" })
        val committed = f.events.count { it.type == "SEEK" }
        f.listener.onMessage(request)
        assertEquals(committed, f.events.count { it.type == "SEEK" })
        f.listener.onMessage(request.copy(requestSequence = 2L, causedBy = request.causedBy?.copy(eventId = "stale"), requestTrackStableKey = "other"))
        assertEquals(committed, f.events.count { it.type == "SEEK" })
        f.player.playbackCommandFlow.emit(PlaybackCommand("PLAY", PlaybackCommandSource.LOCAL))
        f.listener.onMessage(request.copy(requestSequence = 3L, causedBy = request.causedBy?.copy(eventId = "priority")))
        assertEquals(committed, f.events.count { it.type == "SEEK" })
        f.now += 2_000L
        f.listener.onMessage(request.copy(requestSequence = 4L, causedBy = request.causedBy?.copy(eventId = "next")))
        assertEquals(committed + 1, f.events.count { it.type == "SEEK" })
        f.listener.onMessage(request.copy(requestSequence = 5L, causedBy = ListenTogetherCause(userUuid = PEER_UUID, type = "BAD", eventId = "bad")))
    }

    @Test
    fun `foreground recovery refreshes state and reports transport failures without losing session`() = sessionTest {
        val f = fixture(this, "listener")
        f.join(); f.connect()
        f.manager.onApplicationBackgrounded()
        f.manager.onApplicationForegrounded()
        runCurrent()
        assertTrue(f.stateRequests > 0)
        f.stateFailure = IOException("offline")
        f.manager.onApplicationForegrounded()
        runCurrent()
        assertEquals("offline", f.manager.sessionState.value.lastError)
        assertEquals("ABC234", f.manager.sessionState.value.roomId)
        f.stateFailure = IOException()
        f.manager.onApplicationForegrounded()
        assertEquals("IOException", f.manager.sessionState.value.lastError)
        f.socketSends = false
        f.manager.updateRoomSettings(ListenTogetherRoomSettings())
        runCurrent()
        assertTrue(f.controlRequests > 0)
    }

    @Test
    fun `controller foreground reconnect refreshes once and failed ping waits for reconnect`() = sessionTest {
        val f = fixture(this)
        f.join(); f.connect()
        f.listener.onClosed(1006, "offline")
        f.manager.onApplicationForegrounded()
        f.listener.onOpen()
        assertEquals(ListenTogetherConnectionState.CONNECTED, f.manager.sessionState.value.connectionState)
        assertTrue(f.stateRequests > 0)
        f.manager.onApplicationForegrounded()
        val requests = f.stateRequests
        assertTrue(f.events.any { it.type == "HEARTBEAT" })
        f.socketSends = false
        f.manager.onApplicationForegrounded()
        assertEquals(requests, f.stateRequests)
    }

    @Test
    fun `connected controller with no room snapshot or shareable song cannot publish a heartbeat`() = sessionTest {
        val withoutState = fixture(this)
        withoutState.joinResponse = withoutState.joinResponse.copy(state = null)
        withoutState.join(); withoutState.connect()
        assertTrue(withoutState.events.none { it.type == "HEARTBEAT" })
        val withoutSong = fixture(this)
        withoutSong.join()
        withoutSong.player.currentSongFlow.value = null
        withoutSong.connect()
        assertTrue(withoutSong.events.none { it.type == "HEARTBEAT" })
    }

    @Test
    fun `listener promoted to controller while connected starts publishing heartbeats`() = sessionTest {
        val f = fixture(this, "listener")
        f.join(); f.connect()
        runCurrent()
        val promoted = f.serverRoom.copy(
            version = 2L,
            controllerUserUuid = USER_UUID,
            members = f.serverRoom.members.map { member ->
                member.copy(role = if (member.userUuid == USER_UUID) "controller" else "listener")
            }
        )
        f.listener.onMessage(ListenTogetherSocketEnvelope(type = "room_state_updated", roomId = "ABC234", state = promoted, expectedPositionMs = 0L))
        assertEquals("controller", f.manager.sessionState.value.role)

        f.now += 60_000L
        advanceTimeBy(60_000L); runCurrent()
        assertTrue(f.events.any { it.type == "HEARTBEAT" })
    }

    @Test
    fun `silent listener watchdog reports refresh failure and retains membership for recovery`() = sessionTest {
        val f = fixture(this, "listener")
        f.join(); f.connect()
        f.stateFailure = IOException("watchdog offline")
        f.now += 50_000L
        advanceTimeBy(8_000L); runCurrent()
        assertEquals("watchdog offline", f.manager.sessionState.value.lastError)
        assertEquals("ABC234", f.manager.sessionState.value.roomId)
        f.stateFailure = IOException()
        f.connect()
        f.now += 50_000L
        advanceTimeBy(8_000L); runCurrent()
        assertEquals("IOException", f.manager.sessionState.value.lastError)
    }

    @Test
    fun `listener watchdog leaves an in sync player untouched and repairs drift`() = sessionTest {
        val f = fixture(this, "listener")
        f.join(); f.connect()
        runCurrent()
        f.player.calls.clear()
        advanceTimeBy(8_000L); runCurrent()
        assertEquals(emptyList<String>(), f.player.calls)
        f.player.playbackPositionFlow.value = 5_000L
        advanceTimeBy(8_000L); runCurrent()
        assertTrue(f.player.calls.contains("seek:0"))
    }

    @Test
    fun `returning network reconnects a dropped socket at once and leaving stops watching`() = sessionTest {
        val f = fixture(this)
        f.join(); f.connect()
        val monitor = f.platform.networkMonitor
        assertEquals(1, monitor.starts)
        f.listener.onFailure(IOException("network down"))
        assertEquals(1, f.socketConnects)
        requireNotNull(monitor.listener).onDefaultNetworkValidated("wifi")
        assertEquals(2, f.socketConnects)
        assertEquals(ListenTogetherConnectionState.CONNECTING, f.manager.sessionState.value.connectionState)
        f.manager.leaveRoom()
        assertEquals(1, monitor.stops)
    }

    @Test
    fun `backgrounded listener under the playback service holds the wake lock only while reconnecting`() = sessionTest {
        val member = fixture(this, "listener")
        member.platform.initialized = true
        member.join(); member.connect()
        member.manager.onApplicationBackgrounded()
        verify(member.platform.wakeLock, never()).acquire(anyLong())
        member.listener.onFailure(IOException("offline"))
        verify(member.platform.wakeLock, atLeastOnce()).acquire(anyLong())

        val controller = fixture(this)
        controller.platform.initialized = true
        controller.join(); controller.connect()
        controller.manager.onApplicationBackgrounded()
        verify(controller.platform.wakeLock, atLeastOnce()).acquire(anyLong())

        val withoutService = fixture(this, "listener")
        withoutService.platform.initialized = true
        withoutService.platform.playbackServiceReady = false
        withoutService.join(); withoutService.connect()
        withoutService.manager.onApplicationBackgrounded()
        verify(withoutService.platform.wakeLock, atLeastOnce()).acquire(anyLong())
    }

    private class Fixture(scope: TestScope, role: String, queueMainWork: Boolean) {
        val player = FakeListenTogetherPlaybackHost().apply { currentSongFlow.value = testSong(); currentQueueFlow.value = listOf(testSong()) }
        val socket = mock(ListenTogetherWebSocketClient::class.java)
        val platform = FakeListenTogetherPlatformHost()
        lateinit var listener: ListenTogetherWebSocketClient.Listener
        var socketConnects = 0
        val events = mutableListOf<ListenTogetherEvent>()
        var now = 50_000L
        var mainThread = true
        var socketSends = true
        var stateRequests = 0
        var controlRequests = 0
        var lastStateAuthorization: String? = null
        var stateFailure: Throwable? = null
        var serverRoom: ListenTogetherRoomState = testRoom(version = 1L).copy(
            controllerUserUuid = if (role == "controller") USER_UUID else PEER_UUID,
            members = listOf(
                ListenTogetherMember(userUuid = USER_UUID, nickname = "Tester", role = role, joinedAt = 1L),
                ListenTogetherMember(userUuid = PEER_UUID, nickname = "Peer", role = if (role == "controller") "listener" else "controller", joinedAt = 1L)
            )
        )
        var joinResponse = ListenTogetherRoomResponse(ok = true, roomId = "ABC234", userUuid = USER_UUID, nickname = "Tester", role = role, token = "token", state = serverRoom)
        private val json = listenTogetherProtocolJson()
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when {
                request.url.encodedPath.endsWith("/join") -> json.encodeToString(joinResponse)
                request.url.encodedPath.endsWith("/state") -> {
                    stateRequests++
                    lastStateAuthorization = request.header("Authorization")
                    stateFailure?.let { throw it }
                    json.encodeToString(ListenTogetherStateResponse(ok = true, state = serverRoom, expectedPositionMs = 0L))
                }
                request.url.encodedPath.endsWith("/control") -> { controlRequests++; "{\"ok\":true}" }
                request.url.encodedPath.endsWith("/leave") -> "{\"ok\":true}"
                else -> error("unexpected request: ${request.method} ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        private val mainDispatcher = if (queueMainWork) StandardTestDispatcher(scope.testScheduler) else dispatcher

        init {
            val unusedListener = mock(ListenTogetherWebSocketClient.Listener::class.java)
            doAnswer { listener = it.getArgument(1); socketConnects++; null }.`when`(socket).connect(
                anyString(), any(ListenTogetherWebSocketClient.Listener::class.java) ?: unusedListener
            )
            `when`(socket.sendEvent(any(ListenTogetherEvent::class.java) ?: ListenTogetherEvent("PLAY")))
                .thenAnswer { events += it.getArgument<ListenTogetherEvent>(0); socketSends }
            `when`(socket.sendPing(anyLong())).thenAnswer { socketSends }
            `when`(socket.sendLegacyPing()).thenAnswer { socketSends }
        }
        val manager = ListenTogetherSessionManager(ListenTogetherApi(httpClient, dispatcher), socket, player, platform, TestSongMapper, dispatcher, mainDispatcher, { now }, { mainThread })
        suspend fun join() { manager.joinRoom(BASE_URL, "ABC234", USER_UUID, "Tester", joinSecret = "secret") }
        fun connect() { manager.connectWebSocket(); listener.onOpen() }
    }

    private companion object {
        const val BASE_URL = "https://listen.test"
        const val USER_UUID = "123e4567-e89b-12d3-a456-426614174000"
        const val PEER_UUID = "123e4567-e89b-12d3-a456-426614174001"
    }
}
