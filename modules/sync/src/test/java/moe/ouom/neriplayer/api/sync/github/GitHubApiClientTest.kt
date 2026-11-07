package moe.ouom.neriplayer.api.sync.github

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.testing.SyncHttpFixture
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApiClientTest {
    private fun api(fixture: SyncHttpFixture) = GitHubApiClient("token", fixture.client, "expired", "https://example.test")

    @Test
    fun `token validation returns login or legacy unknown fallback`() = runTest {
        assertEquals("owner", api(SyncHttpFixture(content = """{"login":"owner"}""".toByteArray())).validateToken().getOrThrow())
        assertEquals("Unknown", api(SyncHttpFixture()).validateToken().getOrThrow())
    }

    @Test
    fun `token validation differentiates expiry remote error and malformed JSON`() = runTest {
        assertTrue(api(SyncHttpFixture(401)).validateToken().exceptionOrNull() is TokenExpiredException)
        assertTrue(api(SyncHttpFixture(500)).validateToken().isFailure)
        assertTrue(api(SyncHttpFixture(content = "broken".toByteArray())).validateToken().isFailure)
    }

    @Test
    fun `create and check repository preserve shared repository contract`() = runTest {
        val response = """{"id":1,"name":"neriplayer-backup","full_name":"owner/neriplayer-backup","private":true,"default_branch":"main"}"""
        val fixture = SyncHttpFixture(content = response.toByteArray())
        assertEquals("owner/neriplayer-backup", api(fixture).createRepository("neriplayer-backup").getOrThrow().fullName)
        assertEquals("main", api(fixture).checkRepository("owner", "neriplayer-backup").getOrThrow().defaultBranch)

        val request = fixture.requests.first()
        val body = Buffer()
        requireNotNull(request.body).writeTo(body)

        assertEquals("POST", request.method)
        assertEquals("/user/repos", request.url.encodedPath)
        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals(
            JsonParser.parseString(
                """{"name":"neriplayer-backup","description":"NeriPlayer backup data","private":true,"auto_init":true}"""
            ),
            JsonParser.parseString(body.readUtf8())
        )
    }

    @Test
    fun `repository errors retain status for configuration feedback`() = runTest {
        assertTrue(api(SyncHttpFixture(401)).checkRepository("o", "r").exceptionOrNull() is TokenExpiredException)
        for (body in listOf("", "error")) {
            val client = api(SyncHttpFixture(404, body.toByteArray()))
            assertEquals(404, (client.checkRepository("o", "r").exceptionOrNull() as GitHubApiException).statusCode)
            assertTrue(client.createRepository("r").isFailure)
        }
        val invalid = api(SyncHttpFixture(content = "broken".toByteArray()))
        assertTrue(invalid.checkRepository("o", "r").isFailure)
        assertTrue(invalid.createRepository("r").isFailure)
    }

    @Test
    fun `all suspended API entrypoints propagate cancellation`() = runTest {
        val client = GitHubApiClient("token", OkHttpClient.Builder().addInterceptor { throw CancellationException("cancelled") }.build(), "expired")
        val operations: List<suspend () -> Any> = listOf(
            { client.validateToken() }, { client.createRepository("r") }, { client.checkRepository("o", "r") },
            { client.getFileContent("o", "r", "backup.json") }, { client.getFileContentStrict("o", "r", "backup.json") },
            { client.updateFileContent("o", "r", byteArrayOf(1), path = "backup.json") }
        )
        for (operation in operations) {
            try { operation(); throw AssertionError("cancellation was swallowed") }
            catch (_: CancellationException) { }
        }
    }

    @Test
    fun `strict and legacy readers preserve raw success`() = runTest {
        val fixture = SyncHttpFixture(content = """{"default_branch":"main","object":{"sha":"head"}}""".toByteArray())
        val client = api(fixture)
        assertTrue(client.getFileContentStrict("o", "r", "backup.json").isSuccess)
        assertTrue(client.getFileContent("o", "r", "backup.json").isSuccess)
    }
}
