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
    private val headers: Map<String, String> = emptyMap(),
    private val directoryStatus: Int = 207,
    private val directoryContent: ByteArray = """
        <d:multistatus xmlns:d="DAV:"><d:response><d:href>/</d:href>
        <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
        <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
    """.trimIndent().toByteArray()
) {
    val requests = mutableListOf<Request>()
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        requests += request
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (request.method == "PROPFIND") directoryStatus else status)
            .message("stub").body(
                (if (request.method == "PROPFIND") directoryContent else content)
                    .toResponseBody("application/json".toMediaType())
            )
        headers.forEach(response::header)
        response.build()
    }).build()
}
