package moe.ouom.neriplayer.platform.bilibili.api.client

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliHttpErrorBoundaryTest {
    @Test
    fun `favorite business failure is not a successful empty folder`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath == "/x/v3/fav/resource/list") {
                json("""{"code":-101,"message":"login required"}""")
            } else null
        }
    ) { _, client ->
        val error = runCatching { client.getFavFolderContents(mediaId = 42L) }.exceptionOrNull()

        assertTrue("business failure must not produce an empty folder", error is IOException)
    }

    @Test
    fun `collection business failure is not a successful empty archive page`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath == "/x/polymer/web-space/seasons_archives_list") {
                json("""{"code":-352,"message":"request rejected"}""")
            } else null
        }
    ) { _, client ->
        val error = runCatching { client.getCollectionArchives(mid = 7L, seasonId = 42L) }.exceptionOrNull()

        assertTrue("business failure must not produce an empty archive page", error is IOException)
    }

    @Test
    fun `anonymous session failure does not expose the response document`() = biliClientTest(
        cookies = emptyMap(),
        route = { BiliTestReply("<html>anonymous-session diagnostic</html>", code = 412) }
    ) { _, client ->
        val error = runCatching { client.getFavFolderContents(mediaId = 42L) }.exceptionOrNull()

        assertEquals("HTTP 412", error?.message)
        assertFalse(error.toString().contains("anonymous-session diagnostic"))
    }

    @Test
    fun `risk control response keeps its status without exposing the response document`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath == "/x/v3/fav/resource/list") {
                BiliTestReply("<html><script>private diagnostics</script>Access denied</html>", code = 412)
            } else null
        }
    ) { _, client ->
        val error = runCatching { client.getFavFolderContents(mediaId = 42L) }.exceptionOrNull()

        assertTrue(error is BiliHttpStatusException)
        assertEquals(412, (error as BiliHttpStatusException).statusCode)
        assertEquals("HTTP 412", error.message)
        assertFalse(error.toString().contains("private diagnostics"))
        assertFalse(error.toString().contains("<html>"))
    }
}
