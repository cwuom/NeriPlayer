package moe.ouom.neriplayer.data.auth.web

import moe.ouom.neriplayer.data.auth.bili.BiliAuthBundle
import moe.ouom.neriplayer.data.auth.bili.evaluateBiliAuthHealth
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.netease.NeteaseAuthBundle
import moe.ouom.neriplayer.data.auth.netease.evaluateNeteaseAuthHealth
import moe.ouom.neriplayer.data.auth.netease.validateAndSanitizeNeteaseCookies
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.auth.youtube.evaluateYouTubeAuthHealth

private const val SYNTHETIC_SAVED_AT = 1L
private const val SYNTHETIC_CHECKED_AT = 2L

// 浏览器登录成功页并不稳定，这里统一改为基于真正可用的鉴权信号判定
internal fun shouldAutoCompleteBiliWebLogin(cookies: Map<String, String>): Boolean {
    val health = evaluateBiliAuthHealth(
        bundle = BiliAuthBundle(
            cookies = cookies,
            savedAt = SYNTHETIC_SAVED_AT
        ),
        now = SYNTHETIC_CHECKED_AT
    )
    return health.state != SavedCookieAuthState.Missing
}

internal fun normalizeNeteaseWebLoginCookies(
    cookies: Map<String, String>
): Map<String, String> {
    return validateAndSanitizeNeteaseCookies(cookies).sanitizedCookies
}

internal fun shouldAutoCompleteNeteaseWebLogin(
    initialCookies: Map<String, String>,
    currentCookies: Map<String, String>
): Boolean {
    val normalizedCurrent = normalizeNeteaseWebLoginCookies(currentCookies)
    if (normalizedCurrent["MUSIC_U"].isNullOrBlank()) {
        return false
    }
    val currentHealth = evaluateNeteaseAuthHealth(
        bundle = NeteaseAuthBundle(
            cookies = normalizedCurrent,
            savedAt = SYNTHETIC_SAVED_AT
        ),
        now = SYNTHETIC_CHECKED_AT
    )
    if (currentHealth.state == SavedCookieAuthState.Missing) {
        return false
    }

    val normalizedInitial = normalizeNeteaseWebLoginCookies(initialCookies)
    return normalizedInitial != normalizedCurrent
}

internal fun shouldAutoCompleteYouTubeWebLogin(
    currentAuth: YouTubeAuthBundle,
    pageConfirmedSession: Boolean
): Boolean {
    val normalizedCurrent = currentAuth.normalized(
        savedAt = currentAuth.savedAt.takeIf { it > 0L } ?: SYNTHETIC_SAVED_AT
    )
    if (!normalizedCurrent.isUsable()) {
        return false
    }

    val currentHealth = evaluateYouTubeAuthHealth(
        bundle = normalizedCurrent,
        now = SYNTHETIC_CHECKED_AT
    )
    if (currentHealth.state == YouTubeAuthState.Missing || currentHealth.activeCookieKeys.isEmpty()) {
        return false
    }

    return pageConfirmedSession
}
