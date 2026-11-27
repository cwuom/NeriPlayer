package moe.ouom.neriplayer.platform.youtube.api.challenge

import android.app.Application
import android.content.pm.PackageInfo
import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class YouTubeJavaScriptSandboxCompatibilityTest {

    @Test
    fun `sandbox rejects WebView versions without the feature query transaction`() {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo().apply {
            setLongVersionCode(5111_000_00L)
        })

        assertFalse(JavaScriptSandbox.isSupported())
    }

    @Test
    fun `sandbox accepts WebView starting at the compatible version`() {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo().apply {
            setLongVersionCode(5112_000_00L)
        })

        assertTrue(JavaScriptSandbox.isSupported())
    }
}
