@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
package moe.ouom.neriplayer.core.player.prefetch

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerPrefetchCancellationTest {
    @Test fun `cancel reaches existing calls and any calls created afterwards`() {
        val factory = mock(Call.Factory::class.java)
        val first = mock(Call::class.java)
        val second = mock(Call::class.java)
        val request = Request.Builder().url("https://example.test/").build()
        `when`(factory.newCall(request)).thenReturn(first, second)
        val cancellable = PrefetchCallFactory(factory)
        cancellable.newCall(request)
        cancellable.cancel()
        cancellable.newCall(request)
        verify(first).cancel()
        verify(second).cancel()
    }

    @Test fun `cancellation interrupts blocked open and closes source on worker`() = blockedOperation(false)
    @Test fun `cancellation interrupts blocked read and closes source on worker`() = blockedOperation(true)

    private fun blockedOperation(blockRead: Boolean) = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val factory = mock(Call.Factory::class.java)
        val call = mock(Call::class.java)
        val request = Request.Builder().url("https://example.test/").build()
        `when`(factory.newCall(request)).thenReturn(call)
        doAnswer { cancelled.countDown(); null }.`when`(call).cancel()
        val calls = PrefetchCallFactory(factory)
        val source = mock(DataSource::class.java)
        val spec = mock(DataSpec::class.java)
        val block = {
            calls.newCall(request)
            entered.countDown()
            check(cancelled.await(2, TimeUnit.SECONDS)) { "HTTP request was not cancelled" }
            throw IOException("cancelled")
        }
        if (blockRead) {
            `when`(source.open(spec)).thenReturn(1024L)
            `when`(source.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer { block() }
        } else `when`(source.open(spec)).thenAnswer { block() }
        val job = launch { readServerPrefetch(source, spec, 1024L, calls) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
        withTimeout(2000) { job.cancelAndJoin() }
        assertEquals(0, cancelled.count)
        verify(source).close()
        verify(call).cancel()
    }
}
