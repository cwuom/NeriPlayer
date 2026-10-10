package moe.ouom.neriplayer.platform.youtube.auth.web

import android.webkit.CookieManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class YouTubeWebCookieManagerTest {
    private val cookieManager = mock(CookieManager::class.java)
    private val music = "https://music.youtube.com"
    private val other = "https://example.com"

    @Test
    fun `cookie domains only cover YouTube and Google hosts`() {
        assertEquals(".youtube.com", resolveYouTubeWebCookieDomain("https://YouTube.com/watch"))
        assertEquals(".youtube.com", resolveYouTubeWebCookieDomain("https://m.youtube.com/"))
        assertEquals(".google.com", resolveYouTubeWebCookieDomain("https://google.com/"))
        assertNull(resolveYouTubeWebCookieDomain("https://notyoutube.com/"))
        assertNull(resolveYouTubeWebCookieDomain("mailto:someone@youtube.com"))
        assertNull(resolveYouTubeWebCookieDomain("https://bad host/"))
    }

    @Test
    fun `consent cookie is added only when no usable consent exists`() {
        assertFalse(shouldApplyYouTubeConsentCookie(false, emptyMap(), emptyMap(), replaceExisting = true))
        assertFalse(shouldApplyYouTubeConsentCookie(true, mapOf("SOCS" to "CAI"), emptyMap(), replaceExisting = false))
        assertTrue(shouldApplyYouTubeConsentCookie(true, mapOf("SOCS" to " "), mapOf("SOCS" to "CAE"), replaceExisting = true))
        assertTrue(shouldApplyYouTubeConsentCookie(true, emptyMap(), emptyMap(), replaceExisting = false))
        assertTrue(shouldApplyYouTubeConsentCookie(true, emptyMap(), mapOf("SOCS" to " "), replaceExisting = false))
        assertFalse(shouldApplyYouTubeConsentCookie(true, emptyMap(), mapOf("SOCS" to "CAE"), replaceExisting = false))
    }

    @Test
    fun `applying cookies skips existing values and scopes writes to the url domain`() {
        `when`(cookieManager.getCookie(music)).thenReturn("SID=old")

        val changed = applyYouTubeWebCookies(
            cookieManager = cookieManager,
            cookies = linkedMapOf("SID" to "sid", "YSC" to "ysc", "ST-page" to "1"),
            urls = listOf(music, other),
            includeConsentCookie = true
        )

        assertTrue(changed)
        assertEquals(
            listOf(
                music to "YSC=ysc; Path=/; Domain=.youtube.com; Secure",
                music to "SOCS=CAI; Path=/; Domain=.youtube.com; Secure",
                other to "YSC=ysc; Path=/; Secure",
                other to "SOCS=CAI; Path=/; Secure"
            ),
            setCookieCalls()
        )
        verify(cookieManager).flush()
    }

    @Test
    fun `replacing cookies expires stale page cookies but keeps identity cookies`() {
        `when`(cookieManager.getCookie(music)).thenReturn("SID=old; HSID=hsid; YSC=stale")

        val changed = applyYouTubeWebCookies(
            cookieManager = cookieManager,
            cookies = linkedMapOf("SID" to "new"),
            urls = listOf(music),
            replaceExisting = true
        )

        assertTrue(changed)
        assertEquals(
            listOf(
                music to "YSC=; Max-Age=0; Path=/; Domain=.youtube.com; Secure",
                music to "YSC=; Max-Age=0; Path=/; Secure",
                music to "SID=new; Path=/; Domain=.youtube.com; Secure"
            ),
            setCookieCalls()
        )
    }

    @Test
    fun `applying nothing new leaves the cookie jar untouched`() {
        assertFalse(
            applyYouTubeWebCookies(
                cookieManager = cookieManager,
                cookies = mapOf("ST-page" to "1"),
                urls = listOf(music),
                skipExisting = false
            )
        )
        assertTrue(setCookieCalls().isEmpty())
        verify(cookieManager, never()).flush()
    }

    @Test
    fun `clearing cookies expires each distinct key on every url`() {
        assertFalse(clearYouTubeWebCookies(cookieManager, listOf(" ", "")))
        verifyNoInteractions(cookieManager)

        assertTrue(clearYouTubeWebCookies(cookieManager, listOf(" SID ", "SID"), urls = listOf(music, other)))

        assertEquals(
            listOf(
                music to "SID=; Max-Age=0; Path=/; Domain=.youtube.com; Secure",
                music to "SID=; Max-Age=0; Path=/; Secure",
                other to "SID=; Max-Age=0; Path=/; Secure",
                other to "SID=; Max-Age=0; Path=/; Secure"
            ),
            setCookieCalls()
        )
        verify(cookieManager).flush()
    }

    private fun setCookieCalls(): List<Pair<String, String>> =
        mockingDetails(cookieManager).invocations
            .filter { it.method.name == "setCookie" }
            .map { it.arguments[0] as String to it.arguments[1] as String }
}
