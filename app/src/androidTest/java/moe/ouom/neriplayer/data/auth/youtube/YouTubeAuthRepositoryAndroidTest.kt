package moe.ouom.neriplayer.data.auth.youtube

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TestYouTubeAuthPrefs = "youtube_auth_secure_prefs"

@RunWith(AndroidJUnit4::class)
class YouTubeAuthRepositoryAndroidTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        clearStorage()
    }

    @After
    fun tearDown() {
        clearStorage()
    }

    @Test
    fun saveAuth_persistsAcrossRepositoryRecreation_andClearRemovesIt() {
        val savedAt = System.currentTimeMillis()
        val firstRepository = YouTubeAuthRepository(context)

        firstRepository.saveAuth(
            YouTubeAuthBundle(
                cookies = linkedMapOf(
                    "SAPISID" to "sapisid-cookie",
                    "__Secure-1PSIDTS" to "session-token",
                    "VISITOR_INFO1_LIVE" to "visitor-token"
                ),
                authorization = "SAPISIDHASH 1_signature",
                xGoogAuthUser = "0",
                userAgent = "NeriPlayerTest/1.0",
                savedAt = savedAt
            )
        )

        val recreatedRepository = YouTubeAuthRepository(context)
        val storedAuth = recreatedRepository.getAuthOnce()
        assertEquals("sapisid-cookie", storedAuth.cookies["SAPISID"])
        assertEquals("session-token", storedAuth.cookies["__Secure-1PSIDTS"])
        assertEquals("visitor-token", storedAuth.cookies["VISITOR_INFO1_LIVE"])
        assertTrue(storedAuth.cookieHeader.contains("SAPISID=sapisid-cookie"))
        assertEquals("SAPISIDHASH 1_signature", storedAuth.authorization)
        assertEquals("0", storedAuth.xGoogAuthUser)
        assertEquals("NeriPlayerTest/1.0", storedAuth.userAgent)
        assertEquals(YouTubeAuthState.Valid, recreatedRepository.getAuthHealth(now = savedAt + 1_000L).state)

        recreatedRepository.clear()

        val clearedRepository = YouTubeAuthRepository(context)
        assertTrue(clearedRepository.getAuthOnce().cookies.isEmpty())
        assertEquals("", clearedRepository.getAuthOnce().authorization)
        assertEquals(YouTubeAuthState.Missing, clearedRepository.getAuthHealthOnce().state)
    }

    @Test
    fun mergeCookieUpdates_updatesStoredCookiesAcrossRepositoryRecreation() {
        val repository = YouTubeAuthRepository(context)
        repository.saveAuth(
            YouTubeAuthBundle(
                cookies = linkedMapOf(
                    "SAPISID" to "old-sapisid",
                    "LOGIN_INFO" to "login-token",
                    "__Secure-1PSIDTS" to "session-token"
                ),
                savedAt = 1_000L
            )
        )

        assertTrue(
            repository.mergeCookieUpdates(
                listOf(
                    "SAPISID=new-sapisid; Path=/; Secure; HttpOnly",
                    "LOGIN_INFO=; Max-Age=0; Path=/; Secure",
                    "__Secure-1PAPISID=papisid-token; Path=/; Secure"
                )
            )
        )

        val recreatedRepository = YouTubeAuthRepository(context)
        val mergedAuth = recreatedRepository.getAuthOnce()
        assertEquals("new-sapisid", mergedAuth.cookies["SAPISID"])
        assertEquals("papisid-token", mergedAuth.cookies["__Secure-1PAPISID"])
        assertFalse(mergedAuth.cookies.containsKey("LOGIN_INFO"))
        assertTrue(mergedAuth.savedAt >= 1_000L)
        assertEquals(YouTubeAuthState.Valid, recreatedRepository.getAuthHealthOnce().state)
    }

    private fun clearStorage() {
        context.deleteSharedPreferences(TestYouTubeAuthPrefs)
    }
}
