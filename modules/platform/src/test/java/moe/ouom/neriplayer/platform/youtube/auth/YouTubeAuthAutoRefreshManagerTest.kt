package moe.ouom.neriplayer.platform.youtube.auth

import moe.ouom.neriplayer.platform.youtube.api.auth.normalized

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthHealth
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthState
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class YouTubeAuthAutoRefreshManagerTest {

    @Test
    fun `rotation storage failure preserves the current account and lets the caller continue`() = runBlocking {
        assertStorageFailureIsReturned(useRotatedCookieUpdater = true)
    }

    @Test
    fun `fallback auth updater storage failure does not escape the refresh`() = runBlocking {
        assertStorageFailureIsReturned(useRotatedCookieUpdater = false)
    }

    @Test
    fun `rotation publishes changed cookies before reporting successful refresh`() = runBlocking {
        withRotationManager { manager, saved, _ ->
            val result = manager.refreshIfNeeded(reason = "synthetic rotation", force = true)

            assertTrue(result.attempted)
            assertTrue(result.refreshed)
            assertTrue(result.authChanged)
            assertEquals("rotated", result.reason)
            assertEquals("synthetic-rotated", saved().cookies["__Secure-1PSIDTS"])
        }
    }

    @Test
    fun `rotation callback programming errors are not treated as storage failures`() = runBlocking {
        assertCallbackFailureEscapes(IllegalStateException("synthetic callback bug"))
    }

    @Test
    fun `rotation callback cancellation is not treated as a storage failure`() = runBlocking {
        assertCallbackFailureEscapes(CancellationException("synthetic cancellation"))
    }

    private suspend fun assertStorageFailureIsReturned(useRotatedCookieUpdater: Boolean) {
        withRotationManager(
            updateFailure = IOException("synthetic storage failure"),
            useRotatedCookieUpdater = useRotatedCookieUpdater
        ) { manager, saved, initial ->
            val result = manager.refreshIfNeeded(reason = "synthetic rotation", force = true)

            assertTrue(result.attempted)
            assertFalse(result.refreshed)
            assertFalse(result.authChanged)
            assertEquals("storage_unavailable", result.reason)
            assertEquals(initial, saved())
        }
    }

    private suspend fun assertCallbackFailureEscapes(failure: RuntimeException) {
        withRotationManager(updateFailure = failure) { manager, saved, initial ->
            try {
                manager.refreshIfNeeded(reason = "synthetic rotation", force = true)
                fail("The callback exception must propagate")
            } catch (actual: RuntimeException) {
                assertSame(failure, actual)
            }
            assertEquals(initial, saved())
        }
    }

    private suspend fun withRotationManager(
        updateFailure: Exception? = null,
        useRotatedCookieUpdater: Boolean = true,
        block: suspend (YouTubeAuthAutoRefreshManager, () -> YouTubeAuthBundle, YouTubeAuthBundle) -> Unit
    ) {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val initial = YouTubeAuthBundle(
            cookies = mapOf("__Secure-1PSID" to "synthetic-session", "SAPISID" to "synthetic-binding"),
            savedAt = 1L
        ).normalized()
        var saved = initial
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/RotateCookies", chain.request().url.encodedPath)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Set-Cookie", "__Secure-1PSIDTS=synthetic-rotated; Path=/; Secure; HttpOnly")
                .body("".toResponseBody())
                .build()
        }.build()
        val previouslyEnabled = YouTubeFeatureGate.isEnabled()
        YouTubeFeatureGate.update(true)
        try {
            val updater: (YouTubeAuthBundle) -> Unit = { bundle ->
                if (updateFailure != null) throw updateFailure
                saved = bundle
            }
            val manager = YouTubeAuthAutoRefreshManager(
                context = context,
                httpClientProvider = { client },
                authProvider = { saved },
                authHealthProvider = {
                    YouTubeAuthHealth(state = YouTubeAuthState.Valid, activeCookieKeys = listOf("SAPISID"))
                },
                authUpdater = updater,
                rotatedCookieUpdater = if (useRotatedCookieUpdater) { cookies ->
                    updater(mergeYouTubeAuthBundle(base = saved, observedCookies = cookies))
                } else null
            )
            block(manager, { saved }, initial)
        } finally {
            YouTubeFeatureGate.update(previouslyEnabled)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

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
            httpClientProvider = { okhttp3.OkHttpClient() },
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
