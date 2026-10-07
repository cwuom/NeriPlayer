package moe.ouom.neriplayer.platform.bilibili.api.client

import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliClientWbiSessionTest {
    @Test
    fun `signed requests reuse one nav lookup and sign with the documented mixin key`() = biliClientTest(
        route = { request -> json(PROFILE).takeIf { request.url.encodedPath == ACC_INFO_PATH } }
    ) { http, client ->
        client.getUploaderProfile(42L)
        client.getUploaderProfile(43L)

        assertEquals(1, http.requestsTo(NAV_PATH).size)
        val signed = http.requestsTo(ACC_INFO_PATH)
        assertEquals(2, signed.size)
        for (request in signed) {
            assertTrue(request.url.queryParameter("wts").orEmpty().all(Char::isDigit))
            assertEquals(expectedWbiSignature(request), request.url.queryParameter("w_rid"))
        }
    }

    @Test
    fun `short wbi keys sign with only the characters that exist`() = biliClientTest(
        route = { request ->
            when (request.url.encodedPath) {
                NAV_PATH -> json(wbiNavResponse("https://i0.hdslb.com/bfs/wbi/abc.png", "https://i0.hdslb.com/bfs/wbi/def.png"))
                ACC_INFO_PATH -> json(PROFILE)
                else -> null
            }
        }
    ) { http, client ->
        client.getUploaderProfile(42L)

        val request = http.requestsTo(ACC_INFO_PATH).single()
        assertEquals(expectedWbiSignature(request, mixinKey = "cdfabe"), request.url.queryParameter("w_rid"))
    }

    @Test
    fun `nav without sub key falls back to the web ticket with csrf`() = biliClientTest(
        cookies = mapOf("SESSDATA" to "test-session", "bili_jct" to "csrf-token"),
        route = { request ->
            when (request.url.encodedPath) {
                NAV_PATH -> json(wbiNavResponse(subUrl = ""))
                TICKET_PATH -> json(
                    """{"code":0,"data":{"ticket":"t","nav":{"img":"$DOC_WBI_IMG_URL","sub":"$DOC_WBI_SUB_URL"}}}"""
                )
                ACC_INFO_PATH -> json(PROFILE)
                else -> null
            }
        }
    ) { http, client ->
        client.getUploaderProfile(42L)

        val ticket = http.requestsTo(TICKET_PATH).single()
        assertEquals("POST", ticket.method)
        assertTrue(ticket.header("User-Agent").orEmpty().contains("Firefox/115.0"))
        assertEquals("ec02", ticket.url.queryParameter("key_id"))
        assertEquals("csrf-token", ticket.url.queryParameter("csrf"))
        val ts = requireNotNull(ticket.url.queryParameter("context[ts]"))
        assertEquals(hmacSha256Hex("XgwSnGZ1p", "ts$ts"), ticket.url.queryParameter("hexsign"))
        val signed = http.requestsTo(ACC_INFO_PATH).single()
        assertEquals(expectedWbiSignature(signed), signed.url.queryParameter("w_rid"))
    }

    @Test
    fun `missing wbi keys from nav and ticket fail the signed request`() = biliClientTest(
        route = { request ->
            when (request.url.encodedPath) {
                NAV_PATH -> json(wbiNavResponse(imgUrl = ""))
                TICKET_PATH -> json("""{"code":0,"data":{}}""")
                else -> null
            }
        }
    ) { http, client ->
        val error = assertThrows(IOException::class.java) { runBlocking { client.getUploaderProfile(42L) } }

        assertEquals("Invalid wbi mixin url: img= sub=", error.message)
        assertNull(http.requestsTo(TICKET_PATH).single().url.queryParameter("csrf"))
        assertTrue(http.requestsTo(ACC_INFO_PATH).isEmpty())
    }

    @Test
    fun `failed nav request and ticket without data surface the invalid key error`() = biliClientTest(
        route = { request ->
            when (request.url.encodedPath) {
                NAV_PATH -> BiliTestReply("""{"code":-500}""", code = 500)
                TICKET_PATH -> json("""{"code":-1}""")
                else -> null
            }
        }
    ) { http, client ->
        val error = assertThrows(IOException::class.java) { runBlocking { client.searchVideos("lofi") } }

        assertEquals("Invalid wbi mixin url: img= sub=", error.message)
        assertEquals(1, http.requestsTo(NAV_PATH).size)
    }

    @Test
    fun `anonymous requests send no cookie header when the fingerprint has no cookies`() = biliClientTest(
        cookies = emptyMap(),
        route = { request ->
            when (request.url.encodedPath) {
                FINGERPRINT_PATH -> json("""{"code":0,"data":{}}""")
                HAS_LIKE_PATH -> json("""{"code":0,"data":1}""")
                else -> null
            }
        }
    ) { http, client ->
        assertTrue(client.hasLikedRecentlyByBvid("BV1liked"))

        assertEquals(1, http.requestsTo(FINGERPRINT_PATH).size)
        assertNull(http.requestsTo(HAS_LIKE_PATH).single().header("Cookie"))
    }

    @Test
    fun `login validation requires stored sessdata and a logged in nav response`() = runTest {
        var cookies: Map<String, String> = emptyMap()
        var navReply = json("""{"code":0,"data":{"isLogin":true,"mid":42}}""")
        val http = BiliTestHttp { request -> navReply.takeIf { request.url.encodedPath == NAV_PATH } }
        val client = BiliClient({ cookies }, http.client)
        try {
            assertEquals(false, client.validateLoginSession())
            cookies = mapOf("SESSDATA" to " ")
            assertEquals(false, client.validateLoginSession())
            assertTrue(http.requests.isEmpty())

            cookies = mapOf("SESSDATA" to "session", "bili_jct" to "csrf")
            assertEquals(true, client.validateLoginSession())
            val request = http.requests.single()
            assertEquals("SESSDATA=session; bili_jct=csrf", request.header("Cookie"))
            assertEquals("https://www.bilibili.com", request.header("Referer"))

            navReply = json("""{"code":0,"data":{"isLogin":false,"mid":42}}""")
            assertEquals(false, client.validateLoginSession())
            navReply = json("""{"code":0,"data":{"isLogin":true,"mid":0}}""")
            assertEquals(false, client.validateLoginSession())
            navReply = json("""{"code":-101,"data":{"isLogin":true,"mid":42}}""")
            assertEquals(false, client.validateLoginSession())
            navReply = json("""{"code":0}""")
            assertEquals(false, client.validateLoginSession())
            navReply = BiliTestReply("""{"code":-500}""", code = 500)
            assertNull(client.validateLoginSession())
            navReply = json("not json")
            assertNull(client.validateLoginSession())
        } finally {
            http.close()
        }
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ACC_INFO_PATH = "/x/space/wbi/acc/info"
        const val TICKET_PATH = "/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket"
        const val FINGERPRINT_PATH = "/x/frontend/finger/spi"
        const val HAS_LIKE_PATH = "/x/web-interface/archive/has/like"
        const val PROFILE = """{"code":0,"data":{"mid":42,"name":"UP"}}"""
    }
}
