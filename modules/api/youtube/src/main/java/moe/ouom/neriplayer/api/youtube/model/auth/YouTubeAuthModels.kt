package moe.ouom.neriplayer.api.youtube.model.auth

import org.json.JSONObject

const val YOUTUBE_MUSIC_ORIGIN: String = "https://music.youtube.com"

data class YouTubeAuthBundle(
    val cookieHeader: String = "",
    val cookies: Map<String, String> = emptyMap(),
    val authorization: String = "",
    val xGoogAuthUser: String = "",
    val origin: String = YOUTUBE_MUSIC_ORIGIN,
    val userAgent: String = "",
    val savedAt: Long = 0L
) {
    fun hasLoginCookies(): Boolean {
        val normalizedCookies = when {
            cookies.isNotEmpty() -> cookies
            cookieHeader.isNotBlank() -> parseCookieHeader(cookieHeader)
            else -> emptyMap()
        }
        return YouTubeCookieSupport.isLoggedIn(normalizedCookies)
    }

    fun hasEffectiveAuth(): Boolean {
        return hasLoginCookies() || authorization.isNotBlank()
    }

    fun hasSavedAuthMaterial(): Boolean {
        val normalized = normalized(savedAt = savedAt)
        return normalized.cookieHeader.isNotBlank() ||
            normalized.cookies.isNotEmpty() ||
            normalized.authorization.isNotBlank()
    }

    fun isUsable(): Boolean {
        return hasEffectiveAuth()
    }

    fun normalized(savedAt: Long = this.savedAt): YouTubeAuthBundle {
        val normalizedCookies = when {
            cookies.isNotEmpty() -> LinkedHashMap(cookies)
            cookieHeader.isNotBlank() -> parseCookieHeader(cookieHeader)
            else -> linkedMapOf()
        }
        val sanitizedCookies = YouTubeCookieSupport.sanitizePersistedCookies(normalizedCookies)
        val normalizedHeader = if (sanitizedCookies.isEmpty()) {
            ""
        } else {
            sanitizedCookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
        }
        return copy(
            cookieHeader = normalizedHeader,
            cookies = sanitizedCookies,
            origin = origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
            savedAt = savedAt
        )
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("cookieHeader", cookieHeader)
            put(
                "cookies",
                JSONObject().apply {
                    cookies.forEach { (key, value) -> put(key, value) }
                }
            )
            put("authorization", authorization)
            put("xGoogAuthUser", xGoogAuthUser)
            put("origin", origin)
            put("userAgent", userAgent)
            put("savedAt", savedAt)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): YouTubeAuthBundle {
            return runCatching {
                val root = JSONObject(json)
                val cookiesJson = root.optJSONObject("cookies") ?: JSONObject()
                val cookies = linkedMapOf<String, String>()
                val keys = cookiesJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    cookies[key] = cookiesJson.optString(key, "")
                }
                val savedAt = root.optLong("savedAt", 0L)
                YouTubeAuthBundle(
                    cookieHeader = root.optString("cookieHeader", ""),
                    cookies = cookies,
                    authorization = root.optString("authorization", ""),
                    xGoogAuthUser = root.optString("xGoogAuthUser", ""),
                    origin = root.optString("origin", YOUTUBE_MUSIC_ORIGIN),
                    userAgent = root.optString("userAgent", ""),
                    savedAt = savedAt
                ).normalized(savedAt = savedAt)
            }.getOrDefault(YouTubeAuthBundle())
        }
    }
}

enum class YouTubeAuthState {
    Missing,
    Valid
}

data class YouTubeAuthHealth(
    val state: YouTubeAuthState = YouTubeAuthState.Missing,
    val savedAt: Long = 0L,
    val checkedAt: Long = 0L,
    val ageMs: Long = Long.MAX_VALUE,
    val loginCookieKeys: List<String> = emptyList(),
    val activeCookieKeys: List<String> = emptyList()
) {
    val shouldPromptRelogin: Boolean
        get() = false
}

fun evaluateYouTubeAuthHealth(
    bundle: YouTubeAuthBundle,
    now: Long = System.currentTimeMillis()
): YouTubeAuthHealth {
    val normalized = bundle.normalized(savedAt = bundle.savedAt)
    val cookies = normalized.cookies.ifEmpty { parseCookieHeader(normalized.cookieHeader) }
    val loginCookieKeys = YouTubeCookieSupport.collectImportantLoginCookieKeys(cookies)
    val activeCookieKeys = YouTubeCookieSupport.collectActiveSessionCookieKeys(cookies)
    if (loginCookieKeys.isEmpty() && normalized.authorization.isBlank()) {
        return YouTubeAuthHealth(
            state = YouTubeAuthState.Missing,
            savedAt = normalized.savedAt,
            checkedAt = now
        )
    }
    val savedAt = normalized.savedAt
    val ageMs = if (savedAt > 0L) {
        (now - savedAt).coerceAtLeast(0L)
    } else {
        Long.MAX_VALUE
    }
    return YouTubeAuthHealth(
        state = YouTubeAuthState.Valid,
        savedAt = savedAt,
        checkedAt = now,
        ageMs = ageMs,
        loginCookieKeys = loginCookieKeys,
        activeCookieKeys = activeCookieKeys
    )
}

fun parseCookieHeader(raw: String): LinkedHashMap<String, String> {
    val result = linkedMapOf<String, String>()
    raw.split(';')
        .map(String::trim)
        .filter { it.isNotBlank() && it.contains('=') }
        .forEach { segment ->
            val delimiterIndex = segment.indexOf('=')
            if (delimiterIndex <= 0) {
                return@forEach
            }
            val key = segment.substring(0, delimiterIndex).trim()
            val value = segment.substring(delimiterIndex + 1).trim()
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
    return result
}
