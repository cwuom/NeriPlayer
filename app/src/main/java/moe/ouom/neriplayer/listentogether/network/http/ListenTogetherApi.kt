package moe.ouom.neriplayer.listentogether.network.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherControlResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCreateRoomRequest
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherInitialSnapshot
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherJoinRoomRequest
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherStateResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES = 2L * 1024L * 1024L

data class ListenTogetherServerTestResult(
    val ok: Boolean,
    val message: String
)

class ListenTogetherApi(
    private val okHttpClient: OkHttpClient
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun createRoom(
        baseUrl: String,
        userUuid: String,
        nickname: String,
        initialSnapshot: ListenTogetherInitialSnapshot
    ): ListenTogetherRoomResponse {
        return post(
            url = "${baseUrl.normalizeBaseUrl()}/api/rooms",
            body = ListenTogetherCreateRoomRequest(
                userUuid = userUuid,
                nickname = nickname,
                initialSnapshot = initialSnapshot
            )
        )
    }

    suspend fun joinRoom(
        baseUrl: String,
        roomId: String,
        userUuid: String,
        nickname: String
    ): ListenTogetherRoomResponse {
        return post(
            url = "${baseUrl.normalizeBaseUrl()}/api/rooms/$roomId/join",
            body = ListenTogetherJoinRoomRequest(
                userUuid = userUuid,
                nickname = nickname
            )
        )
    }

    suspend fun getRoomState(
        baseUrl: String,
        roomId: String
    ): ListenTogetherStateResponse {
        return get("${baseUrl.normalizeBaseUrl()}/api/rooms/$roomId/state")
    }

    suspend fun sendControlEvent(
        baseUrl: String,
        roomId: String,
        token: String,
        event: ListenTogetherEvent
    ): ListenTogetherControlResponse {
        return post(
            url = "${baseUrl.normalizeBaseUrl()}/api/rooms/$roomId/control",
            body = event,
            bearerToken = token
        )
    }

    suspend fun testServerAvailability(baseUrl: String): ListenTogetherServerTestResult = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.normalizedHttpBaseUrlOrNull()
            ?: return@withContext ListenTogetherServerTestResult(
                ok = false,
                message = "invalid_base_url"
            )
        val request = Request.Builder()
            .url("$normalizedBaseUrl/api/rooms/ABCDEF/state")
            .get()
            .build()
        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.limitedString().orEmpty()
                val looksLikeListenTogetherService =
                    body.contains("\"ok\"", ignoreCase = true) ||
                        body.contains("room not initialized", ignoreCase = true) ||
                        body.contains("not found", ignoreCase = true)
                if (looksLikeListenTogetherService) {
                    ListenTogetherServerTestResult(
                        ok = true,
                        message = "reachable"
                    )
                } else {
                    ListenTogetherServerTestResult(
                        ok = false,
                        message = "invalid_response"
                    )
                }
            }
        }.getOrElse {
            ListenTogetherServerTestResult(
                ok = false,
                message = it.message ?: it.javaClass.simpleName
            )
        }
    }

    private suspend inline fun <reified T> get(url: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.limitedString().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("ListenTogether GET failed (${response.code}): $body")
            }
            json.decodeFromString(body)
        }
    }

    private suspend inline fun <reified RequestBodyT, reified ResponseT> post(
        url: String,
        body: RequestBodyT,
        bearerToken: String? = null
    ): ResponseT = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(
                json.encodeToString(body)
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        bearerToken?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("Authorization", "Bearer $it")
        }
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.limitedString().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("ListenTogether POST failed (${response.code}): $responseBody")
            }
            json.decodeFromString(responseBody)
        }
    }
}

private fun ResponseBody.limitedString(): String {
    val contentLength = contentLength()
    if (contentLength > LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES) {
        throw IOException("ListenTogether response too large: $contentLength bytes")
    }
    val bytes = byteStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES) {
                throw IOException("ListenTogether response too large: $total bytes")
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
    return bytes.toString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}
