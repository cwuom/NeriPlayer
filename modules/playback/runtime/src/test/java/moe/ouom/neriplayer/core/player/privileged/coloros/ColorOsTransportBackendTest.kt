package moe.ouom.neriplayer.core.player.privileged.coloros

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ColorOsTransportBackendTest {
    @Test fun `malformed read reply closes the session`() = runBlocking {
        val channel = FakeChannel { "garbage" }
        val error = runCatching { ColorOsTransportBackend(0, channel).read() }.exceptionOrNull()
        assertTrue(error is ColorOsConfigException)
        assertTrue(channel.closed)
    }

    @Test fun `remote permission denial stays permission denial`() = runBlocking {
        val channel = FakeChannel { "{\"uid\":2000,\"error\":\"PERMISSION_DENIED\",\"detail\":\"provider_denied\"}" }
        val error = runCatching { ColorOsTransportBackend(2000, channel).read() }.exceptionOrNull() as ColorOsConfigException
        assertEquals(ColorOsConfigStatus.PERMISSION_DENIED, error.status)
    }

    @Test fun `lost CAS reply reports unknown write outcome and closes`() = runBlocking {
        val channel = FakeChannel { throw IOException("lost") }
        val backend = ColorOsTransportBackend(0, channel)
        val row = ColorOsRusRow("1", "<x/>")
        val error = runCatching { backend.compareAndSet(row, row.copy(version = "2")) }.exceptionOrNull() as ColorOsConfigException
        assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, error.status)
        assertTrue(channel.closed)
    }

    @Test fun `absent row is distinct from omitted row`() = runBlocking {
        assertNull(ColorOsTransportBackend(0, FakeChannel { "{\"uid\":0,\"row\":null}" }).read())
        val error = runCatching { ColorOsTransportBackend(0, FakeChannel { "{\"uid\":0}" }).read() }.exceptionOrNull()
        assertTrue(error is ColorOsConfigException)
    }

    @Test fun `CAS reply from a different uid cannot confirm the write`() = runBlocking {
        val channel = FakeChannel { "{\"uid\":2000,\"count\":1}" }
        val row = ColorOsRusRow("1", "<x/>")
        val error = runCatching { ColorOsTransportBackend(0, channel).compareAndSet(row, row) }.exceptionOrNull() as ColorOsConfigException
        assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, error.status)
    }

    @Test fun `CAS timeout closes session and reports unknown`() = runBlocking {
        val released = CountDownLatch(1)
        val channel = object : ColorOsTransportChannel {
            override fun exchange(request: String): String {
                released.await(3, TimeUnit.SECONDS)
                return "{\"uid\":0,\"count\":1}"
            }
            override fun close() { released.countDown() }
        }
        val row = ColorOsRusRow("1", "<x/>")
        val error = runCatching { ColorOsTransportBackend(0, channel, 1_000).compareAndSet(row, row) }.exceptionOrNull() as ColorOsConfigException
        assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, error.status)
        assertEquals(0L, released.count)
    }

    @Test fun `oversized CAS fails before submission`() = runBlocking {
        var submitted = false
        val channel = FakeChannel { submitted = true; "{}" }
        val row = ColorOsRusRow("1", "x".repeat(ColorOsTransportProtocol.MAX_XML_BYTES + 1))
        val error = runCatching { ColorOsTransportBackend(0, channel).compareAndSet(row, row) }.exceptionOrNull() as ColorOsConfigException
        assertEquals(ColorOsConfigStatus.UNSUPPORTED, error.status)
        assertEquals(false, submitted)
    }

    @Test fun `cancellation closes a blocked transport`() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val channel = object : ColorOsTransportChannel {
            override fun exchange(request: String): String {
                started.countDown()
                released.await(3, TimeUnit.SECONDS)
                throw IOException("closed")
            }
            override fun close() { released.countDown() }
        }
        val deferred = async(Dispatchers.Default) { ColorOsTransportBackend(0, channel).read() }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        deferred.cancelAndJoin()
        assertEquals(0L, released.count)
    }

    private class FakeChannel(private val reply: () -> String) : ColorOsTransportChannel {
        var closed = false
        override fun exchange(request: String): String = reply()
        override fun close() { closed = true }
    }
}
