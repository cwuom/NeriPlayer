package moe.ouom.neriplayer.platform.youtube.api.potoken

import android.webkit.CookieManager

interface YouTubeWebSession {
    val isForegroundLoginActive: Boolean
    val foregroundLoginSkipReason: String
    fun applyAuthCookies(cookieManager: CookieManager, cookies: Map<String, String>)
}
