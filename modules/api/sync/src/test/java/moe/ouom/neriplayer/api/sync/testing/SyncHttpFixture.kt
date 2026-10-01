package moe.ouom.neriplayer.api.sync.testing

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

internal class SyncHttpFixture(
    private val status: Int = 200,
    private val content: ByteArray = "{}".toByteArray(),
    private val headers: Map<String, String> = emptyMap()
) {
    val requests = mutableListOf<Request>()
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        requests += request
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(status).message("stub").body(content.toResponseBody("application/json".toMediaType()))
        headers.forEach(response::header)
        response.build()
    }).build()
}
