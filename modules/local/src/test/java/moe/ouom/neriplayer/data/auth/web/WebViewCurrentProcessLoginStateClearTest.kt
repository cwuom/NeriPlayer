package moe.ouom.neriplayer.data.auth.web

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
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewCurrentProcessLoginStateClearTest {
    private val cookieManager = mock(CookieManager::class.java)
    private val webStorage = mock(WebStorage::class.java)
    private val cookieWrites = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        doAnswer { invocation ->
            cookieWrites += invocation.getArgument<String>(0) to invocation.getArgument<String>(1)
            invocation.getArgument<ValueCallback<Boolean>>(2).onReceiveValue(true)
            null
        }.`when`(cookieManager).setCookie(anyString(), anyString(), any())
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
    fun `platform logout expires that platform's named cookies on every domain and clears its storage`() = runTest {
        doReturn("=x; SESSDATA=abc; bili_jct=def;  =blank; flag; SESSDATA=again")
            .`when`(cookieManager).getCookie("https://passport.bilibili.com")

        withWebView { clearWebViewLoginState(WebLoginPlatform.BILI) }

        assertEquals(
            listOf(
                PASSPORT to expired("SESSDATA"),
                PASSPORT to expired("SESSDATA", domain = ".bilibili.com"),
                PASSPORT to expired("bili_jct"),
                PASSPORT to expired("bili_jct", domain = ".bilibili.com")
            ),
            cookieWrites
        )
        listOf(
            "https://bilibili.com",
            "https://passport.bilibili.com",
            "https://www.bilibili.com",
            "https://m.bilibili.com"
        ).forEach { origin -> verify(webStorage).deleteOrigin(origin) }
        verify(webStorage, never()).deleteAllData()
        verify(cookieManager, never()).removeAllCookies(any())
        verify(cookieManager, never()).getCookie("https://music.163.com")
        verify(cookieManager).flush()
    }

    @Test
    fun `full logout wipes all WebView data before expiring remembered cookies`() = runTest {
        doReturn("MUSIC_U=token").`when`(cookieManager).getCookie("https://music.163.com")
        doReturn("SID=google").`when`(cookieManager).getCookie("https://accounts.google.com")

        withWebView { clearWebViewLoginState() }

        val neteaseDomains = listOf(
            ".music.163.com",
            "music.163.com",
            ".163.com",
            "163.com",
            ".126.net",
            "126.net",
            ".163yun.com",
            "163yun.com"
        )
        assertEquals(
            listOf("https://music.163.com" to expired("MUSIC_U")) +
                neteaseDomains.map { domain -> "https://music.163.com" to expired("MUSIC_U", domain) } +
                listOf(
                    "https://accounts.google.com" to expired("SID"),
                    "https://accounts.google.com" to expired("SID", domain = ".google.com"),
                    "https://accounts.google.com" to expired("SID", domain = ".youtube.com")
                ),
            cookieWrites
        )
        val order = inOrder(cookieManager, webStorage)
        order.verify(cookieManager).removeAllCookies(any())
        order.verify(cookieManager).removeSessionCookies(any())
        order.verify(cookieManager, atLeastOnce()).setCookie(anyString(), anyString(), any())
        order.verify(webStorage).deleteAllData()
        order.verify(cookieManager).flush()
        verify(webStorage, never()).deleteOrigin(anyString())
    }

    @Test
    fun `cookie snapshot failures still wipe all WebView data`() = runTest {
        doThrow(IllegalStateException("WebView provider missing")).`when`(cookieManager).getCookie(anyString())

        withWebView { clearWebViewLoginState() }

        assertEquals(emptyList<Pair<String, String>>(), cookieWrites)
        verify(cookieManager).removeAllCookies(any())
        verify(cookieManager).removeSessionCookies(any())
        verify(webStorage).deleteAllData()
        verify(cookieManager).flush()
    }

    @Test
    fun `cookie expiry failures after a full wipe do not stop storage clearing`() = runTest {
        doReturn("MUSIC_U=token").`when`(cookieManager).getCookie("https://music.163.com")
        doThrow(IllegalStateException("cookie store closed"))
            .`when`(cookieManager).setCookie(anyString(), anyString(), any())

        withWebView { clearWebViewLoginState() }

        verify(cookieManager).setCookie(anyString(), anyString(), any())
        verify(webStorage).deleteAllData()
        verify(cookieManager).flush()
    }

    private suspend fun withWebView(block: suspend () -> Unit) {
        mockStatic(CookieManager::class.java).use { cookies ->
            cookies.`when`<CookieManager> { CookieManager.getInstance() }.thenReturn(cookieManager)
            mockStatic(WebStorage::class.java).use { storage ->
                storage.`when`<WebStorage> { WebStorage.getInstance() }.thenReturn(webStorage)
                block()
            }
        }
    }

    private fun expired(name: String, domain: String? = null): String =
        "$name=$EXPIRED" + domain?.let { "; Domain=$it" }.orEmpty()

    private companion object {
        const val PASSPORT = "https://passport.bilibili.com"
        const val EXPIRED = "; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/"
    }
}
