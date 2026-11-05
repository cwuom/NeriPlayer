package moe.ouom.neriplayer.platform.subsonic.api

import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicCredentials
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SubsonicCoverResourceTest {
    private val source = SubsonicProfile("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Source",
        "https://example.test:4533/music/", "fixture-user")
    private val local = source.copy(id = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc792")
    private val cover = SubsonicCoverReference.create(source, "cover / 中文")
    private val reference = requireNotNull(SubsonicCoverReference.parse(cover.toHttpUrl()))
    private val accounts = mock(SubsonicAccounts::class.java).also {
        `when`(it.coverCredentials(reference)).thenReturn(SubsonicCredentials(local, "fixture-password"))
        `when`(it.credentials(source.id)).thenReturn(SubsonicCredentials(source, "fixture-password"))
    }

    private fun client(status: Int = 200, inspect: (Request) -> Unit) = OkHttpClient.Builder()
        .addInterceptor(SubsonicResourceInterceptor { accounts })
        .addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body("cover-bytes".toResponseBody("image/jpeg".toMediaType())).build()
        }.build()

    @Test fun `a synchronized real cover uses local credentials only during transport`() {
        var calls = 0
        val client = client { request ->
            calls++
            assertEquals("/music/rest/getCoverArt.view", request.url.encodedPath)
            assertEquals(4533, request.url.port)
            assertEquals("cover / 中文", request.url.queryParameter("id"))
            assertEquals("600", request.url.queryParameter("size"))
            assertEquals(local.username, request.url.queryParameter("u"))
            assertNotNull(request.url.queryParameter("t"))
            assertNotNull(request.url.queryParameter("s"))
            assertNull(request.url.fragment)
            for (header in listOf("Authorization", "Cookie", "Referer", "Origin")) assertNull(request.header(header))
        }
        val original = Request.Builder().url(cover).header("Authorization", "fixture-other-token")
            .header("Cookie", "fixture-cookie").header("Referer", "https://other.test/")
            .header("Origin", "https://other.test").build()
        client.newCall(original).execute().use { response ->
            assertEquals(cover, response.request.url.toString())
            assertEquals(setOf("id", "size"), response.request.url.queryParameterNames)
            assertFalse(response.request.url.toString().contains(local.username))
            assertEquals("cover-bytes", response.body.string())
        }
        assertEquals(1, calls)
    }

    @Test fun `legacy opaque cover references continue to resolve to their original local account`() {
        val legacy = ServerSongRef(source.id, "song").resourceUrl("cover", "cover / 中文")
        client { request ->
            assertEquals("/music/rest/getCoverArt.view", request.url.encodedPath)
            assertEquals("cover / 中文", request.url.queryParameter("id"))
            assertEquals(source.username, request.url.queryParameter("u"))
        }.newCall(Request.Builder().url(legacy).build()).execute().use { response ->
            assertEquals(legacy, response.request.url.toString())
            assertEquals("cover-bytes", response.body.string())
        }
    }

    @Test fun `unmarked server urls and ordinary external images never acquire server authentication`() {
        for (url in listOf(cover.toHttpUrl().newBuilder().fragment(null).build().toString(),
            "https://example.test:4533/music/image.jpg", "https://external.test/cover.jpg")) {
            client { request ->
                assertEquals(url, request.url.toString())
                for (key in listOf("u", "p", "t", "s", "apiKey")) assertNull(request.url.queryParameter(key))
            }.newCall(Request.Builder().url(url).build()).execute().close()
        }
    }

    @Test fun `malformed marked urls fail before transport instead of falling through with credentials`() {
        for (url in listOf(cover.replace("getCoverArt.view", "stream.view"),
            cover.replace("size=600", "size=300"), cover.replace("#neri-cover=v1:", "&u=other#neri-cover=v1:"),
            cover.substringBefore('#') + "#neri-cover=v2:unsupported")) {
            val error = assertThrows(SubsonicException::class.java) {
                client { error("Malformed source must not reach transport") }
                    .newCall(Request.Builder().url(url).build()).execute().close()
            }
            assertNull(error.cause)
            assertFalse(error.toString().contains("fixture-password"))
        }
    }

    @Test fun `a missing matching account produces a safe login error without any network request`() {
        `when`(accounts.coverCredentials(reference)).thenThrow(IllegalStateException("fixture private diagnostic"))
        val error = assertThrows(SubsonicException::class.java) {
            client { error("Missing account must stop before transport") }
                .newCall(Request.Builder().url(cover).build()).execute().close()
        }
        assertEquals(SubsonicFailureKind.ACCOUNT_UNAVAILABLE, error.kind)
        assertNull(error.cause)
        assertFalse(error.toString().contains("fixture private diagnostic"))
    }

    @Test fun `HTTP cover errors never expose authenticated response requests`() {
        val error = assertThrows(SubsonicException::class.java) {
            client(403) { assertNotNull(it.url.queryParameter("t")) }
                .newCall(Request.Builder().url(cover).build()).execute().close()
        }
        assertEquals(SubsonicFailureKind.FORBIDDEN, error.kind)
        assertNull(error.cause)
        assertFalse(error.toString().contains(source.username))
        assertFalse(error.toString().contains("example.test"))
    }
}
