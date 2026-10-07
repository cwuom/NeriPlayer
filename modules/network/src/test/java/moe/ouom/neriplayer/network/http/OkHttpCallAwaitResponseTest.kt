package moe.ouom.neriplayer.network.http

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpCallAwaitResponseTest {

    @Test
    fun `responses are transformed and closed`() = runTest {
        val call = ControlledCall()
        val body = TrackingBody("payload")
        val result = async { call.awaitResponse { response -> "${response.code}:${response.body.string()}" } }
        runCurrent()

        call.respond(code = 200, body = body)

        assertEquals("200:payload", result.await())
        assertTrue(body.closed)
    }

    @Test
    fun `transform failures surface to the caller and still close the response`() = runTest {
        val call = ControlledCall()
        val body = TrackingBody("{")
        val result = async {
            runCatching { call.awaitResponse<Int> { throw IllegalStateException("malformed payload") } }
        }
        runCurrent()

        call.respond(code = 200, body = body)

        val error = result.await().exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("malformed payload", error?.message)
        assertTrue(body.closed)
    }

    @Test
    fun `transport failures surface as io exceptions`() = runTest {
        val call = ControlledCall()
        val result = async { runCatching { call.awaitResponse { response -> response.code } } }
        runCurrent()

        call.fail(IOException("timeout"))

        val error = result.await().exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("timeout", error?.message)
    }

    @Test
    fun `callbacks arriving after cancellation are dropped but responses are closed`() = runTest {
        val call = ControlledCall()
        val job = launch {
            call.awaitResponse { response ->
                check(response.code < 500) { "server error" }
                response.code
            }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        val lateSuccess = TrackingBody("late")
        val lateFailure = TrackingBody("late error")

        call.respond(code = 200, body = lateSuccess)
        call.respond(code = 503, body = lateFailure)
        call.fail(IOException("late transport failure"))
        runCurrent()

        assertTrue(job.isCancelled)
        assertTrue(call.isCanceled())
        assertTrue(lateSuccess.closed)
        assertTrue(lateFailure.closed)
    }

    private class ControlledCall : Call {
        private val request = Request.Builder().url("https://example.com/api").build()
        private var callback: Callback? = null
        private var canceled = false

        fun respond(code: Int, body: ResponseBody) {
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("status $code")
                .body(body)
                .build()
            checkNotNull(callback).onResponse(this, response)
        }

        fun fail(error: IOException) {
            checkNotNull(callback).onFailure(this, error)
        }

        override fun request(): Request = request

        override fun execute(): Response {
            throw UnsupportedOperationException("sync execution is not used")
        }

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = callback != null

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = ControlledCall()

        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()
    }

    private class TrackingBody(content: String) : ResponseBody() {
        private val buffer = Buffer().writeUtf8(content)
        var closed = false
            private set

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = buffer.size

        override fun source(): BufferedSource = buffer

        override fun close() {
            closed = true
            super.close()
        }
    }
}
