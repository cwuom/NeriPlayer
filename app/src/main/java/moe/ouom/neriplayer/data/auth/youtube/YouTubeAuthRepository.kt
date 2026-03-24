@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.auth.youtube

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 */

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

const val YOUTUBE_MUSIC_ORIGIN: String = "https://music.youtube.com"
internal const val YOUTUBE_AUTH_STALE_AFTER_MS: Long = 30L * 24L * 60L * 60L * 1000L

private const val YOUTUBE_AUTH_PREFS = "youtube_auth_secure_prefs"
private const val KEY_YOUTUBE_AUTH_BUNDLE = "youtube_auth_bundle"

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

    fun isUsable(): Boolean {
        if (authorization.isNotBlank()) {
            return true
        }
        return hasLoginCookies()
    }

    fun normalized(savedAt: Long = this.savedAt): YouTubeAuthBundle {
        val normalizedCookies = when {
            cookies.isNotEmpty() -> LinkedHashMap(cookies)
            cookieHeader.isNotBlank() -> parseCookieHeader(cookieHeader)
            else -> linkedMapOf()
        }
        val normalizedHeader = when {
            cookieHeader.isNotBlank() -> cookieHeader.trim()
            normalizedCookies.isNotEmpty() -> normalizedCookies.entries.joinToString("; ") { (key, value) ->
                "$key=$value"
            }
            else -> ""
        }
        return copy(
            cookieHeader = normalizedHeader,
            cookies = normalizedCookies,
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
    Expired,
    Valid,
    Stale
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
        get() = state == YouTubeAuthState.Expired || state == YouTubeAuthState.Stale
}

fun evaluateYouTubeAuthHealth(
    bundle: YouTubeAuthBundle,
    now: Long = System.currentTimeMillis(),
    staleAfterMs: Long = YOUTUBE_AUTH_STALE_AFTER_MS
): YouTubeAuthHealth {
    val normalized = bundle.normalized(savedAt = bundle.savedAt)
    val cookies = normalized.cookies.ifEmpty { parseCookieHeader(normalized.cookieHeader) }
    val loginCookieKeys = YouTubeCookieSupport.importantLoginCookieKeys.filter { key ->
        !cookies[key].isNullOrBlank()
    }
    val activeCookieKeys = YouTubeCookieSupport.activeSessionCookieKeys.filter { key ->
        !cookies[key].isNullOrBlank()
    }
    if (loginCookieKeys.isEmpty()) {
        return YouTubeAuthHealth(
            state = YouTubeAuthState.Missing,
            savedAt = normalized.savedAt,
            checkedAt = now
        )
    }

    if (activeCookieKeys.isEmpty()) {
        return YouTubeAuthHealth(
            state = YouTubeAuthState.Expired,
            savedAt = normalized.savedAt,
            checkedAt = now,
            loginCookieKeys = loginCookieKeys
        )
    }

    val savedAt = normalized.savedAt
    if (savedAt <= 0L) {
        return YouTubeAuthHealth(
            state = YouTubeAuthState.Stale,
            savedAt = savedAt,
            checkedAt = now,
            loginCookieKeys = loginCookieKeys,
            activeCookieKeys = activeCookieKeys
        )
    }

    val ageMs = (now - savedAt).coerceAtLeast(0L)
    return if (ageMs >= staleAfterMs) {
        YouTubeAuthHealth(
            state = YouTubeAuthState.Stale,
            savedAt = savedAt,
            checkedAt = now,
            ageMs = ageMs,
            loginCookieKeys = loginCookieKeys,
            activeCookieKeys = activeCookieKeys
        )
    } else {
        YouTubeAuthHealth(
            state = YouTubeAuthState.Valid,
            savedAt = savedAt,
            checkedAt = now,
            ageMs = ageMs,
            loginCookieKeys = loginCookieKeys,
            activeCookieKeys = activeCookieKeys
        )
    }
}

internal fun parseCookieHeader(raw: String): LinkedHashMap<String, String> {
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

class YouTubeAuthRepository(context: Context) {
    private val encryptedPrefs: SharedPreferences
    private val _authFlow: MutableStateFlow<YouTubeAuthBundle>
    private val _authHealthFlow: MutableStateFlow<YouTubeAuthHealth>

    val authFlow: StateFlow<YouTubeAuthBundle>
        get() = _authFlow.asStateFlow()

    val authHealthFlow: StateFlow<YouTubeAuthHealth>
        get() = _authHealthFlow.asStateFlow()

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            YOUTUBE_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val initialBundle = loadAuthBundle()
        _authFlow = MutableStateFlow(initialBundle)
        _authHealthFlow = MutableStateFlow(
            evaluateYouTubeAuthHealth(initialBundle)
        )
    }

    fun getAuthOnce(): YouTubeAuthBundle = _authFlow.value

    fun getAuthHealthOnce(): YouTubeAuthHealth = _authHealthFlow.value

    fun getAuthHealth(
        now: Long = System.currentTimeMillis(),
        staleAfterMs: Long = YOUTUBE_AUTH_STALE_AFTER_MS
    ): YouTubeAuthHealth = evaluateYouTubeAuthHealth(_authFlow.value, now, staleAfterMs)

    fun saveAuth(bundle: YouTubeAuthBundle) {
        val normalized = bundle.normalized(
            savedAt = bundle.savedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        encryptedPrefs.edit {
            putString(KEY_YOUTUBE_AUTH_BUNDLE, normalized.toJson())
        }
        _authFlow.value = normalized
        _authHealthFlow.value = evaluateYouTubeAuthHealth(normalized)
    }

    fun mergeCookieUpdates(setCookieHeaders: Iterable<String>): Boolean {
        val merged = mergeYouTubeAuthCookieUpdates(
            base = _authFlow.value,
            setCookieHeaders = setCookieHeaders
        ) ?: return false
        saveAuth(merged)
        return true
    }

    fun clear() {
        encryptedPrefs.edit {
            remove(KEY_YOUTUBE_AUTH_BUNDLE)
        }
        val cleared = YouTubeAuthBundle()
        _authFlow.value = cleared
        _authHealthFlow.value = evaluateYouTubeAuthHealth(cleared)
    }

    fun refreshHealth(
        now: Long = System.currentTimeMillis(),
        staleAfterMs: Long = YOUTUBE_AUTH_STALE_AFTER_MS
    ) {
        _authHealthFlow.value = evaluateYouTubeAuthHealth(
            bundle = _authFlow.value,
            now = now,
            staleAfterMs = staleAfterMs
        )
    }

    private fun loadAuthBundle(): YouTubeAuthBundle {
        val raw = encryptedPrefs.getString(KEY_YOUTUBE_AUTH_BUNDLE, null).orEmpty()
        if (raw.isBlank()) {
            return YouTubeAuthBundle()
        }
        return YouTubeAuthBundle.fromJson(raw)
    }
}
