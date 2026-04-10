package moe.ouom.neriplayer.data.auth.youtube

import android.webkit.CookieManager
import java.net.URI
import java.util.Locale

private const val YOUTUBE_WEB_COOKIE_YOUTUBE_DOMAIN = ".youtube.com"
private const val YOUTUBE_WEB_COOKIE_GOOGLE_DOMAIN = ".google.com"
private const val YOUTUBE_WEB_COOKIE_HOST_TEMPLATE_SUFFIX = "; Path=/; Secure"
private const val YOUTUBE_WEB_COOKIE_HOST_CLEAR_SUFFIX = "; Max-Age=0; Path=/; Secure"

internal fun resolveYouTubeWebCookieDomain(url: String): String? {
    val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.US) }
        .getOrDefault("")
    return when {
        host == "youtube.com" || host.endsWith(".youtube.com") -> YOUTUBE_WEB_COOKIE_YOUTUBE_DOMAIN
        host == "google.com" || host.endsWith(".google.com") -> YOUTUBE_WEB_COOKIE_GOOGLE_DOMAIN
        else -> null
    }
}

private fun buildYouTubeWebCookieTemplateSuffix(url: String): String {
    val domain = resolveYouTubeWebCookieDomain(url)
    return if (domain.isNullOrBlank()) {
        YOUTUBE_WEB_COOKIE_HOST_TEMPLATE_SUFFIX
    } else {
        "; Path=/; Domain=$domain; Secure"
    }
}

private fun buildYouTubeWebCookieClearSuffix(url: String): String {
    val domain = resolveYouTubeWebCookieDomain(url)
    return if (domain.isNullOrBlank()) {
        YOUTUBE_WEB_COOKIE_HOST_CLEAR_SUFFIX
    } else {
        "; Max-Age=0; Path=/; Domain=$domain; Secure"
    }
}

private fun buildYouTubeSocsCookie(url: String): String {
    return "SOCS=CAI${buildYouTubeWebCookieTemplateSuffix(url)}"
}

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
                    cookieManager.setCookie(url, "$key=${buildYouTubeWebCookieClearSuffix(url)}")
                    cookieManager.setCookie(url, "$key=$YOUTUBE_WEB_COOKIE_HOST_CLEAR_SUFFIX")
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
                "$key=$value${buildYouTubeWebCookieTemplateSuffix(url)}"
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
            cookieManager.setCookie(url, buildYouTubeSocsCookie(url))
            changed = true
        }
    }

    if (changed) {
        cookieManager.flush()
    }
    return changed
}

internal fun clearYouTubeWebCookies(
    cookieManager: CookieManager,
    cookieKeys: Iterable<String>,
    urls: Iterable<String> = YouTubeCookieSupport.webCookieReadUrls
): Boolean {
    val normalizedKeys = cookieKeys
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    if (normalizedKeys.isEmpty()) {
        return false
    }

    urls.forEach { url ->
        normalizedKeys.forEach { key ->
            cookieManager.setCookie(url, "$key=${buildYouTubeWebCookieClearSuffix(url)}")
            cookieManager.setCookie(url, "$key=$YOUTUBE_WEB_COOKIE_HOST_CLEAR_SUFFIX")
        }
    }
    cookieManager.flush()
    return true
}
