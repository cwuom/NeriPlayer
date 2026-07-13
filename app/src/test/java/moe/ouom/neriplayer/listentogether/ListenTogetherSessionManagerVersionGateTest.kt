package moe.ouom.neriplayer.listentogether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.listentogether.network.http.ListenTogetherApi
import moe.ouom.neriplayer.listentogether.network.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherMember
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherPlaybackState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherStateResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ListenTogetherSessionManagerVersionGateTest {

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
            nickname = "Tester"
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
        manager.handleSocketRoomStateForTest(
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
        withTimeout(2_000L) {
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
            nickname = "Tester"
        )

        val freshSocketState = roomState(
            version = 2L,
            track = track("netease:fresh", "fresh-song"),
            playbackState = "playing",
            basePositionMs = 2_000L
        )
        manager.handleSocketRoomStateForTest(
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
            nickname = "Tester"
        )

        val refreshJob = async(Dispatchers.IO) {
            manager.refreshRoomState(BASE_URL, ROOM_ID)
        }
        assertTrue(httpEntered.await(2, TimeUnit.SECONDS))
        manager.leaveRoom()
        releaseHttp.countDown()
        withTimeout(2_000L) {
            refreshJob.await()
        }

        assertNull(manager.roomState.value)
        assertNull(manager.sessionState.value.roomId)
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

    private fun ListenTogetherSessionManager.handleSocketRoomStateForTest(
        message: ListenTogetherSocketEnvelope
    ) {
        val method = ListenTogetherSessionManager::class.java.getDeclaredMethod(
            "handleSocketRoomState",
            ListenTogetherSocketEnvelope::class.java
        )
        method.isAccessible = true
        method.invoke(this, message)
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

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
