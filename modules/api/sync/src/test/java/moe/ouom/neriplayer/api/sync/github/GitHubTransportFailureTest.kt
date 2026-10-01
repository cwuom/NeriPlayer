package moe.ouom.neriplayer.api.sync.github

import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.testing.SyncHttpFixture
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTransportFailureTest {
    @Test
    fun `upload rejects empty or oversized payload before requests`() = runTest {
        val fixture = SyncHttpFixture()
        for (payload in listOf(byteArrayOf(), ByteArray(12 * 1024 * 1024 + 1))) {
            assertTrue(transport(fixture.client).updateFileContent("o", "r", payload, null, "backup.json", "sync", null).isFailure)
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `invalid repository and nested SHA fields cannot be used for upload`() = runTest {
        for (body in listOf("{}", "null", "broken", """{"default_branch":{}}""", """{"default_branch":" "}""", """{"default_branch":"main"}""", """{"default_branch":"main","object":{}}""", """{"default_branch":"main","object":{"sha":[]}}""")) {
            val fixture = SyncHttpFixture(content = body.toByteArray())
            assertTrue(transport(fixture.client).getFileContent("o", "r", "backup.json", true).isFailure)
        }
    }

    @Test
    fun `explicit branch and head are retained and malformed tree stops update`() = runTest {
        val fixture = SyncHttpFixture(content = "{}".toByteArray())
        val result = transport(fixture.client).updateFileContent("o", "r", byteArrayOf(1), "expected", "backup.json", "sync", "branch")
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.requests.single().url.encodedPath.endsWith("/git/commits/expected"))
    }

    @Test
    fun `status and error bodies remain useful and bounded`() = runTest {
        for (status in listOf(401, 500)) for (body in listOf("", "broken", "{}", """{"message":"failure"}""", """{"message":" "}""")) {
            val error = transport(SyncHttpFixture(status, body.toByteArray()).client).getFileContent("o", "r", "backup.json", true).exceptionOrNull()
            if (status == 401) assertTrue(error is TokenExpiredException)
            else assertTrue(error is GitHubApiException)
        }
    }

    @Test
    fun `non force reference conflicts preserve newer remote content`() = runTest {
        for ((status, body) in listOf(409 to "{}", 422 to "reference moved", 422 to "other invalid input", 500 to "failure", 401 to "")) {
            var count = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val response = if (count++ < 4) """{"tree":{"sha":"base"},"sha":"new"}""" else body
                val fixture = SyncHttpFixture(if (count <= 4) 200 else status, response.toByteArray())
                fixture.client.newCall(chain.request()).execute()
            }.build()
            val error = transport(client).updateFileContent("o", "r", byteArrayOf(1), "head", "backup.json", "sync", "main").exceptionOrNull()
            when {
                status == 409 || (status == 422 && body.contains("reference")) -> assertTrue(error is GitHubContentConflictException)
                status == 401 -> assertTrue(error is TokenExpiredException)
                else -> assertTrue(error is GitHubApiException)
            }
        }
    }

    private fun transport(client: OkHttpClient) = GitHubRepositorySyncTransport(client, "token", "https://example.test", "expired")
}
