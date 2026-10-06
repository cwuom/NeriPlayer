package moe.ouom.neriplayer.network.security

import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SecurityGuardsTest {

    @Test
    fun `exact hosts and subdomains pass while lookalikes fail`() {
        assertTrue(isExactHostOrSubdomain("www.Bilibili.com.", "bilibili.com"))
        assertTrue(isExactHostOrSubdomain("bilibili.com", "BILIBILI.COM."))
        assertFalse(isExactHostOrSubdomain("notbilibili.com", "bilibili.com"))
        assertFalse(isExactHostOrSubdomain(null, "bilibili.com"))
        assertFalse(isExactHostOrSubdomain(" ", "bilibili.com"))
        assertFalse(isExactHostOrSubdomain("bilibili.com", "."))
    }

    @Test
    fun `only https uris reach the host allow list`() {
        val checkedHosts = mutableListOf<String?>()
        val allowHost: (String?) -> Boolean = { host ->
            checkedHosts += host
            host == "music.youtube.com"
        }

        assertFalse(isAllowedHttpsUri(null, allowHost))
        assertFalse(isAllowedHttpsUri(uri(scheme = "http", host = "music.youtube.com"), allowHost))
        assertFalse(isAllowedHttpsUri(uri(scheme = null, host = "music.youtube.com"), allowHost))
        assertTrue(isAllowedHttpsUri(uri(scheme = "HTTPS", host = "music.youtube.com"), allowHost))
        assertFalse(isAllowedHttpsUri(uri(scheme = "https", host = "evil.example"), allowHost))
        assertEquals(listOf("music.youtube.com", "evil.example"), checkedHosts)
    }

    @Test
    fun `only disallowed main frame navigations are blocked`() {
        assertTrue(shouldBlockMainFrameNavigation(isForMainFrame = true, isAllowedNavigation = false))
        assertFalse(shouldBlockMainFrameNavigation(isForMainFrame = true, isAllowedNavigation = true))
        assertFalse(shouldBlockMainFrameNavigation(isForMainFrame = false, isAllowedNavigation = false))
    }

    @Test
    fun `only main frame requests are blocked by the uri allow list`() {
        val allowed = uri(scheme = "https", host = "music.youtube.com")
        val blocked = uri(scheme = "https", host = "evil.example")
        val checkedUris = mutableListOf<Uri>()
        val allowUri: (Uri) -> Boolean = { uri ->
            checkedUris += uri
            uri === allowed
        }

        assertFalse(isAllowedMainFrameRequest(null, allowUri))
        assertTrue(isAllowedMainFrameRequest(request(allowed, isForMainFrame = true), allowUri))
        assertFalse(isAllowedMainFrameRequest(request(blocked, isForMainFrame = true), allowUri))
        assertTrue(isAllowedMainFrameRequest(request(blocked, isForMainFrame = false), allowUri))
        assertEquals(listOf(allowed, blocked, blocked), checkedUris)
    }

    private fun uri(scheme: String?, host: String): Uri {
        val uri = mock(Uri::class.java)
        `when`(uri.scheme).thenReturn(scheme)
        `when`(uri.host).thenReturn(host)
        return uri
    }

    private fun request(url: Uri, isForMainFrame: Boolean): WebResourceRequest {
        val request = mock(WebResourceRequest::class.java)
        `when`(request.url).thenReturn(url)
        `when`(request.isForMainFrame).thenReturn(isForMainFrame)
        return request
    }
}
