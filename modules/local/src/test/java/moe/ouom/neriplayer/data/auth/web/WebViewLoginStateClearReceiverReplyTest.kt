package moe.ouom.neriplayer.data.auth.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewLoginStateClearReceiverReplyTest {
    private val cookieManager = mock(CookieManager::class.java)
    private val webStorage = mock(WebStorage::class.java)
    private val context = mock(Context::class.java).also { doReturn(PACKAGE).`when`(it).packageName }
    private val pendingResult = mock(BroadcastReceiver.PendingResult::class.java)
    private val request = mock(Intent::class.java).also { intent ->
        doReturn(ACTION_CLEAR_WEBVIEW_LOGIN_STATE).`when`(intent).action
        doReturn(REQUEST_ID).`when`(intent).getStringExtra(EXTRA_WEBVIEW_CLEAR_REQUEST_ID)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        doAnswer { invocation ->
            invocation.getArgument<ValueCallback<Boolean>>(0).onReceiveValue(true)
            null
        }.`when`(cookieManager).removeAllCookies(any())
        doAnswer { invocation ->
            invocation.getArgument<ValueCallback<Boolean>>(0).onReceiveValue(true)
            null
        }.`when`(cookieManager).removeSessionCookies(any())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `login processes confirm a successful wipe for the request and then finish the broadcast`() {
        val receiver = spy(BiliWebViewLoginStateClearReceiver())
        doReturn(pendingResult).`when`(receiver).goAsync()

        val reply = receiveClearRequest(receiver, webViewAvailable = true)

        verify(webStorage).deleteAllData()
        verify(reply).setPackage(PACKAGE)
        verify(reply).putExtra(EXTRA_WEBVIEW_CLEAR_REQUEST_ID, REQUEST_ID)
        verify(reply).putExtra(EXTRA_WEBVIEW_CLEAR_SUCCEEDED, true)
        val order = inOrder(context, pendingResult)
        order.verify(context).sendBroadcast(reply)
        order.verify(pendingResult).finish()
    }

    @Test
    fun `login processes that cannot wipe WebView state still answer with a failure`() {
        val receiver = spy(NeteaseWebViewLoginStateClearReceiver())
        doReturn(pendingResult).`when`(receiver).goAsync()

        val reply = receiveClearRequest(receiver, webViewAvailable = false)

        verify(webStorage, never()).deleteAllData()
        verify(reply).putExtra(EXTRA_WEBVIEW_CLEAR_REQUEST_ID, REQUEST_ID)
        verify(reply).putExtra(EXTRA_WEBVIEW_CLEAR_SUCCEEDED, false)
        val order = inOrder(context, pendingResult)
        order.verify(context).sendBroadcast(reply)
        order.verify(pendingResult).finish()
    }

    private fun receiveClearRequest(receiver: WebViewLoginStateClearReceiver, webViewAvailable: Boolean): Intent {
        return mockStatic(CookieManager::class.java).use { cookies ->
            if (webViewAvailable) {
                cookies.`when`<CookieManager> { CookieManager.getInstance() }.thenReturn(cookieManager)
            } else {
                cookies.`when`<CookieManager> { CookieManager.getInstance() }
                    .thenThrow(IllegalStateException("WebView provider missing"))
            }
            mockStatic(WebStorage::class.java).use { storage ->
                storage.`when`<WebStorage> { WebStorage.getInstance() }.thenReturn(webStorage)
                mockConstruction(Intent::class.java) { intent, _ ->
                    doReturn(intent).`when`(intent).setPackage(anyString())
                    doReturn(intent).`when`(intent).putExtra(anyString(), anyString())
                    doReturn(intent).`when`(intent).putExtra(anyString(), anyBoolean())
                }.use { intents ->
                    receiver.onReceive(context, request)
                    intents.constructed().single()
                }
            }
        }
    }

    private companion object {
        const val PACKAGE = "moe.ouom.neriplayer"
        const val REQUEST_ID = "request-7"
    }
}
