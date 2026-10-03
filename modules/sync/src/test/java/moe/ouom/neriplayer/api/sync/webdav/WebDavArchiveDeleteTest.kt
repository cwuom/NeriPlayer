package moe.ouom.neriplayer.api.sync.webdav

import moe.ouom.neriplayer.data.model.sync.transport.WebDavArchiveEntry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WebDavArchiveDeleteTest {
    private val token = "urn:uuid:delete-fixture"
    private val entry = WebDavArchiveEntry("neriplayer-sync-v4-${"a".repeat(64)}.zst", "\"etag\"")

    @Test fun `conditional delete accepts successful and already absent objects`() {
        for (status in listOf(200, 204, 404)) {
            withFixture(status) { api, lease, requests ->
                api.deleteArchiveObject(entry, lease).getOrThrow()
                val deletion = requests.single { it.method == "DELETE" }
                assertEquals("/dav/${entry.path}", deletion.url.encodedPath)
                assertEquals(entry.etag, deletion.header("If-Match"))
                assertEquals("<https://sync.test/dav/> (<$token>)", deletion.header("If"))
            }
        }
    }

    @Test fun `authentication access lock conditions and server errors refuse deletion`() {
        for (status in listOf(401, 403, 412, 423, 500)) {
            withFixture(status) { api, lease, requests ->
                val error = api.deleteArchiveObject(entry, lease).exceptionOrNull()
                when (status) {
                    401 -> assertTrue(error is WebDavAuthException)
                    403 -> assertTrue(error is WebDavAccessDeniedException)
                    412, 423 -> assertTrue(error is WebDavContentConflictException)
                    else -> assertEquals(status, (error as WebDavApiException).statusCode)
                }
                assertEquals(1, requests.count { it.method == "DELETE" })
            }
        }
    }

    @Test fun `unowned paths collections and weak validators never send a DELETE`() {
        withFixture(204) { api, lease, requests ->
            for (invalid in listOf(entry.copy(path = "notes.bin"), entry.copy(path = entry.path + "/"),
                entry.copy(path = "sub/" + entry.path), entry.copy(etag = "W/\"weak\""), entry.copy(etag = ""))) {
                assertTrue(api.deleteArchiveObject(invalid, lease).exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(requests.none { it.method == "DELETE" })
        }
    }

    private fun withFixture(status: Int, verify: (WebDavApiClient, WebDavArchiveLease, List<Request>) -> Unit) {
        val requests = arrayListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val code = when (request.method) { "LOCK" -> 200; "UNLOCK" -> 204; else -> status }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Lock-Token", "<$token>").body(lockResponse.toResponseBody()).build()
        }.build()
        val api = WebDavApiClient("fixture", "password", client, "auth")
        api.acquireArchiveLease("https://sync.test/dav/manifest", false, {}).getOrThrow()!!.use { lease ->
            verify(api, lease, requests)
        }
        assertEquals("UNLOCK", requests.last().method)
    }

    private val lockResponse = """
        <d:prop xmlns:d="DAV:"><d:lockdiscovery><d:activelock>
        <d:locktype><d:write/></d:locktype><d:lockscope><d:exclusive/></d:lockscope>
        <d:depth>infinity</d:depth><d:timeout>Second-300</d:timeout>
        <d:locktoken><d:href>$token</d:href></d:locktoken>
        <d:lockroot><d:href>https://sync.test/dav/</d:href></d:lockroot>
        </d:activelock></d:lockdiscovery></d:prop>
    """.trimIndent()
}
