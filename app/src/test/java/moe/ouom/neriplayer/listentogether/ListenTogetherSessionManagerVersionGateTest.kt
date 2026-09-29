package moe.ouom.neriplayer.listentogether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.api.ltw.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherAppliedEvent
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherControlResponse
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherMember
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherRoomResponse
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherStateResponse
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherSessionManagerVersionGateTest {
    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `delayed http state cannot overwrite newer websocket state`() = runBlocking {
        val httpEntered = CountDownLatch(1)
        val releaseHttp = CountDownLatch(1)
        val staleHttpState = roomState(
            version = 1L,
            track = track("netease:old", "old-song"),
            playbackState = "paused",
            basePositionMs = 1_000L
        )
        val api = ListenTogetherApi(
            clientForDelayedState(
                staleState = staleHttpState,
                httpEntered = httpEntered,
                releaseHttp = releaseHttp
            )
        )
        val manager = ListenTogetherSessionManager(
            api = api,
            webSocketClient = ListenTogetherWebSocketClient(OkHttpClient())
        )
        manager.joinRoom(
            baseUrl = BASE_URL,
            roomId = ROOM_ID,
            userUuid = USER_UUID,
            nickname = "Tester",
            joinSecret = JOIN_SECRET
        )

        val refreshJob = async(Dispatchers.IO) {
            manager.refreshRoomState(BASE_URL, ROOM_ID)
        }
        assertTrue(httpEntered.await(2, TimeUnit.SECONDS))

        val freshSocketState = roomState(
            version = 2L,
            track = track("netease:fresh", "fresh-song"),
            playbackState = "playing",
            basePositionMs = 2_000L
        )
        manager.receiveSocketEnvelope(
            ListenTogetherSocketEnvelope(
                type = "room_state_updated",
                state = freshSocketState,
                expectedPositionMs = 2_500L,
                causedBy = ListenTogetherCause(
                    userUuid = USER_UUID,
                    type = "REQUEST_PLAY"
                )
            )
        )

        releaseHttp.countDown()
        withTimeout(2_000.milliseconds) {
            refreshJob.await()
        }

        val finalState = requireNotNull(manager.roomState.value)
        assertEquals(2L, finalState.version)
        assertEquals("netease:fresh", finalState.track?.stableKey)
        assertEquals("playing", finalState.playback.state)
        assertEquals(2_500L, manager.sessionState.value.expectedPositionMs)
    }

    @Test
    fun `same version http state only supplements position`() = runBlocking {
        val staleSameVersionState = roomState(
            version = 2L,
            track = track("netease:old", "old-song"),
            playbackState = "paused",
            basePositionMs = 1_000L
        )
        val api = ListenTogetherApi(
            clientForState(
                state = staleSameVersionState,
                expectedPositionMs = 3_500L
            )
        )
        val manager = ListenTogetherSessionManager(
            api = api,
            webSocketClient = ListenTogetherWebSocketClient(OkHttpClient())
        )
        manager.joinRoom(
            baseUrl = BASE_URL,
            roomId = ROOM_ID,
            userUuid = USER_UUID,
            nickname = "Tester",
            joinSecret = JOIN_SECRET
        )

        val freshSocketState = roomState(
            version = 2L,
            track = track("netease:fresh", "fresh-song"),
            playbackState = "playing",
            basePositionMs = 2_000L
        )
        manager.receiveSocketEnvelope(
            ListenTogetherSocketEnvelope(
                type = "room_state_updated",
                state = freshSocketState,
                expectedPositionMs = 2_500L,
                causedBy = ListenTogetherCause(
                    userUuid = USER_UUID,
                    type = "REQUEST_PLAY"
                )
            )
        )

        manager.refreshRoomState(BASE_URL, ROOM_ID)

        val finalState = requireNotNull(manager.roomState.value)
        assertEquals(2L, finalState.version)
        assertEquals("netease:fresh", finalState.track?.stableKey)
        assertEquals("playing", finalState.playback.state)
        assertEquals(3_500L, manager.sessionState.value.expectedPositionMs)
    }

    @Test
    fun `late http state cannot resurrect a room after leave`() = runBlocking {
        val httpEntered = CountDownLatch(1)
        val releaseHttp = CountDownLatch(1)
        val api = ListenTogetherApi(
            clientForDelayedState(
                staleState = roomState(
                    version = 3L,
                    track = track("netease:late", "late-song"),
                    playbackState = "playing",
                    basePositionMs = 4_000L
                ),
                httpEntered = httpEntered,
                releaseHttp = releaseHttp
            )
        )
        val manager = ListenTogetherSessionManager(
            api = api,
            webSocketClient = ListenTogetherWebSocketClient(OkHttpClient())
        )
        manager.joinRoom(
            baseUrl = BASE_URL,
            roomId = ROOM_ID,
            userUuid = USER_UUID,
            nickname = "Tester",
            joinSecret = JOIN_SECRET
        )

        val refreshJob = async(Dispatchers.IO) {
            manager.refreshRoomState(BASE_URL, ROOM_ID)
        }
        assertTrue(httpEntered.await(2, TimeUnit.SECONDS))
        manager.leaveRoom()
        releaseHttp.countDown()
        withTimeout(2_000.milliseconds) {
            refreshJob.await()
        }

        assertNull(manager.roomState.value)
        assertNull(manager.sessionState.value.roomId)
    }

    @Test
    fun `socket ingress isolates room errors and closes only the active room`() = runBlocking {
        val manager = ListenTogetherSessionManager(
            api = ListenTogetherApi(clientForState(roomState(1L, track("netease:one", "one"), "paused", 0L), 0L)),
            webSocketClient = ListenTogetherWebSocketClient(OkHttpClient())
        )
        manager.joinRoom(BASE_URL, ROOM_ID, USER_UUID, "Tester", joinSecret = JOIN_SECRET)

        manager.receiveSocketEnvelope(ListenTogetherSocketEnvelope(
            type = "error", roomId = "other_room", message = "foreign error"
        ))
        assertNull(manager.sessionState.value.lastError)

        manager.receiveSocketEnvelope(ListenTogetherSocketEnvelope(
            type = "error", roomId = ROOM_ID, message = "temporary failure"
        ))
        assertEquals("temporary failure", manager.sessionState.value.lastError)
        manager.receiveSocketEnvelope(ListenTogetherSocketEnvelope(type = "np_pong", roomId = ROOM_ID, nowMs = 1_000L))
        assertNull(manager.sessionState.value.lastError)
        manager.receiveSocketEnvelope(ListenTogetherSocketEnvelope(type = "pong", roomId = ROOM_ID))
        manager.receiveSocketEnvelope(ListenTogetherSocketEnvelope(type = "welcome", roomId = ROOM_ID, nowMs = 2_000L))
        manager.receiveSocketEnvelope(ListenTogetherSocketEnvelope(
            type = "ack", roomId = ROOM_ID,
            result = ListenTogetherControlResponse(
                ok = true,
                applied = ListenTogetherAppliedEvent(type = "ACK", nowMs = 3_000L)
            )
        ))
        assertNull(manager.sessionState.value.lastError)
        manager.receiveSocketEnvelope(ListenTogetherSocketEnvelope(
            type = "room_closed", roomId = ROOM_ID, message = "controller_left"
        ))
        assertNull(manager.roomState.value)
        assertNull(manager.sessionState.value.roomId)
        assertEquals("controller_left", manager.sessionState.value.roomNotice)
        assertNull(manager.sessionState.value.lastError)
    }

    private fun clientForState(
        state: ListenTogetherRoomState,
        expectedPositionMs: Long
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val responseBody = when {
                        request.method == "POST" && request.url.encodedPath.endsWith("/join") -> {
                            joinResponseBody()
                        }

                        request.method == "POST" && request.url.encodedPath.endsWith("/leave") -> {
                            "{\"ok\":true}"
                        }

                        request.method == "GET" && request.url.encodedPath.endsWith("/state") -> {
                            json.encodeToString(
                                ListenTogetherStateResponse(
                                    ok = true,
                                    state = state,
                                    expectedPositionMs = expectedPositionMs
                                )
                            )
                        }

                        else -> error("unexpected request: ${request.method} ${request.url}")
                    }
                    jsonResponse(request, responseBody)
                }
            )
            .build()
    }

    private fun clientForDelayedState(
        staleState: ListenTogetherRoomState,
        httpEntered: CountDownLatch,
        releaseHttp: CountDownLatch
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val responseBody = when {
                        request.method == "POST" && request.url.encodedPath.endsWith("/join") -> {
                            joinResponseBody()
                        }

                        request.method == "POST" && request.url.encodedPath.endsWith("/leave") -> {
                            "{\"ok\":true}"
                        }

                        request.method == "GET" && request.url.encodedPath.endsWith("/state") -> {
                            httpEntered.countDown()
                            assertTrue(releaseHttp.await(2, TimeUnit.SECONDS))
                            json.encodeToString(
                                ListenTogetherStateResponse(
                                    ok = true,
                                    state = staleState,
                                    expectedPositionMs = 1_500L
                                )
                            )
                        }

                        else -> error("unexpected request: ${request.method} ${request.url}")
                    }
                    jsonResponse(request, responseBody)
                }
            )
            .build()
    }

    private fun joinResponseBody(): String {
        return json.encodeToString(
            ListenTogetherRoomResponse(
                ok = true,
                roomId = ROOM_ID,
                userUuid = USER_UUID,
                nickname = "Tester",
                role = "controller",
                token = "unit-token"
            )
        )
    }

    private fun jsonResponse(
        request: okhttp3.Request,
        responseBody: String
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun roomState(
        version: Long,
        track: ListenTogetherTrack,
        playbackState: String,
        basePositionMs: Long
    ): ListenTogetherRoomState {
        return ListenTogetherRoomState(
            roomId = ROOM_ID,
            version = version,
            controllerUserUuid = USER_UUID,
            members = listOf(
                ListenTogetherMember(
                    userUuid = USER_UUID,
                    nickname = "Tester",
                    role = "controller",
                    joinedAt = 1_000L
                )
            ),
            queue = listOf(track),
            currentIndex = 0,
            track = track,
            playback = ListenTogetherPlaybackState(
                state = playbackState,
                basePositionMs = basePositionMs,
                baseTimestampMs = 10_000L
            )
        )
    }

    private fun track(stableKey: String, audioId: String): ListenTogetherTrack {
        return ListenTogetherTrack(
            stableKey = stableKey,
            channelId = ListenTogetherChannels.NETEASE,
            audioId = audioId,
            name = audioId,
            artist = "Tester"
        )
    }

    private companion object {
        private const val BASE_URL = "http://listen.test"
        private const val ROOM_ID = "ABCD23"
        private const val USER_UUID = "123e4567-e89b-12d3-a456-426614174000"
        private const val JOIN_SECRET = "test-secret"

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
