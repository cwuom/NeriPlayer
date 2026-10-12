package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertSame
import org.junit.Test

class WebDavArchiveReadCallTest {
    @Test fun `archive interceptor cancellation preserves the original cancellation instance`() = runBlocking {
        val cancelled = CancellationException("synthetic interceptor cancellation")
        val client = OkHttpClient.Builder().addInterceptor { throw cancelled }.build()
        try {
            val failure = runCatching {
                WebDavApiClient("test-user", "test-password", client, "auth")
                    .getArchiveFileContent("http://127.0.0.1/manifest")
            }.exceptionOrNull()
            assertSame(cancelled, failure)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `archive transport failures preserve the original exception instance`() = runBlocking {
        val failure = IOException("synthetic archive network failure")
        val client = OkHttpClient.Builder().addInterceptor { throw failure }.build()
        try {
            val result = WebDavApiClient("test-user", "test-password", client, "auth")
                .getArchiveFileContent("http://127.0.0.1/manifest")
            assertSame(failure, result.exceptionOrNull())
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun `archive transport failures with an IO cause preserve the outer exception instance`() = runBlocking {
        val failure = IOException("synthetic archive network failure", IOException("original IO cause"))
        val client = OkHttpClient.Builder().addInterceptor { throw failure }.build()
        try {
            val result = WebDavApiClient("test-user", "test-password", client, "auth")
                .getArchiveFileContent("http://127.0.0.1/manifest")
            assertSame(failure, result.exceptionOrNull())
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
