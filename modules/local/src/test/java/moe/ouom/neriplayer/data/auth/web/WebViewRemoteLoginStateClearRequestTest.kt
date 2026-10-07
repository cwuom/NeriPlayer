package moe.ouom.neriplayer.data.auth.web

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.MockedStatic
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewRemoteLoginStateClearRequestTest {
    private val cookieManager = mock(CookieManager::class.java)
    private val webStorage = mock(WebStorage::class.java)
    private val context = mock(Context::class.java).also { context ->
        doReturn(context).`when`(context).applicationContext
        doReturn(PACKAGE).`when`(context).packageName
    }
    private val requestIds = mutableListOf<String>()
    private val requestedReceivers = mutableListOf<String>()
    private val repliesPerRequest = ArrayDeque<List<Intent>>()
    private var replyReceiver: BroadcastReceiver? = null

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
        doAnswer { invocation ->
            replyReceiver = invocation.getArgument(0)
            null
        }.`when`(context).registerReceiver(any(), any(), any(), any())
        doAnswer {
            repliesPerRequest.removeFirstOrNull()?.forEach { reply -> replyReceiver!!.onReceive(context, reply) }
            null
        }.`when`(context).sendBroadcast(any())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `remote clears ask every login process and report the ones that never confirmed`() = runTest {
        repliesPerRequest += listOf(
            reply(receiver = NETEASE, succeeded = true, requestId = "stale-request"),
            reply(receiver = NETEASE, succeeded = true)
        )
        repliesPerRequest += listOf(
            reply(receiver = null, succeeded = true),
            reply(receiver = BILI, succeeded = false)
        )

        withBroadcasts { log ->
            clearAllWebViewLoginState(context)

            log.verify { Log.w(anyString(), eq("Remote WebView state clear failed: $BILI"), isNull()) }
            log.verify({ Log.w(anyString(), eq("Remote WebView state clear failed: $NETEASE"), isNull()) }, never())
            log.verify { Log.w(anyString(), eq("Timed out clearing remote WebView state: $YOUTUBE"), isNull()) }
        }

        assertEquals(listOf(NETEASE, BILI, YOUTUBE), requestedReceivers)
        assertEquals(3, requestIds.size)
        assertEquals(1, requestIds.distinct().size)
        verify(context).unregisterReceiver(replyReceiver)
        verify(webStorage).deleteAllData()
    }

    private fun reply(receiver: String?, succeeded: Boolean, requestId: String? = null): Intent {
        val reply = mock(Intent::class.java)
        doAnswer { requestId ?: requestIds.last() }.`when`(reply).getStringExtra(EXTRA_WEBVIEW_CLEAR_REQUEST_ID)
        doReturn(receiver).`when`(reply).getStringExtra(EXTRA_WEBVIEW_CLEAR_RECEIVER)
        doReturn(succeeded).`when`(reply).getBooleanExtra(EXTRA_WEBVIEW_CLEAR_SUCCEEDED, false)
        return reply
    }

    private suspend fun withBroadcasts(block: suspend (MockedStatic<Log>) -> Unit) {
        mockStatic(CookieManager::class.java).use { cookies ->
            cookies.`when`<CookieManager> { CookieManager.getInstance() }.thenReturn(cookieManager)
            mockStatic(WebStorage::class.java).use { storage ->
                storage.`when`<WebStorage> { WebStorage.getInstance() }.thenReturn(webStorage)
                mockConstruction(ComponentName::class.java) { component, construction ->
                    doReturn(construction.arguments()[0]).`when`(component).packageName
                    doReturn(construction.arguments()[1]).`when`(component).className
                }.use {
                    mockConstruction(Intent::class.java) { intent, _ ->
                        doReturn(intent).`when`(intent).addFlags(anyInt())
                        doAnswer { invocation ->
                            requestedReceivers += invocation.getArgument<ComponentName>(0).className
                            intent
                        }.`when`(intent).setComponent(any())
                        doAnswer { invocation ->
                            requestIds += invocation.getArgument<String>(1)
                            intent
                        }.`when`(intent).putExtra(eq(EXTRA_WEBVIEW_CLEAR_REQUEST_ID), anyString())
                    }.use {
                        mockStatic(Log::class.java).use { log -> block(log) }
                    }
                }
            }
        }
    }

    private companion object {
        const val PACKAGE = "moe.ouom.neriplayer"
        val NETEASE: String = NeteaseWebViewLoginStateClearReceiver::class.java.name
        val BILI: String = BiliWebViewLoginStateClearReceiver::class.java.name
        val YOUTUBE: String = YouTubeWebViewLoginStateClearReceiver::class.java.name
    }
}
