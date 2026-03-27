package moe.ouom.neriplayer.data.auth.youtube

import android.webkit.CookieManager

private const val YOUTUBE_WEB_COOKIE_TEMPLATE_SUFFIX = "; Path=/; Domain=.youtube.com; Secure"
private const val YOUTUBE_WEB_COOKIE_CLEAR_SUFFIX = "; Max-Age=0; Path=/; Domain=.youtube.com; Secure"
private const val YOUTUBE_WEB_HOST_COOKIE_CLEAR_SUFFIX = "; Max-Age=0; Path=/; Secure"
private const val YOUTUBE_SOCS_COOKIE = "SOCS=CAI$YOUTUBE_WEB_COOKIE_TEMPLATE_SUFFIX"

internal fun shouldApplyYouTubeConsentCookie(
    includeConsentCookie: Boolean,
    sanitizedCookies: Map<String, String>,
    existingCookies: Map<String, String>,
    replaceExisting: Boolean
): Boolean {
    if (!includeConsentCookie || !sanitizedCookies["SOCS"].isNullOrBlank()) {
        return false
    }
    return replaceExisting || existingCookies["SOCS"].isNullOrBlank()
}

internal fun collectYouTubeWebCookies(
    cookieManager: CookieManager,
    urls: Iterable<String> = YouTubeCookieSupport.webCookieReadUrls
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
    replaceExisting: Boolean = false,
    includeConsentCookie: Boolean = false
): Boolean {
    val sanitizedCookies = YouTubeCookieSupport.sanitizePersistedCookies(cookies)
    val existingCookies = if (skipExisting || replaceExisting) {
        collectYouTubeWebCookies(cookieManager, urls)
    } else {
        emptyMap()
    }
    var changed = false

    urls.forEach { url ->
        if (replaceExisting) {
            existingCookies.keys.forEach { key ->
                if (sanitizedCookies[key].isNullOrBlank()) {
                    cookieManager.setCookie(url, "$key=$YOUTUBE_WEB_COOKIE_CLEAR_SUFFIX")
                    cookieManager.setCookie(url, "$key=$YOUTUBE_WEB_HOST_COOKIE_CLEAR_SUFFIX")
                    changed = true
                }
            }
        }

        sanitizedCookies.forEach { (key, value) ->
            if (key.isBlank() || value.isBlank()) {
                return@forEach
            }
            if (
                skipExisting &&
                !replaceExisting &&
                !existingCookies[key].isNullOrBlank()
            ) {
                return@forEach
            }
            cookieManager.setCookie(
                url,
                "$key=$value$YOUTUBE_WEB_COOKIE_TEMPLATE_SUFFIX"
            )
            changed = true
        }

        if (shouldApplyYouTubeConsentCookie(
                includeConsentCookie = includeConsentCookie,
                sanitizedCookies = sanitizedCookies,
                existingCookies = existingCookies,
                replaceExisting = replaceExisting
            )
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
