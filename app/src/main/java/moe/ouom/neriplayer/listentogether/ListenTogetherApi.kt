package moe.ouom.neriplayer.listentogether

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

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
                val body = response.body?.string().orEmpty()
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
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("ListenTogether GET failed (${response.code}): $body")
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
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("ListenTogether POST failed (${response.code}): $responseBody")
            }
            json.decodeFromString(responseBody)
        }
    }
}

internal fun String.normalizeBaseUrl(): String {
    return normalizedHttpBaseUrlOrNull()
        ?: throw IllegalArgumentException("ListenTogether baseUrl must use http or https")
}

internal fun String.normalizedHttpBaseUrlOrNull(): String? {
    val candidate = trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
    val scheme = runCatching { candidate.toUri().scheme }
        .getOrNull()
        ?.lowercase(Locale.ROOT)
        ?: return null
    if (scheme != "http" && scheme != "https") return null
    return candidate
}
