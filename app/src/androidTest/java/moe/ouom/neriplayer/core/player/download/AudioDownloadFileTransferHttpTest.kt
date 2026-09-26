package moe.ouom.neriplayer.core.player.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDownloadFileTransferHttpTest {
    @Test
    fun missingEtagFallsBackToOneCompleteResponse() = runBlocking {
        assertSingleResponseFallback(etag = null)
    }

    @Test
    fun weakEtagFallsBackWithoutSendingIfRange() = runBlocking {
        assertSingleResponseFallback(etag = "W/\"v1\"")
    }

    private suspend fun assertSingleResponseFallback(etag: String?) {
        withFixture(etag = etag) { fixture ->
            val result = fixture.download()
            assertArrayEquals(fixture.data, fixture.working.readBytes())
            assertEquals(fixture.data.size.toLong(), result.actualBytes)
            assertEquals(listOf("bytes=0-${CHUNK_BYTES - 1}", null), fixture.server.requests.map { it.range })
            assertTrue(fixture.server.requests.all { it.ifRange == null })
        }
    }

    @Test
    fun missingValidatorAndForcedShort206FailsAfterOneFullRequest() = runBlocking {
        withFixture(etag = null, forcePartialWithoutRange = true) { fixture ->
            val error = runCatching { fixture.download() }.exceptionOrNull()
            assertTrue("short unvalidated response must be diagnosed: $error", error is IOException)
            assertTrue(error?.message.orEmpty().contains("完整响应"))
            assertFalse(AudioDownloadTransferPolicy.shouldRetryDownloadFailureForSource(checkNotNull(error), true))
            assertEquals(listOf("bytes=0-${CHUNK_BYTES - 1}", null), fixture.server.requests.map { it.range })
            assertTrue(fixture.working.length() < fixture.data.size)
        }
    }

    @Test
    fun strongEtagContinuesValidatedChunks() = runBlocking {
        withFixture(etag = "\"v1\"") { fixture ->
            val result = fixture.download()
            assertArrayEquals(fixture.data, fixture.working.readBytes())
            assertEquals(fixture.data.size.toLong(), result.actualBytes)
            assertTrue(fixture.server.requests.size > 1)
            assertNull(fixture.server.requests.first().ifRange)
            assertTrue(fixture.server.requests.drop(1).all { it.ifRange == "\"v1\"" })
            assertTrue(fixture.server.requests.all { it.range != null })
        }
    }

    @Test
    fun previousAttemptWithoutStrongValidatorRestartsFromZero() = runBlocking {
        listOf<String?>(null, "W/\"v1\"").forEach { etag ->
            withFixture(etag = etag) { fixture ->
                fixture.seedPreviousAttempt(ByteArray(CHUNK_BYTES) { 0x7f }, etag)
                fixture.download()
                assertArrayEquals(fixture.data, fixture.working.readBytes())
                assertEquals("bytes=0-${CHUNK_BYTES - 1}", fixture.server.requests.first().range)
                assertTrue(fixture.server.requests.all { it.ifRange == null })
            }
        }
    }

    @Test
    fun previousAttemptWithStrongValidatorResumesVerifiedPrefix() = runBlocking {
        withFixture(etag = "\"v1\"") { fixture ->
            fixture.seedPreviousAttempt(fixture.data.copyOf(CHUNK_BYTES), "\"v1\"")
            fixture.download()
            assertArrayEquals(fixture.data, fixture.working.readBytes())
            assertEquals("bytes=$CHUNK_BYTES-${CHUNK_BYTES * 2 - 1}", fixture.server.requests.first().range)
            assertTrue(fixture.server.requests.all { it.ifRange == "\"v1\"" })
        }
    }

    @Test
    fun changedStrongValidatorNeverCombinesRepresentations() = runBlocking {
        withFixture(etag = "\"v1\"", change = "etag") { fixture ->
            val error = runCatching { fixture.download() }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(2, fixture.server.requests.size)
            assertFalse(fixture.working.exists())
        }
    }

    @Test
    fun changedTotalAndInvalidContentRangesRemainRejected() = runBlocking {
        listOf("total", "offset", "range-length").forEach { change ->
            withFixture(etag = "\"v1\"", change = change) { fixture ->
                val error = runCatching { fixture.download() }.exceptionOrNull()
                assertTrue("must reject $change: $error", error is IOException)
                assertEquals(2, fixture.server.requests.size)
                assertFalse(fixture.working.exists())
            }
        }
    }

    private suspend fun withFixture(
        etag: String?,
        forcePartialWithoutRange: Boolean = false,
        change: String? = null,
        block: suspend (Fixture) -> Unit
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "range-http-${UUID.randomUUID()}").apply { mkdirs() }
        val data = ByteArray(CHUNK_BYTES * 3 + 17) { index -> (index * 31 + 7).toByte() }
        val server = RangeServer(data, etag, forcePartialWithoutRange, change)
        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        try {
            block(Fixture(directory, data, server, client))
            server.failure.get()?.let { throw AssertionError("loopback HTTP fixture failed", it) }
        } finally {
            server.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            directory.deleteRecursively()
        }
    }

    private class Fixture(
        directory: File,
        val data: ByteArray,
        val server: RangeServer,
        private val client: OkHttpClient
    ) {
        val working = File(directory, "audio.part")
        private val request = Request.Builder()
            .url("http://range.googlevideo.com/videoplayback?clen=${data.size}&id=fixture")
            .build()
        private val transfer = AudioDownloadFileTransfer(
            hooks = TestHooks(server.port),
            readBufferBytes = 8_192L,
            preferredChunkSizeBytes = CHUNK_BYTES.toLong()
        )

        suspend fun download() = transfer.download(
            client = client,
            request = request,
            destFile = working,
            displayFileName = "audio.mp3",
            songId = 1L,
            songKey = "1|netease|",
            attemptId = 2L,
            operationId = "loopback-${working.parentFile?.name}"
        )

        fun seedPreviousAttempt(prefix: ByteArray, etag: String?) {
            working.writeBytes(prefix)
            assertTrue(ManagedDownloadStorage.updateWorkingResumeFingerprint(
                working,
                ManagedDownloadStorage.WorkingResumeFingerprint(
                    sourceUrl = request.url.toString(),
                    etag = etag,
                    lastModified = "Wed, 15 Jul 2026 12:00:00 GMT",
                    expectedContentLength = data.size.toLong()
                )
            ))
        }
    }

    private class TestHooks(private val port: Int) : AudioDownloadFileTransfer.Hooks {
        override fun ensureDownloadNotCancelled(
            songId: Long, songKey: String, destFile: File, batchSessionId: Long?,
            attemptId: Long?, operationId: String?
        ) = Unit

        override fun <T> withWorkingFileMutation(
            songKey: String, stage: String, batchSessionId: Long?, attemptId: Long?,
            operationId: String?, block: () -> T
        ): T = block()

        override fun deleteWorkingFile(file: File?) = ManagedDownloadStorage.deleteWorkingDownloadArtifacts(file)

        override fun storageSpaceOwnerKey(
            operationId: String?, attemptId: Long?, songKey: String, file: File
        ) = checkNotNull(operationId)

        override fun <T> executeTrackedCall(
            client: OkHttpClient, request: Request, songKey: String,
            operationId: String?, block: (Response) -> T
        ): T {
            // 只替换连接地址，Range 和条件头仍由真实传输引擎生成
            val localRequest = request.newBuilder()
                .url(request.url.newBuilder().host("127.0.0.1").port(port).build())
                .build()
            return client.newCall(localRequest).execute().use(block)
        }

        override fun newTrafficAccumulator() = TrafficByteAccumulator { }
        override fun markTransferNetworkActivity(
            operationId: String?, attemptId: Long?, songKey: String, transferGeneration: Long?
        ) = Unit
        override fun publishProgress(progress: AudioDownloadManager.DownloadProgress) = Unit
        override fun resolveVisibleDownloadFileName(requestedName: String, actualName: String) = requestedName
    }

    private data class RecordedRequest(val range: String?, val ifRange: String?)

    private class RangeServer(
        private val data: ByteArray,
        private val etag: String?,
        private val forcePartialWithoutRange: Boolean,
        private val change: String?
    ) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
        val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "range-http-fixture", isDaemon = true) {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        check(reader.readLine()?.startsWith("GET ") == true)
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val range = headers["range"]
                        val index = requests.size
                        requests += RecordedRequest(range, headers["if-range"])
                        val partial = range != null || forcePartialWithoutRange
                        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                        val end = if (partial) {
                            (range?.substringAfter('-')?.toIntOrNull() ?: (CHUNK_BYTES - 1))
                                .coerceAtMost(data.lastIndex)
                        } else data.lastIndex
                        val body = data.copyOfRange(start, end + 1)
                        val responseEtag = if (index > 0 && change == "etag") "\"v2\"" else etag
                        val rangeStart = start + if (index > 0 && change == "offset") 1 else 0
                        val rangeEnd = end + if (index > 0 && change in setOf("offset", "range-length")) 1 else 0
                        val total = data.size + if (index > 0 && change == "total") 1 else 0
                        val response = buildString {
                            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                            append("Content-Length: ${body.size}\r\n")
                            append("Content-Type: audio/mpeg\r\n")
                            append("Connection: close\r\n")
                            append("Last-Modified: Wed, 15 Jul 2026 12:00:00 GMT\r\n")
                            if (responseEtag != null) append("ETag: $responseEtag\r\n")
                            if (partial) append("Content-Range: bytes $rangeStart-$rangeEnd/$total\r\n")
                            append("\r\n")
                        }
                        try {
                            socket.getOutputStream().apply {
                                write(response.toByteArray(Charsets.ISO_8859_1))
                                write(body)
                                flush()
                            }
                        } catch (_: SocketException) {
                            // 引擎允许在校验响应头后关闭被拒绝的响应体
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!server.isClosed) failure.set(error)
            }
        }

        override fun close() {
            server.close()
            worker.join(2_000L)
            check(!worker.isAlive) { "loopback HTTP fixture did not stop" }
        }
    }

    private companion object {
        const val CHUNK_BYTES = 128 * 1024
    }
}
