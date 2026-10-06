package moe.ouom.neriplayer.platform.bilibili.api.client

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.bilibili.api.auth.BiliCookieSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** WBI keys and mixin key from the public Bilibili WBI signing documentation example */
internal const val DOC_WBI_IMG_URL = "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"
internal const val DOC_WBI_SUB_URL = "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"
internal const val DOC_WBI_MIXIN_KEY = "ea1db124af3c7062474693fa704f4ff8"
internal const val NAV_PATH = "/x/web-interface/nav"

internal fun wbiNavResponse(imgUrl: String = DOC_WBI_IMG_URL, subUrl: String = DOC_WBI_SUB_URL): String =
    """{"code":0,"data":{"wbi_img":{"img_url":"$imgUrl","sub_url":"$subUrl"}}}"""

internal class BiliTestReply(val body: String, val code: Int = 200)

internal fun json(body: String) = BiliTestReply(body)

/**
 * Routes every Bili request to canned JSON and records it; WBI nav defaults to the documented keys.
 */
internal class BiliTestHttp(
    private val route: (Request) -> BiliTestReply?
) {
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val reply = route(request)
            ?: BiliTestReply(wbiNavResponse()).takeIf { request.url.encodedPath == NAV_PATH }
            ?: error("Unexpected request: ${request.url}")
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(reply.code).message(if (reply.code == 200) "OK" else "Error")
            .body(reply.body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    fun requestsTo(path: String): List<Request> = synchronized(requests) {
        requests.filter { it.url.encodedPath == path }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

internal fun biliClientTest(
    cookies: Map<String, String> = mapOf("SESSDATA" to "test-session"),
    route: (Request) -> BiliTestReply?,
    block: suspend TestScope.(BiliTestHttp, BiliClient) -> Unit
) = runTest {
    val http = BiliTestHttp(route)
    try {
        block(http, BiliClient(BiliCookieSource { cookies }, http.client))
    } finally {
        http.close()
    }
}

internal fun md5Hex(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Recomputes w_rid as md5 of the key-sorted, percent-encoded query followed by the mixin key */
internal fun expectedWbiSignature(request: Request, mixinKey: String = DOC_WBI_MIXIN_KEY): String {
    val url = request.url
    val query = url.queryParameterNames
        .filter { it != "w_rid" }
        .sorted()
        .joinToString("&") { name -> "${percentEncode(name)}=${percentEncode(url.queryParameter(name).orEmpty())}" }
    return md5Hex(query + mixinKey)
}

private fun percentEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
