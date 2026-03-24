package moe.ouom.neriplayer.data.auth.youtube

import android.webkit.CookieManager

private const val YOUTUBE_WEB_COOKIE_TEMPLATE_SUFFIX = "; Path=/; Domain=.youtube.com; Secure"
private const val YOUTUBE_SOCS_COOKIE = "SOCS=CAI$YOUTUBE_WEB_COOKIE_TEMPLATE_SUFFIX"

internal fun collectYouTubeWebCookies(
    cookieManager: CookieManager,
    urls: Iterable<String> = YouTubeCookieSupport.webUrls
): LinkedHashMap<String, String> {
    return YouTubeCookieSupport.mergeCookieStrings(
        urls.map { url -> cookieManager.getCookie(url).orEmpty() }
    )
}

internal fun applyYouTubeWebCookies(
    cookieManager: CookieManager,
    cookies: Map<String, String>,
    urls: Iterable<String> = YouTubeCookieSupport.webUrls,
    skipExisting: Boolean = true,
    includeConsentCookie: Boolean = false
): Boolean {
    val sanitizedCookies = YouTubeCookieSupport.sanitizePersistedCookies(cookies)
    val existingCookies = if (skipExisting) {
        collectYouTubeWebCookies(cookieManager, urls)
    } else {
        emptyMap()
    }
    var changed = false

    urls.forEach { url ->
        sanitizedCookies.forEach { (key, value) ->
            if (key.isBlank() || value.isBlank()) {
                return@forEach
            }
            if (skipExisting && !existingCookies[key].isNullOrBlank()) {
                return@forEach
            }
            cookieManager.setCookie(
                url,
                "$key=$value$YOUTUBE_WEB_COOKIE_TEMPLATE_SUFFIX"
            )
            changed = true
        }

        if (
            includeConsentCookie &&
            existingCookies["SOCS"].isNullOrBlank() &&
            sanitizedCookies["SOCS"].isNullOrBlank()
        ) {
            cookieManager.setCookie(url, YOUTUBE_SOCS_COOKIE)
            changed = true
        }
    }

    if (changed) {
        cookieManager.flush()
    }
    return changed
}
