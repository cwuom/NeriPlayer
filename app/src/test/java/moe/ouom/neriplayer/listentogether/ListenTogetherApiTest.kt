package moe.ouom.neriplayer.listentogether

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.listentogether.network.http.ListenTogetherApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherApiTest {
    @Test
    fun `server availability probes anonymous health endpoint`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                assertEquals("/healthz", request.url.encodedPath)
                assertTrue(request.header("Authorization").isNullOrBlank())
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        "{\"ok\":true,\"service\":\"neriplayer-listen-together-worker\"}"
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()

        val result = ListenTogetherApi(client).testServerAvailability("https://worker.example")

        assertTrue(result.ok)
        assertEquals("reachable", result.message)
    }

    @Test
    fun `leave room sends authenticated leave request`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                assertEquals("POST", request.method)
                assertEquals("/api/rooms/ABC234/leave", request.url.encodedPath)
                assertEquals("Bearer leave-token", request.header("Authorization"))
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"ok\":true}".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val result = ListenTogetherApi(client).leaveRoom(
            baseUrl = "https://worker.example",
            roomId = "ABC234",
            token = "leave-token"
        )

        assertTrue(result.ok)
    }
}
