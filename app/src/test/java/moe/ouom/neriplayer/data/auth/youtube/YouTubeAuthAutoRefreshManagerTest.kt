package moe.ouom.neriplayer.data.auth.youtube

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class YouTubeAuthAutoRefreshManagerTest {

    @Test
    fun shouldAcceptYouTubeRefreshResult_rejectsSettledGuestPage() {
        assertFalse(
            shouldAcceptYouTubeRefreshResult(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = false,
                recoveredActiveSession = false
            )
        )
    }

    @Test
    fun shouldAcceptYouTubeRefreshResult_acceptsRecoveredAuthOnSettledPage() {
        assertTrue(
            shouldAcceptYouTubeRefreshResult(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = false,
                recoveredActiveSession = true
            )
        )
    }

    @Test
    fun shouldAcceptYouTubeRefreshResult_rejectsSettledGuestPageWhenOnlyCookiesChanged() {
        assertFalse(
            shouldAcceptYouTubeRefreshResult(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = false,
                recoveredActiveSession = false
            )
        )
    }

    @Test
    fun shouldAcceptYouTubeRefreshResult_acceptsLiveSessionSignal() {
        assertTrue(
            shouldAcceptYouTubeRefreshResult(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = true,
                recoveredActiveSession = false
            )
        )
    }

    @Test
    fun shouldAcceptYouTubeRefreshResult_rejectsCookieChurnBeforePageSettles() {
        assertFalse(
            shouldAcceptYouTubeRefreshResult(
                pageReady = false,
                hasYtcfg = false,
                hasLiveSessionSignal = false,
                recoveredActiveSession = false
            )
        )
    }

    @Test
    fun shouldAcceptYouTubeRefreshResult_allowsActiveSessionRecoveryBeforePageSettles() {
        assertTrue(
            shouldAcceptYouTubeRefreshResult(
                pageReady = false,
                hasYtcfg = false,
                hasLiveSessionSignal = false,
                recoveredActiveSession = true
            )
        )
    }

    @Test
    fun shouldTriggerYouTubeRefreshLogin_acceptsSettledGuestPageWithTrustedLoginUrl() {
        assertTrue(
            shouldTriggerYouTubeRefreshLogin(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = false,
                loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube"
            )
        )
    }

    @Test
    fun shouldTriggerYouTubeRefreshLogin_rejectsLiveSessionPage() {
        assertFalse(
            shouldTriggerYouTubeRefreshLogin(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = true,
                loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube"
            )
        )
    }

    @Test
    fun resolveYouTubeRefreshLoginUrl_prefersTrustedSignInUrlFromPage() {
        assertEquals(
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/",
            resolveYouTubeRefreshLoginUrl(
                currentUrl = "https://music.youtube.com/",
                signInUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/",
                hasYtcfg = true
            )
        )
    }

    @Test
    fun resolveYouTubeRefreshLoginUrl_buildsGoogleFallbackForGuestPage() {
        assertEquals(
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F",
            resolveYouTubeRefreshLoginUrl(
                currentUrl = "https://music.youtube.com/",
                signInUrl = "",
                hasYtcfg = true
            )
        )
    }

    @Test
    fun resolveObservedYouTubeAuthUser_fallsBackToPageSessionIndex() {
        assertEquals(
            "7",
            resolveObservedYouTubeAuthUser(
                capturedAuthUser = "",
                pageSessionIndex = "7"
            )
        )
    }

    @Test
    fun resolveObservedYouTubeAuthUser_prefersCapturedAuthUser() {
        assertEquals(
            "3",
            resolveObservedYouTubeAuthUser(
                capturedAuthUser = "3",
                pageSessionIndex = "7"
            )
        )
    }

    @Test
    fun shouldAttemptRefresh_skipsFreshValidAuth() {
        val decision = invokeShouldAttemptRefresh(
            auth = sampleAuth(savedAt = 24L * 60L * 60L * 1000L),
            health = YouTubeAuthHealth(
                state = YouTubeAuthState.Valid,
                ageMs = 60L * 60L * 1000L,
                activeCookieKeys = listOf("SAPISID")
            ),
            now = 25L * 60L * 60L * 1000L,
            force = false
        )

        assertFalse(decision.allowed)
        assertEquals("auth_valid", decision.reason)
    }

    @Test
    fun shouldAttemptRefresh_skipsStaleValidAuthWithoutForce() {
        val decision = invokeShouldAttemptRefresh(
            auth = sampleAuth(savedAt = 0L),
            health = YouTubeAuthHealth(
                state = YouTubeAuthState.Valid,
                ageMs = 24L * 60L * 60L * 1000L,
                activeCookieKeys = listOf("SAPISID")
            ),
            now = 25L * 60L * 60L * 1000L,
            force = false
        )

        assertFalse(decision.allowed)
        assertEquals("auth_valid", decision.reason)
    }

    @Test
    fun webAuthRecoveryAcceptsUnauthorizedOnly() {
        assertTrue(isYouTubeAuthRecoverableFailure(Exception("request failed: 401")))
        assertTrue(isYouTubeAuthRecoverableFailure(Exception("request failed: 403")))
        assertTrue(isYouTubeAuthRecoverableFailure(Exception("request failed: 429")))
        assertTrue(shouldStartYouTubeWebAuthRecovery(Exception("request failed: 401")))
        assertFalse(shouldStartYouTubeWebAuthRecovery(Exception("request failed: 403")))
        assertFalse(shouldStartYouTubeWebAuthRecovery(Exception("request failed: 429")))
        assertFalse(shouldStartYouTubeWebAuthRecovery(Exception("blocked response")))
    }

    private data class GateDecisionSnapshot(
        val allowed: Boolean,
        val reason: String
    )

    private fun invokeShouldAttemptRefresh(
        auth: YouTubeAuthBundle,
        health: YouTubeAuthHealth,
        now: Long,
        force: Boolean
    ): GateDecisionSnapshot {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val manager = YouTubeAuthAutoRefreshManager(
            context = context,
            authProvider = { auth },
            authHealthProvider = { health }
        )
        val method = YouTubeAuthAutoRefreshManager::class.java.getDeclaredMethod(
            "shouldAttemptRefresh",
            YouTubeAuthBundle::class.java,
            YouTubeAuthHealth::class.java,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        val decision = method.invoke(manager, auth, health, now, force)
        val allowedField = decision.javaClass.getDeclaredField("allowed").apply {
            isAccessible = true
        }
        val reasonField = decision.javaClass.getDeclaredField("reason").apply {
            isAccessible = true
        }
        return GateDecisionSnapshot(
            allowed = allowedField.getBoolean(decision),
            reason = reasonField.get(decision) as String
        )
    }

    private fun sampleAuth(savedAt: Long): YouTubeAuthBundle {
        return YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SID" to "sid-value",
                "SAPISID" to "sap-value"
            ),
            savedAt = savedAt
        ).normalized(savedAt = savedAt)
    }

    @Test
    fun `keeps session instead of opening sign-in page when login cookies are intact`() {
        // 页面没暴露会话信号但 cookie 还在，跳登录页在用户看来就是掉登录态
        assertFalse(
            shouldTriggerYouTubeRefreshLogin(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = false,
                loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube",
                hasActiveSessionCookies = true
            )
        )
    }

    @Test
    fun `still signs in when no login cookies are held`() {
        assertTrue(
            shouldTriggerYouTubeRefreshLogin(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = false,
                loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube",
                hasActiveSessionCookies = false
            )
        )
    }

    @Test
    fun `salvages the http only identity cookies the archive never captured`() {
        // 存档里从来没有 HSID 和 LOGIN_INFO, 浏览器里一直都在
        val salvaged = collectSalvageableYouTubeIdentityCookies(
            persistedCookies = mapOf(
                "SID" to "sid-value",
                "SAPISID" to "sapisid-value",
                "__Secure-1PSID" to "1psid-value"
            ),
            observedCookies = mapOf(
                "SID" to "sid-value",
                "SAPISID" to "sapisid-value",
                "__Secure-1PSID" to "1psid-value",
                "HSID" to "hsid-value",
                "LOGIN_INFO" to "login-info-value"
            )
        )

        assertEquals(
            mapOf("HSID" to "hsid-value", "LOGIN_INFO" to "login-info-value"),
            salvaged
        )
    }

    @Test
    fun `salvages nothing once the archive already holds every identity cookie`() {
        val salvaged = collectSalvageableYouTubeIdentityCookies(
            persistedCookies = mapOf(
                "SID" to "sid-value",
                "HSID" to "hsid-value",
                "LOGIN_INFO" to "login-info-value"
            ),
            observedCookies = mapOf(
                "SID" to "sid-value",
                "HSID" to "hsid-rotated",
                "LOGIN_INFO" to "login-info-rotated"
            )
        )

        // 已有值不参与, 免得拿一份被判过游客态的快照覆盖掉有效凭据
        assertTrue(salvaged.isEmpty())
    }

    @Test
    fun `salvages nothing from an anonymous observation`() {
        val salvaged = collectSalvageableYouTubeIdentityCookies(
            persistedCookies = mapOf("SID" to "sid-value"),
            observedCookies = mapOf(
                "VISITOR_INFO1_LIVE" to "visitor-value",
                "YSC" to "ysc-value",
                "NID" to "nid-value"
            )
        )

        assertTrue(salvaged.isEmpty())
    }

    @Test
    fun `leaves non identity cookies out of the salvage`() {
        val salvaged = collectSalvageableYouTubeIdentityCookies(
            persistedCookies = mapOf("SID" to "sid-value"),
            observedCookies = mapOf(
                "SID" to "sid-value",
                "HSID" to "hsid-value",
                "VISITOR_INFO1_LIVE" to "visitor-value",
                "YSC" to "ysc-value",
                "PREF" to "pref-value"
            )
        )

        assertEquals(mapOf("HSID" to "hsid-value"), salvaged)
    }

    @Test
    fun `skips blank observed values so a cleared cookie never lands in the archive`() {
        val salvaged = collectSalvageableYouTubeIdentityCookies(
            persistedCookies = mapOf("SID" to "sid-value"),
            observedCookies = mapOf(
                "SID" to "sid-value",
                "HSID" to "",
                "LOGIN_INFO" to "login-info-value"
            )
        )

        assertEquals(mapOf("LOGIN_INFO" to "login-info-value"), salvaged)
    }

    @Test
    fun `merging a salvage only adds keys and keeps every persisted value`() {
        val persisted = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SID" to "sid-value",
                "SAPISID" to "sapisid-value"
            ),
            cookieHeader = "SID=sid-value; SAPISID=sapisid-value",
            savedAt = 1_000L
        )

        val merged = mergeYouTubeAuthBundle(
            base = persisted,
            observedCookies = mapOf("HSID" to "hsid-value")
        )

        assertEquals("sid-value", merged.cookies["SID"])
        assertEquals("sapisid-value", merged.cookies["SAPISID"])
        assertEquals("hsid-value", merged.cookies["HSID"])
        // 只补 cookie 不该顺手刷新时间戳, 否则会盖掉刷新新鲜度判断
        assertEquals(1_000L, merged.savedAt)
    }
}
