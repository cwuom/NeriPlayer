package moe.ouom.neriplayer.activity.auth

import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP

internal const val NETEASE_MOBILE_LOGIN_URL = "https://music.163.com/m/login"
internal const val NETEASE_DESKTOP_LOGIN_URL = "https://music.163.com/"
internal const val NETEASE_MOBILE_WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; NeriPlayer) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
internal const val NETEASE_DESKTOP_WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

internal data class NeteaseWebLoginWebSettings(
    val url: String,
    val userAgent: String,
    val useWideViewPort: Boolean,
    val loadWithOverviewMode: Boolean
)

internal fun resolveNeteaseWebLoginWebSettings(
    smallestScreenWidthDp: Int,
    defaultUserAgent: String? = null
): NeteaseWebLoginWebSettings {
    val isPhone = smallestScreenWidthDp < PHONE_SMALLEST_SCREEN_WIDTH_DP
    return if (isPhone) {
        NeteaseWebLoginWebSettings(
            url = NETEASE_MOBILE_LOGIN_URL,
            userAgent = resolveNeteaseMobileUserAgent(defaultUserAgent),
            // the mobile page has its own viewport meta tag; a wide viewport makes it
            // match the desktop breakpoint even when the device is a phone
            useWideViewPort = false,
            loadWithOverviewMode = false
        )
    } else {
        NeteaseWebLoginWebSettings(
            url = NETEASE_DESKTOP_LOGIN_URL,
            userAgent = NETEASE_DESKTOP_WEB_USER_AGENT,
            useWideViewPort = true,
            loadWithOverviewMode = true
        )
    }
}

internal fun resolveNeteaseWebLoginUrl(smallestScreenWidthDp: Int): String {
    return resolveNeteaseWebLoginWebSettings(smallestScreenWidthDp).url
}

internal fun resolveNeteaseWebLoginUserAgent(smallestScreenWidthDp: Int): String {
    return resolveNeteaseWebLoginWebSettings(smallestScreenWidthDp).userAgent
}

private fun resolveNeteaseMobileUserAgent(defaultUserAgent: String?): String {
    val sanitized = defaultUserAgent
        ?.replace("; wv", "")
        ?.replace("Version/4.0 ", "")
        ?.trim()
        ?.takeIf { userAgent ->
            userAgent.contains("Android", ignoreCase = true) &&
                userAgent.contains("Mobile", ignoreCase = true)
        }
    return sanitized ?: NETEASE_MOBILE_WEB_USER_AGENT
}
