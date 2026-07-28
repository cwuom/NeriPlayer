package moe.ouom.neriplayer.data.sync.github

import android.content.Context
import com.google.gson.Gson
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

class GitHubReleaseSyncTransportTest {

    @Test
    fun `downloads raw release asset described by manifest`() = runBlocking {
        val payload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0xFF.toByte(), 0x42)
        val manifest = GitHubReleaseSyncManifest.create(
            assetId = 42L,
            assetName = "neriplayer-sync-v1-test.bin",
            content = payload
        )
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"head-sha"}}"""),
                StubResponse.json(200, Gson().toJson(manifest)),
                StubResponse.binary(200, payload)
            )
        )

        val result = newTransport(script)
            .getFileContent("owner", "repo", "backup.bin", strict = true)
            .getOrThrow()

        assertArrayEquals(payload, result.first)
        assertEquals("head-sha", result.second)
        assertEquals(4, script.requests.size)
        assertEquals("/repos/owner/repo", script.requests[0].url.encodedPath)
        assertEquals(
            "application/vnd.github.raw",
            script.requests[2].headers["Accept"]
        )
        assertEquals(
            "application/octet-stream",
            script.requests[3].headers["Accept"]
        )
    }

    @Test
    fun `uploads over one MiB as a raw release asset`() = runBlocking {
        val payload = ByteArray(1024 * 1024 + 1) { index -> (index % 251).toByte() }.apply {
            this[0] = 0x1F.toByte()
            this[1] = 0x8B.toByte()
            this[2] = 0x08.toByte()
        }
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"parent-sha"}}"""),
                StubResponse.json(404, "{}"),
                StubResponse.json(404, "{}"),
                StubResponse.json(
                    201,
                    """
                    {
                      "id":7,
                      "upload_url":"https://uploads.example.test/repos/owner/repo/releases/7/assets{?name,label}"
                    }
                    """.trimIndent()
                ),
                StubResponse.json(
                    201,
                    """{"id":42,"name":"remote.bin","size":${payload.size}}"""
                ),
                StubResponse.json(200, """{"tree":{"sha":"base-tree"}}"""),
                StubResponse.json(201, """{"sha":"manifest-blob"}"""),
                StubResponse.json(201, """{"sha":"updated-tree"}"""),
                StubResponse.json(201, """{"sha":"manifest-commit"}"""),
                StubResponse.json(200, """{"object":{"sha":"manifest-commit"}}""")
            )
        )

        val newHead = newTransport(script)
            .updateFileContent(
                owner = "owner",
                repo = "repo",
                content = payload,
                remoteHead = "",
                path = "backup.bin",
                message = "sync",
                branch = null
            )
            .getOrThrow()

        assertTrue(payload.size > 1024 * 1024)
        assertEquals("manifest-commit", newHead)
        assertEquals(11, script.requests.size)

        val assetRequest = script.requests[5]
        assertEquals("POST", assetRequest.method)
        assertEquals("uploads.example.test", assetRequest.url.host)
        assertEquals("application/octet-stream", assetRequest.bodyContentType)
        assertTrue(assetRequest.url.queryParameter("name").orEmpty().endsWith(".bin"))
        assertArrayEquals(payload, assetRequest.body)

        val manifestRequest = script.requests[7]
        val manifestBody = String(manifestRequest.body, StandardCharsets.UTF_8)
        assertTrue(manifestBody.contains("formatVersion"))
        assertFalse(manifestBody.contains("H4sI"))

        val refRequest = script.requests[10]
        assertEquals("PATCH", refRequest.method)
        val refBody = String(refRequest.body, StandardCharsets.UTF_8)
        assertTrue(refBody.contains("\"force\":false"))
        assertTrue(refBody.contains("\"sha\":\"manifest-commit\""))
    }

    @Test
    fun `deletes superseded asset after manifest publication`() = runBlocking {
        val previousPayload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)
        val previousManifest = GitHubReleaseSyncManifest.create(
            assetId = 41L,
            assetName = "previous.bin",
            content = previousPayload
        )
        val payload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x01)
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"parent-sha"}}"""),
                StubResponse.json(200, Gson().toJson(previousManifest)),
                StubResponse.json(
                    200,
                    """
                    {
                      "id":7,
                      "upload_url":"https://uploads.example.test/repos/owner/repo/releases/7/assets{?name,label}"
                    }
                    """.trimIndent()
                ),
                StubResponse.json(201, """{"id":42,"name":"remote.bin","size":${payload.size}}"""),
                StubResponse.json(200, """{"tree":{"sha":"base-tree"}}"""),
                StubResponse.json(201, """{"sha":"manifest-blob"}"""),
                StubResponse.json(201, """{"sha":"updated-tree"}"""),
                StubResponse.json(201, """{"sha":"manifest-commit"}"""),
                StubResponse.json(200, """{"object":{"sha":"manifest-commit"}}"""),
                StubResponse.json(204, "")
            )
        )

        val newHead = newTransport(script)
            .updateFileContent(
                owner = "owner",
                repo = "repo",
                content = payload,
                remoteHead = "",
                path = "backup.bin",
                message = "sync",
                branch = null
            )
            .getOrThrow()

        assertEquals("manifest-commit", newHead)
        assertEquals(11, script.requests.size)
        val deleteRequest = script.requests.last()
        assertEquals("DELETE", deleteRequest.method)
        assertEquals(
            "/repos/owner/repo/releases/assets/41",
            deleteRequest.url.encodedPath
        )
    }

    @Test
    fun `deletes uploaded asset when manifest publication fails`() = runBlocking {
        val payload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"parent-sha"}}"""),
                StubResponse.json(404, "{}"),
                StubResponse.json(
                    200,
                    """
                    {
                      "id":7,
                      "upload_url":"https://uploads.example.test/repos/owner/repo/releases/7/assets{?name,label}"
                    }
                    """.trimIndent()
                ),
                StubResponse.json(201, """{"id":42,"name":"remote.bin","size":${payload.size}}"""),
                StubResponse.json(500, """{"message":"temporary failure"}"""),
                StubResponse.json(204, "")
            )
        )

        val result = newTransport(script).updateFileContent(
            owner = "owner",
            repo = "repo",
            content = payload,
            remoteHead = "",
            path = "backup.bin",
            message = "sync",
            branch = null
        )

        assertTrue(result.isFailure)
        assertEquals(7, script.requests.size)
        val deleteRequest = script.requests.last()
        assertEquals("DELETE", deleteRequest.method)
        assertEquals(
            "/repos/owner/repo/releases/assets/42",
            deleteRequest.url.encodedPath
        )
    }

    private fun newTransport(script: ScriptedInterceptor): GitHubReleaseSyncTransport {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val client = OkHttpClient.Builder().addInterceptor(script).build()
        return GitHubReleaseSyncTransport(
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
        val bodyContentType: String?,
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
                bodyContentType = request.body?.contentType()?.toString(),
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
