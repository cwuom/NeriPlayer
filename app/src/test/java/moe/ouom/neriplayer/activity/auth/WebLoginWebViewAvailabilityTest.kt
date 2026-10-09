package moe.ouom.neriplayer.activity.auth

import android.util.AndroidRuntimeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginWebViewAvailabilityTest {

    @Test
    fun `webview provider probe reports a missing provider as unavailable`() {
        assertTrue(isWebViewProviderAvailable {})
        assertFalse(isWebViewProviderAvailable { throw AndroidRuntimeException("No WebView installed") })
    }
}
