package moe.ouom.neriplayer.data.auth.web

import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginCompletionHeuristicsTest {

    @Test
    fun shouldAutoCompleteBiliWebLogin_rejectsAnonymousCookies() {
        assertFalse(
            shouldAutoCompleteBiliWebLogin(
                cookies = mapOf(
                    "buvid3" to "device-cookie",
                    "sid" to "guest-session"
                )
            )
        )
    }

    @Test
    fun shouldAutoCompleteBiliWebLogin_acceptsSessdataCookie() {
        assertTrue(
            shouldAutoCompleteBiliWebLogin(
                cookies = mapOf(
                    "SESSDATA" to "sess-cookie",
                    "bili_jct" to "csrf-token",
                    "DedeUserID" to "12345"
                )
            )
        )
    }

    @Test
    fun shouldAutoCompleteNeteaseWebLogin_requiresCookieChange() {
        val snapshot = mapOf(
            "MUSIC_U" to "music-cookie",
            "__csrf" to "csrf-token",
            "os" to "pc",
            "appver" to "8.10.35"
        )

        assertFalse(
            shouldAutoCompleteNeteaseWebLogin(
                initialCookies = snapshot,
                currentCookies = snapshot
            )
        )
    }

    @Test
    fun shouldAutoCompleteNeteaseWebLogin_acceptsFreshMusicCookie() {
        assertTrue(
            shouldAutoCompleteNeteaseWebLogin(
                initialCookies = mapOf("__csrf" to "guest-token"),
                currentCookies = mapOf(
                    "MUSIC_U" to "music-cookie",
                    "__csrf" to "csrf-token"
                )
            )
        )
    }

    @Test
    fun shouldAutoCompleteNeteaseWebLogin_rejectsMusicAOnlySession() {
        assertFalse(
            shouldAutoCompleteNeteaseWebLogin(
                initialCookies = mapOf("__csrf" to "guest-token"),
                currentCookies = mapOf(
                    "MUSIC_A" to "anonymous-token",
                    "__csrf" to "csrf-token"
                )
            )
        )
    }

    @Test
    fun shouldAutoCompleteYouTubeWebLogin_requiresActiveSessionSignal() {
        assertFalse(
            shouldAutoCompleteYouTubeWebLogin(
                currentAuth = YouTubeAuthBundle(
                    cookies = mapOf(
                        "LOGIN_INFO" to "login-token",
                        "VISITOR_INFO1_LIVE" to "visitor"
                    )
                ),
                pageConfirmedSession = true
            )
        )
    }

    @Test
    fun shouldAutoCompleteYouTubeWebLogin_rejectsUnconfirmedPageSession() {
        val currentAuth = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "LOGIN_INFO" to "login-token",
                "SAPISID" to "sapisid-cookie",
                "__Secure-1PAPISID" to "papisid-cookie",
                "__Secure-1PSIDTS" to "session-token",
                "SID" to "sid-cookie",
                "SIDCC" to "sidcc-cookie"
            ),
            authorization = "SAPISIDHASH 1_signature",
            xGoogAuthUser = "0"
        )

        assertFalse(
            shouldAutoCompleteYouTubeWebLogin(
                currentAuth = currentAuth,
                pageConfirmedSession = false
            )
        )
    }

    @Test
    fun shouldAutoCompleteYouTubeWebLogin_acceptsConfirmedLiveSession() {
        val currentAuth = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "LOGIN_INFO" to "login-token",
                "SAPISID" to "sapisid-cookie",
                "__Secure-1PAPISID" to "papisid-cookie",
                "__Secure-1PSIDTS" to "session-token",
                "SID" to "sid-cookie",
                "SIDCC" to "sidcc-cookie"
            ),
            authorization = "SAPISIDHASH 1_signature",
            xGoogAuthUser = "0"
        )

        assertTrue(
            shouldAutoCompleteYouTubeWebLogin(
                currentAuth = currentAuth,
                pageConfirmedSession = true
            )
        )
    }
}
