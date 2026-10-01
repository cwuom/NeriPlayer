package moe.ouom.neriplayer.platform.youtube.auth.web

import android.webkit.CookieManager
import moe.ouom.neriplayer.platform.youtube.api.auth.YouTubeCookieSupport
import moe.ouom.neriplayer.platform.youtube.api.potoken.YouTubeWebSession
import moe.ouom.neriplayer.network.weblogin.ForegroundWebLoginGuard

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
