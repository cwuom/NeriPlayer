package moe.ouom.neriplayer.data.youtube.auth.web

import android.webkit.CookieManager
import moe.ouom.neriplayer.api.youtube.auth.YouTubeCookieSupport
import moe.ouom.neriplayer.core.network.weblogin.ForegroundWebLoginGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

class YouTubeWebSessionAdapterTest {
    @Test
    fun observesForegroundLoginUntilItsOwnerReleasesTheGuard() {
        assertFalse(YouTubeWebSessionAdapter.isForegroundLoginActive)
        ForegroundWebLoginGuard.enter("youtube-session-adapter-test").use {
            assertTrue(YouTubeWebSessionAdapter.isForegroundLoginActive)
            assertEquals(ForegroundWebLoginGuard.SKIP_REASON, YouTubeWebSessionAdapter.foregroundLoginSkipReason)
        }
        assertFalse(YouTubeWebSessionAdapter.isForegroundLoginActive)
    }

    @Test
    fun keepsExistingBrowserIdentityWhileAddingMissingCookiesAndConsent() {
        val cookieManager = mock(CookieManager::class.java)
        val writes = mutableListOf<Pair<String, String>>()
        `when`(cookieManager.getCookie(anyString())).thenReturn("SAPISID=browser")
        doAnswer { invocation ->
            writes += invocation.getArgument<String>(0) to invocation.getArgument<String>(1)
            null
        }.`when`(cookieManager).setCookie(anyString(), anyString())

        YouTubeWebSessionAdapter.applyAuthCookies(
            cookieManager,
            mapOf("SAPISID" to "stored", "SID" to "missing")
        )

        assertFalse(writes.any { it.second.startsWith("SAPISID=") })
        YouTubeCookieSupport.webCookieReadUrls.forEach { url ->
            assertTrue(writes.any { it.first == url && it.second.startsWith("SID=missing;") })
            assertTrue(writes.any { it.first == url && it.second.startsWith("SOCS=") })
        }
    }
}
