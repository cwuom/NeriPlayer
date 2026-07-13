package moe.ouom.neriplayer.core.di

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SharedOkHttpClientPolicyTest {

    @Test
    fun `shared client bounds connection setup without total call deadline`() {
        val connectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)
        val client = configureSharedOkHttpClient(
            builder = OkHttpClient.Builder(),
            connectionPool = connectionPool
        ).build()

        assertEquals(8_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertSame(connectionPool, client.connectionPool)
        assertTrue(client.retryOnConnectionFailure)
    }

    @Test
    fun `shared client keeps OkHttp protocol fallback defaults`() {
        val client = configureSharedOkHttpClient(OkHttpClient.Builder()).build()

        assertTrue(client.protocols.contains(Protocol.HTTP_2))
        assertTrue(client.protocols.contains(Protocol.HTTP_1_1))
    }
}
