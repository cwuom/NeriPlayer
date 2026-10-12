package moe.ouom.neriplayer.api.sync.github

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubGraphQlCallTest {
    @Test
    fun `interceptor cancellation preserves the original cancellation instance`() = runBlocking {
        val cancelled = CancellationException("synthetic interceptor cancellation")
        assertFailureIdentity(cancelled, OkHttpClient.Builder().addInterceptor { throw cancelled }.build())
    }

    @Test
    fun `transport failure preserves the original IOException instance`() = runBlocking {
        val failure = IOException("synthetic network failure")
        assertFailureIdentity(failure, OkHttpClient.Builder().addInterceptor { throw failure }.build())
    }

    @Test
    fun `transport failure with an IO cause preserves the outer IOException instance`() = runBlocking {
        val failure = IOException("synthetic network failure", IOException("original IO cause"))
        assertFailureIdentity(failure, OkHttpClient.Builder().addInterceptor { throw failure }.build())
    }

    @Test
    fun `transport failure with a nested cancellation retains the outer IOException instance`() = runBlocking {
        val failure = IOException("synthetic network failure",
            IOException("original IO cause", CancellationException("nested cancellation")))
        assertFailureIdentity(failure, OkHttpClient.Builder().addInterceptor { throw failure }.build())
    }

    @Test
    fun `response body cancellation preserves its original instance and closes the body`() = runBlocking {
        val cancelled = CancellationException("synthetic body cancellation")
        val closed = AtomicBoolean()
        val body = object : ResponseBody() {
            private val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw cancelled
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() { closed.set(true) }
            }.buffer()

            override fun contentType() = null
            override fun contentLength() = 1L
            override fun source() = source
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(200).message("test")
                .protocol(Protocol.HTTP_1_1).body(body).build()
        }.build()
        try {
            assertFailureIdentity(cancelled, client)
            assertTrue("cancelled response body must be closed", closed.get())
        } finally {
            body.close()
        }
    }

    private suspend fun assertFailureIdentity(expected: Throwable, client: OkHttpClient) {
        try {
            val request = Request.Builder().url("http://127.0.0.1/graphql").build()
            val failure = runCatching {
                executeGraphQlMutation(client.newCall(request)) { it.body.string() }
            }.exceptionOrNull()
            assertSame(expected, failure)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
