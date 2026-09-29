package moe.ouom.neriplayer.data.youtube.auth.web

import android.webkit.CookieManager
import moe.ouom.neriplayer.api.youtube.auth.YouTubeCookieSupport
import moe.ouom.neriplayer.api.youtube.potoken.YouTubeWebSession
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard

internal object YouTubeWebSessionAdapter : YouTubeWebSession {
    override val isForegroundLoginActive: Boolean
        get() = ForegroundWebLoginGuard.isActive

    override val foregroundLoginSkipReason: String
        get() = ForegroundWebLoginGuard.SKIP_REASON

    override fun applyAuthCookies(cookieManager: CookieManager, cookies: Map<String, String>) {
        applyYouTubeWebCookies(
            cookieManager = cookieManager,
            cookies = cookies,
            urls = YouTubeCookieSupport.webCookieReadUrls,
            skipExisting = true,
            includeConsentCookie = true
        )
    }
}
