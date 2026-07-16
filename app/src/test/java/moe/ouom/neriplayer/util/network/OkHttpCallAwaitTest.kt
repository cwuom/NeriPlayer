package moe.ouom.neriplayer.util.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpCallAwaitTest {

    @Test
    fun `cancelling coroutine cancels pending call`() = runTest {
        val call = PendingCall()
        val requestJob = launch {
            call.awaitResponse { response -> response.code }
        }
        runCurrent()

        requestJob.cancel()
        runCurrent()

        assertTrue(call.isCanceled())
    }

    private class PendingCall : Call {
        private val request = Request.Builder().url("https://example.com").build()
        private val canceled = AtomicBoolean(false)
        private val executed = AtomicBoolean(false)

        override fun request(): Request = request

        override fun execute(): Response {
            throw UnsupportedOperationException("sync execution is not used")
        }

        override fun enqueue(responseCallback: Callback) {
            executed.set(true)
        }

        override fun cancel() {
            canceled.set(true)
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun isCanceled(): Boolean = canceled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = PendingCall()
    }
}
