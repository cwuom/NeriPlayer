package moe.ouom.neriplayer.platform.youtube.auth

import android.app.Application
import android.content.Context
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class YouTubeAuthRefreshLifecycleTest {
    @Test
    fun `logout waits for refresh destruction before clearing cookies without cancelling its caller`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            withManager { manager, store ->
                val view = mock(WebView::class.java)
                val loading = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                doAnswer { loading.complete(Unit); null }.`when`(view).loadUrl(anyString())
                doAnswer { events += "destroy"; null }.`when`(view).destroy()
                manager.setWebView(view)
                val callerContinued = CompletableDeferred<Unit>()
                val caller = launch {
                    val result = manager.refreshIfNeeded("synthetic logout", force = true)
                    assertFalse(result.refreshed)
                    callerContinued.complete(Unit)
                }
                try {
                    loading.await()
                    store.replaceAuth(YouTubeAuthBundle())

                    manager.cancelAndAwaitRefresh()
                    events += "clearCookies"
                    caller.join()

                    assertTrue("The caller must continue after its owned refresh is stopped", callerContinued.isCompleted)
                    assertFalse(caller.isCancelled)
                    assertEquals(listOf("destroy", "clearCookies"), events)
                } finally {
                    caller.cancelAndJoin()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a WebView whose configuration fails is still destroyed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            withManager { manager, _ ->
                mockConstruction(WebView::class.java) { view, _ ->
                    `when`(view.settings).thenThrow(IllegalStateException("synthetic configuration failure"))
                }.use { views ->
                    val result = manager.refreshIfNeeded("synthetic configuration failure", force = true)

                    assertFalse(result.refreshed)
                    assertEquals("webview_unavailable", result.reason)
                    verify(views.constructed().single()).destroy()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun withManager(block: suspend (YouTubeAuthAutoRefreshManager, RefreshTestAuthStore) -> Unit) {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }.build()
        val store = RefreshTestAuthStore(YouTubeAuthBundle(
            cookies = mapOf("__Secure-1PSID" to "synthetic-session", "SAPISID" to "synthetic-binding"),
            savedAt = 1L
        ))
        val enabled = YouTubeFeatureGate.isEnabled()
        YouTubeFeatureGate.update(true)
        try {
            block(YouTubeAuthAutoRefreshManager(context, { client }, store), store)
        } finally {
            YouTubeFeatureGate.update(enabled)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun YouTubeAuthAutoRefreshManager.setWebView(view: WebView) {
        javaClass.getDeclaredField("webView").apply { isAccessible = true }.set(this, view)
    }

}
