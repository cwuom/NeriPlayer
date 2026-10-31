@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicFailureKind
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicResourceInterceptor
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicCredentials
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.io.IOException

class ServerRangeResponseTest {
    private val profile = SubsonicProfile(
        "9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Test", "https://example.test/", "demo"
    )
    private val url = ServerSongRef(profile.id, "song").resourceUrl()

    @Test fun `exact EOF reaches Media3 through the resource interceptor`() {
        val source = source(416)
        try {
            assertEquals(0L, source.open(spec(position = 100)))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        } finally { source.close() }
    }

    @Test fun `exact EOF with explicit length does not become a premature disconnect`() {
        val source = source(416)
        try {
            assertEquals(32L, source.open(spec(position = 100, length = 32)))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        } finally { source.close() }
    }

    @Test fun `a truly invalid offset retains the range signal and excludes transport secrets`() {
        val source = source(416)
        val error = assertThrows(IOException::class.java) { source.open(spec(position = 101)) }
        assertTrue(DataSourceException.isCausedByPositionOutOfRange(error))
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            (error as DataSourceException).reason)
        assertNull(SubsonicException.find(error))
        assertNull(error.cause)
    }

    @Test fun `416 keeps opaque request and content range even for a JSON error body`() {
        val response = intercepted(416)
        response.use {
            assertEquals(url, it.request.url.toString())
            assertEquals("bytes */100", it.header("Content-Range"))
            assertEquals("{\"error\":\"range\"}", it.body.string())
        }
    }

    @Test fun `authentication failures retain the typed server error`() {
        val source = source(401)
        val error = assertThrows(IOException::class.java) { source.open(spec(position = 0)) }
        assertEquals(SubsonicFailureKind.AUTHENTICATION, SubsonicException.find(error)?.kind)
    }

    @Test fun `premature EOF on a successful response still reports a failed connection`() {
        val delegate = mock(HttpDataSource::class.java)
        val spec = spec(position = 0)
        `when`(delegate.open(spec)).thenReturn(100L)
        `when`(delegate.responseCode).thenReturn(200)
        `when`(delegate.read(any(ByteArray::class.java), anyInt(), anyInt()))
            .thenReturn(C.RESULT_END_OF_INPUT)
        val source = ServerAwareHttpDataSource(delegate)
        source.open(spec)
        val error = assertThrows(IOException::class.java) { source.read(ByteArray(8), 0, 8) }
        assertEquals(SubsonicFailureKind.NETWORK, SubsonicException.find(error)?.kind)
        source.close()
    }

    private fun spec(position: Long, length: Long = C.LENGTH_UNSET.toLong()): DataSpec {
        val uri = mock(Uri::class.java)
        `when`(uri.host).thenReturn(ServerSongRef.RESOURCE_HOST)
        `when`(uri.scheme).thenReturn("https")
        `when`(uri.toString()).thenReturn(url)
        return DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()
    }

    private fun source(code: Int): ServerAwareHttpDataSource {
        val calls = mock(Call.Factory::class.java)
        `when`(calls.newCall(anyRequest())).thenAnswer { callInvocation ->
            val request = callInvocation.getArgument<Request>(0)
            val call = mock(Call::class.java)
            val callbackPlaceholder = mock(Callback::class.java)
            doAnswer { invocation ->
                val callback = invocation.getArgument<Callback>(0)
                try { callback.onResponse(call, intercepted(code, request)) }
                catch (error: IOException) { callback.onFailure(call, error) }
                null
            }.`when`(call).enqueue(any(Callback::class.java) ?: callbackPlaceholder)
            call
        }
        return ServerAwareHttpDataSource(ResumableChunkedHttpDataSource(
            OkHttpDataSource.Factory(calls), { it }
        ))
    }

    private fun intercepted(code: Int, request: Request = Request.Builder().url(url).build()): Response {
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.credentials(profile.id)).thenReturn(SubsonicCredentials(profile, "test-password"))
        val chain = mock(Interceptor.Chain::class.java)
        `when`(chain.request()).thenReturn(request)
        `when`(chain.proceed(anyRequest())).thenAnswer { invocation ->
            Response.Builder().request(invocation.getArgument(0)).protocol(Protocol.HTTP_1_1)
                .code(code).message("Test").header("Content-Range", "bytes */100")
                .header("Content-Type", "application/json")
                .body("{\"error\":\"range\"}".toResponseBody()).build()
        }
        return SubsonicResourceInterceptor { accounts }.intercept(chain)
    }

    private fun anyRequest(): Request = any(Request::class.java) ?: Request.Builder().url(url).build()
}
