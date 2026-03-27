package moe.ouom.neriplayer.data.auth.youtube

fun mergeYouTubeAuthBundle(
    base: YouTubeAuthBundle,
    observedCookies: Map<String, String>,
    authorization: String = "",
    xGoogAuthUser: String = "",
    origin: String = "",
    userAgent: String = "",
    savedAt: Long = base.savedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
): YouTubeAuthBundle {
    val normalizedBase = base.normalized(savedAt = base.savedAt)
    val mergedCookies = linkedMapOf<String, String>().apply {
        putAll(
            normalizedBase.cookies.ifEmpty {
                parseCookieHeader(normalizedBase.cookieHeader)
            }
        )
        observedCookies.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                put(key, value)
            }
        }
    }

    return YouTubeAuthBundle(
        cookieHeader = mergedCookies.entries.joinToString("; ") { (key, value) -> "$key=$value" },
        cookies = mergedCookies,
        authorization = authorization.ifBlank { normalizedBase.authorization },
        xGoogAuthUser = xGoogAuthUser.ifBlank { normalizedBase.xGoogAuthUser },
        origin = origin.ifBlank { normalizedBase.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN } },
        userAgent = userAgent.ifBlank { normalizedBase.userAgent },
        savedAt = savedAt
    ).normalized(savedAt = savedAt)
}

fun hasMeaningfulYouTubeAuthChange(
    previous: YouTubeAuthBundle,
    current: YouTubeAuthBundle
): Boolean {
    val normalizedPrevious = previous.normalized(savedAt = 0L).copy(savedAt = 0L)
    val normalizedCurrent = current.normalized(savedAt = 0L).copy(savedAt = 0L)
    return normalizedPrevious != normalizedCurrent
}

fun mergeYouTubeAuthCookieUpdates(
    base: YouTubeAuthBundle,
    setCookieHeaders: Iterable<String>,
    savedAt: Long = System.currentTimeMillis()
): YouTubeAuthBundle? {
    val normalizedBase = base.normalized(savedAt = base.savedAt)
    val mergedCookies = linkedMapOf<String, String>().apply {
        putAll(
            normalizedBase.cookies.ifEmpty {
                parseCookieHeader(normalizedBase.cookieHeader)
            }
        )
    }
    var changed = false

    setCookieHeaders.forEach { rawHeader ->
        val update = parseSetCookieUpdate(rawHeader) ?: return@forEach
        if (update.shouldRemove) {
            changed = mergedCookies.remove(update.name) != null || changed
        } else if (mergedCookies[update.name] != update.value) {
            mergedCookies[update.name] = update.value
            changed = true
        }
    }

    if (!changed) {
        return null
    }

    return normalizedBase.copy(
        cookieHeader = mergedCookies.entries.joinToString("; ") { (key, value) -> "$key=$value" },
        cookies = mergedCookies,
        savedAt = savedAt
    ).normalized(savedAt = savedAt)
}

private data class ParsedSetCookieUpdate(
    val name: String,
    val value: String,
    val shouldRemove: Boolean
)

private fun parseSetCookieUpdate(rawHeader: String): ParsedSetCookieUpdate? {
    val segments = rawHeader
        .split(';')
        .map(String::trim)
        .filter { it.isNotBlank() }
    val cookiePair = segments.firstOrNull()?.takeIf { it.contains('=') } ?: return null
    val delimiterIndex = cookiePair.indexOf('=')
    if (delimiterIndex <= 0) {
        return null
    }

    val name = cookiePair.substring(0, delimiterIndex).trim()
    val value = cookiePair.substring(delimiterIndex + 1).trim()
    if (name.isBlank()) {
        return null
    }

    val attributes = linkedMapOf<String, String>()
    segments.drop(1).forEach { attribute ->
        val attributeIndex = attribute.indexOf('=')
        if (attributeIndex <= 0) {
            attributes[attribute.lowercase()] = ""
        } else {
            val key = attribute.substring(0, attributeIndex).trim().lowercase()
            val attributeValue = attribute.substring(attributeIndex + 1).trim()
            attributes[key] = attributeValue
        }
    }

    val maxAge = attributes["max-age"]?.toLongOrNull()
    val expires = attributes["expires"].orEmpty().lowercase()
    val shouldRemove = value.isBlank() ||
        (maxAge != null && maxAge <= 0L) ||
        expires.contains("1970") ||
        expires.contains("1969")

    return ParsedSetCookieUpdate(
        name = name,
        value = value,
        shouldRemove = shouldRemove
    )
}
