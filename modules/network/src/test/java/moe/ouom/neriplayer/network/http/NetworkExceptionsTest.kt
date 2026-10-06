package moe.ouom.neriplayer.network.http

import java.io.IOException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkExceptionsTest {

    @Test
    fun `cancelled or refused stream resets are transient`() {
        assertTrue(StreamResetException(ErrorCode.REFUSED_STREAM).isTransientHttp2StreamReset())
        assertTrue(StreamResetException(ErrorCode.CANCEL).isTransientHttp2StreamReset())
        assertTrue(IOException("Stream Was Reset: refused_stream").isTransientHttp2StreamReset())
    }

    @Test
    fun `other resets and plain failures are not transient`() {
        assertFalse(StreamResetException(ErrorCode.PROTOCOL_ERROR).isTransientHttp2StreamReset())
        assertFalse(IOException("stream was reset: INTERNAL_ERROR").isTransientHttp2StreamReset())
        assertFalse(IOException("CANCEL").isTransientHttp2StreamReset())
        assertFalse(IOException().isTransientHttp2StreamReset())
    }

    @Test
    fun `causes and suppressed failures are searched`() {
        val wrapped = IllegalStateException("wrapper", IOException("stream was reset: CANCEL"))
        val suppressed = IOException("outer").apply {
            addSuppressed(IOException("unrelated"))
            addSuppressed(StreamResetException(ErrorCode.REFUSED_STREAM))
        }

        assertTrue(wrapped.isTransientHttp2StreamReset())
        assertTrue(suppressed.isTransientHttp2StreamReset())
    }

    @Test
    fun `cause cycles terminate without a match`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        first.addSuppressed(second)

        assertFalse(first.isTransientHttp2StreamReset())
    }
}
