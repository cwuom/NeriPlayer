package moe.ouom.neriplayer.util.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.AndroidRuntimeException
import moe.ouom.neriplayer.activity.auth.isWebViewProviderAvailable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class ExternalActivityLauncherTest {

    @Test
    fun `started activities report success`() {
        val context = mock(Context::class.java)
        val intent = mock(Intent::class.java)

        assertTrue(context.tryStartActivity(intent))
        verify(context).startActivity(intent)
    }

    @Test
    fun `missing handlers are reported instead of crashing`() {
        val context = mock(Context::class.java)
        doThrow(ActivityNotFoundException("no browser")).`when`(context).startActivity(any())

        assertFalse(context.tryStartActivity(mock(Intent::class.java)))
    }

    @Test
    fun `blocked handlers are reported instead of crashing`() {
        val context = mock(Context::class.java)
        doThrow(SecurityException("work profile policy")).`when`(context).startActivity(any())

        assertFalse(context.tryStartActivity(mock(Intent::class.java)))
    }

    @Test
    fun `webview provider probe reports a missing provider as unavailable`() {
        assertTrue(isWebViewProviderAvailable {})
        assertFalse(isWebViewProviderAvailable { throw AndroidRuntimeException("No WebView installed") })
    }
}
