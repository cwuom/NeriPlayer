package moe.ouom.neriplayer.platform.youtube.auth

import android.content.Context
import android.webkit.WebView
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeAuthRefreshCleanupTest {
    @Test
    fun `cancelled refresh still destroys its WebView and completes its pending load`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = manager()
            val webView = mock(WebView::class.java)
            val pending = CompletableDeferred<Boolean>()
            manager.setField("webView", webView)
            manager.setField("pendingPageLoad", pending)
            manager.setField("webViewUserAgent", "synthetic-agent")
            val caller = launch {
                try {
                    awaitCancellation()
                } finally {
                    manager.releaseWebView()
                }
            }
            runCurrent()

            caller.cancelAndJoin()

            verify(webView).stopLoading()
            verify(webView).destroy()
            assertTrue(pending.isCompleted)
            assertFalse(pending.await())
            assertNull(manager.field("webView"))
            assertNull(manager.field("pendingPageLoad"))
            assertTrue((manager.field("webViewUserAgent") as String).isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stop loading failure still destroys the WebView and clears ownership`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = manager()
            val webView = mock(WebView::class.java)
            doThrow(IllegalStateException("synthetic stopped renderer")).`when`(webView).stopLoading()
            manager.setField("webView", webView)

            manager.releaseWebView()

            verify(webView).destroy()
            assertNull(manager.field("webView"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun manager(): YouTubeAuthAutoRefreshManager {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        return YouTubeAuthAutoRefreshManager(context, { OkHttpClient() }, RefreshTestAuthStore())
    }

    private fun YouTubeAuthAutoRefreshManager.field(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun YouTubeAuthAutoRefreshManager.setField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private suspend fun YouTubeAuthAutoRefreshManager.releaseWebView(): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = javaClass.getDeclaredMethod("releaseWebView", kotlin.coroutines.Continuation::class.java)
                .apply { isAccessible = true }
            try {
                method.invoke(this, continuation)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
}
