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
                authChanged = true,
                recoveredActiveSession = true
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
                authChanged = false,
                recoveredActiveSession = false
            )
        )
    }

    @Test
    fun shouldAcceptYouTubeRefreshResult_allowsCookieRecoveryBeforePageSettles() {
        assertTrue(
            shouldAcceptYouTubeRefreshResult(
                pageReady = false,
                hasYtcfg = false,
                hasLiveSessionSignal = false,
                authChanged = true,
                recoveredActiveSession = false
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
    fun shouldAttemptRefresh_allowsStaleValidAuth() {
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

        assertTrue(decision.allowed)
        assertEquals("allowed", decision.reason)
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
}
