package moe.ouom.neriplayer.data.sync.github

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Base64

class GitHubRepositorySyncTransportTest {

    @Test
    fun `reads repository raw backup file`() = runBlocking {
        val payload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0xFF.toByte(), 0x42)
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"head-sha"}}"""),
                StubResponse.binary(200, payload)
            )
        )

        val result = newTransport(script)
            .getFileContent("owner", "repo", "backup-raw.bin", strict = true)
            .getOrThrow()

        assertArrayEquals(payload, result.first)
        assertEquals("head-sha", result.second)
        assertEquals(3, script.requests.size)
        assertEquals("/repos/owner/repo", script.requests[0].url.encodedPath)
        assertEquals(
            "/repos/owner/repo/contents/backup-raw.bin",
            script.requests[2].url.encodedPath
        )
        assertEquals("head-sha", script.requests[2].url.queryParameter("ref"))
        assertEquals("application/vnd.github.raw", script.requests[2].headers["Accept"])
    }

    @Test
    fun `reports missing raw backup without release fallback`() = runBlocking {
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"head-sha"}}"""),
                StubResponse.json(404, "{}")
            )
        )

        val result = newTransport(script)
            .getFileContent("owner", "repo", "backup-raw.bin", strict = true)

        assertTrue(result.exceptionOrNull() is GitHubFileNotFoundException)
        assertEquals(3, script.requests.size)
        assertEquals(
            "/repos/owner/repo/contents/backup-raw.bin",
            script.requests.last().url.encodedPath
        )
        assertTrue(script.requests.none { it.url.encodedPath.contains("/releases") })
    }

    @Test
    fun `uploads over one MiB as a repository binary blob`() = runBlocking {
        val payload = ByteArray(1024 * 1024 + 1) { index -> (index % 251).toByte() }.apply {
            this[0] = 0x1F.toByte()
            this[1] = 0x8B.toByte()
            this[2] = 0x08.toByte()
        }
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"parent-sha"}}"""),
                StubResponse.json(200, """{"tree":{"sha":"base-tree"}}"""),
                StubResponse.json(201, """{"sha":"binary-blob"}"""),
                StubResponse.json(201, """{"sha":"updated-tree"}"""),
                StubResponse.json(201, """{"sha":"binary-commit"}"""),
                StubResponse.json(200, """{"object":{"sha":"binary-commit"}}""")
            )
        )

        val newHead = newTransport(script)
            .updateFileContent(
                owner = "owner",
                repo = "repo",
                content = payload,
                remoteHead = "",
                path = "backup-raw.bin",
                message = "sync",
                branch = null
            )
            .getOrThrow()

        assertTrue(payload.size > 1024 * 1024)
        assertEquals("binary-commit", newHead)
        assertEquals(7, script.requests.size)

        val blobRequest = script.requests[3]
        assertEquals("POST", blobRequest.method)
        assertEquals("/repos/owner/repo/git/blobs", blobRequest.url.encodedPath)
        val blobBody = JSONObject(String(blobRequest.body, StandardCharsets.UTF_8))
        assertEquals("base64", blobBody.getString("encoding"))
        assertArrayEquals(payload, Base64.getDecoder().decode(blobBody.getString("content")))

        val treeRequest = script.requests[4]
        val treeBody = JSONObject(String(treeRequest.body, StandardCharsets.UTF_8))
        val treeEntry = treeBody.getJSONArray("tree").getJSONObject(0)
        assertEquals("backup-raw.bin", treeEntry.getString("path"))
        assertEquals("binary-blob", treeEntry.getString("sha"))
        assertEquals("blob", treeEntry.getString("type"))

        val commitRequest = script.requests[5]
        val commitBody = JSONObject(String(commitRequest.body, StandardCharsets.UTF_8))
        assertEquals("sync", commitBody.getString("message"))
        assertEquals("updated-tree", commitBody.getString("tree"))

        val refRequest = script.requests[6]
        assertEquals("PATCH", refRequest.method)
        val refBody = String(refRequest.body, StandardCharsets.UTF_8)
        assertTrue(refBody.contains("\"force\":false"))
        assertTrue(refBody.contains("\"sha\":\"binary-commit\""))
        assertTrue(script.requests.none { it.url.encodedPath.contains("/releases") })
    }

    @Test
    fun `does not move branch when binary blob creation fails`() = runBlocking {
        val payload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x01)
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"parent-sha"}}"""),
                StubResponse.json(200, """{"tree":{"sha":"base-tree"}}"""),
                StubResponse.json(500, """{"message":"temporary failure"}""")
            )
        )

        val result = newTransport(script).updateFileContent(
            owner = "owner",
            repo = "repo",
            content = payload,
            remoteHead = "",
            path = "backup-raw.bin",
            message = "sync",
            branch = null
        )

        assertTrue(result.isFailure)
        assertEquals(4, script.requests.size)
        assertTrue(script.requests.none { it.method == "PATCH" })
    }

    private fun newTransport(script: ScriptedInterceptor): GitHubRepositorySyncTransport {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val client = OkHttpClient.Builder().addInterceptor(script).build()
        return GitHubRepositorySyncTransport(
            context = context,
            client = client,
            token = "token",
            apiBase = "https://api.example.test"
        )
    }

    private data class CapturedRequest(
        val method: String,
        val url: HttpUrl,
        val headers: Headers,
        val body: ByteArray
    )

    private inner class ScriptedInterceptor(responses: List<StubResponse>) : Interceptor {
        private val responses = ArrayDeque(responses)
        val requests = mutableListOf<CapturedRequest>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += CapturedRequest(
                method = request.method,
                url = request.url,
                headers = request.headers,
                body = readBodyBytes(request)
            )
            val response = check(responses.isNotEmpty()) { "Unexpected request: ${request.url}" }
            return responses.removeFirst().toResponse(request)
        }
    }

    private data class StubResponse(
        val code: Int,
        val body: ByteArray,
        val contentType: String
    ) {
        fun toResponse(request: Request): Response {
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("stub")
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }

        companion object {
            fun json(code: Int, body: String): StubResponse = StubResponse(
                code = code,
                body = body.toByteArray(StandardCharsets.UTF_8),
                contentType = "application/json; charset=utf-8"
            )

            fun binary(code: Int, body: ByteArray): StubResponse = StubResponse(
                code = code,
                body = body,
                contentType = "application/octet-stream"
            )
        }
    }

    private fun readBodyBytes(request: Request): ByteArray {
        val requestBody = request.body ?: return ByteArray(0)
        return Buffer().use { buffer ->
            requestBody.writeTo(buffer)
            buffer.readByteArray()
        }
    }
}
