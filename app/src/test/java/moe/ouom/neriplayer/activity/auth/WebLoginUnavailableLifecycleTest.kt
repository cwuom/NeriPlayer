package moe.ouom.neriplayer.activity.auth

import android.app.Application
import android.content.Context
import android.util.AndroidRuntimeException
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    shadows = [UnavailableLoginWebSettings::class, UnavailableLoginCookieManager::class]
)
class WebLoginUnavailableLifecycleTest {
    @Test
    fun `youtube login closes without touching missing provider cookies during destruction`() {
        val controller = Robolectric.buildActivity(YouTubeWebLoginActivity::class.java).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test
    fun `youtube login can pause after the provider probe fails`() {
        val controller = Robolectric.buildActivity(YouTubeWebLoginActivity::class.java).create()
        assertTrue(controller.get().isFinishing)
        controller.start().resume().pause().stop().destroy()
    }

    @Test
    fun `bilibili login can pause after the provider probe fails`() {
        val controller = Robolectric.buildActivity(BiliWebLoginActivity::class.java).create()
        assertTrue(controller.get().isFinishing)
        controller.start().resume().pause().stop().destroy()
    }
}

@Implements(WebSettings::class)
class UnavailableLoginWebSettings {
    companion object {
        @JvmStatic
        @Implementation
        fun getDefaultUserAgent(context: Context): String {
            throw AndroidRuntimeException("WebView provider unavailable for ${context.packageName}")
        }
    }
}

@Implements(CookieManager::class)
class UnavailableLoginCookieManager {
    companion object {
        @JvmStatic
        @Implementation
        fun getInstance(): CookieManager {
            throw AndroidRuntimeException("Unavailable WebView provider must not be accessed again")
        }
    }
}
