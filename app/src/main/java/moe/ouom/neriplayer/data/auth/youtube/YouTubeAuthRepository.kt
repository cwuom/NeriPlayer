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
import android.annotation.SuppressLint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.security.RecoveringEncryptedSharedPreferences
import org.json.JSONObject

const val YOUTUBE_MUSIC_ORIGIN: String = "https://music.youtube.com"
private const val YOUTUBE_AUTH_PREFS = "youtube_auth_secure_prefs"
private const val YOUTUBE_AUTH_RECOVERY_PREFS = "youtube_auth_secure_prefs_recovery"
private const val YOUTUBE_AUTH_RECOVERY_MASTER_KEY_ALIAS =
    "youtube_auth_recovery_master_key"
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

class YouTubeAuthRepository(private val context: Context) {
    private var encryptedPrefs: RecoveringEncryptedSharedPreferences
    private var usingRecoveryStorage = false
    private val recoveryPrefs by lazy { createRecoveryEncryptedPrefs() }
    private val _authFlow: MutableStateFlow<YouTubeAuthBundle>
    private val _authHealthFlow: MutableStateFlow<YouTubeAuthHealth>
    private val authMutationLock = Any()

    val authFlow: StateFlow<YouTubeAuthBundle>
        get() = _authFlow.asStateFlow()

    val authHealthFlow: StateFlow<YouTubeAuthHealth>
        get() = _authHealthFlow.asStateFlow()

    init {
        encryptedPrefs = createEncryptedPrefs(YOUTUBE_AUTH_PREFS)
        val initialBundle = loadAuthBundle()
        _authFlow = MutableStateFlow(initialBundle)
        _authHealthFlow = MutableStateFlow(
            evaluateYouTubeAuthHealth(initialBundle)
        )
    }

    fun getAuthOnce(): YouTubeAuthBundle = _authFlow.value

    fun getAuthHealthOnce(): YouTubeAuthHealth = _authHealthFlow.value

    fun getAuthHealth(
        now: Long = System.currentTimeMillis()
    ): YouTubeAuthHealth = evaluateYouTubeAuthHealth(_authFlow.value, now)

    fun saveAuth(bundle: YouTubeAuthBundle) {
        synchronized(authMutationLock) {
            saveAuthLocked(bundle)
        }
    }

    /** 在现有快照上合并轮换值, 防止并发页面刷新覆盖其它最新字段 */
    fun mergeRotatedCookies(rotatedCookies: Map<String, String>): YouTubeAuthBundle {
        synchronized(authMutationLock) {
            val merged = mergeYouTubeAuthBundle(
                base = _authFlow.value,
                observedCookies = rotatedCookies,
                savedAt = System.currentTimeMillis()
            )
            saveAuthLocked(merged)
            return merged
        }
    }

    private fun saveAuthLocked(bundle: YouTubeAuthBundle) {
        val normalized = bundle.normalized(
            savedAt = bundle.savedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        persistAuthBundle(normalized)
        _authFlow.value = normalized
        _authHealthFlow.value = evaluateYouTubeAuthHealth(normalized)
    }

    fun mergeCookieUpdates(setCookieHeaders: Iterable<String>): Boolean {
        synchronized(authMutationLock) {
            val merged = mergeYouTubeAuthCookieUpdates(
                base = _authFlow.value,
                setCookieHeaders = setCookieHeaders
            ) ?: return false
            saveAuthLocked(merged)
            return true
        }
    }

    fun clear() {
        synchronized(authMutationLock) {
            clearStoredAuth(encryptedPrefs, "active")
            // 恢复存储可能保留着上一次加密异常时写入的凭据, 显式清除不能留下第二份
            if (usingRecoveryStorage) {
                clearStoredAuth(createEncryptedPrefs(YOUTUBE_AUTH_PREFS), "primary")
            } else {
                clearStoredAuth(createRecoveryEncryptedPrefs(), "recovery")
            }
            val cleared = YouTubeAuthBundle()
            _authFlow.value = cleared
            _authHealthFlow.value = evaluateYouTubeAuthHealth(cleared)
        }
    }

    fun refreshHealth(now: Long = System.currentTimeMillis()) {
        _authHealthFlow.value = evaluateYouTubeAuthHealth(
            bundle = _authFlow.value,
            now = now
        )
    }

    private fun loadAuthBundle(): YouTubeAuthBundle {
        val raw = encryptedPrefs.getString(KEY_YOUTUBE_AUTH_BUNDLE, null).orEmpty()
        if (raw.isBlank() && !usingRecoveryStorage) {
            // 旧的恢复存储可能是在一次加密异常后写入的, 优先取它以免恢复成功后又显示游客态
            val recoveryRaw = recoveryPrefs.getString(KEY_YOUTUBE_AUTH_BUNDLE, null).orEmpty()
            if (recoveryRaw.isNotBlank()) {
                encryptedPrefs = recoveryPrefs
                usingRecoveryStorage = true
            }
        }
        if (usingRecoveryStorage) {
            return readRecoveryAuthBundle()
        }
        if (raw.isBlank()) {
            return YouTubeAuthBundle()
        }
        return YouTubeAuthBundle.fromJson(raw)
    }

    private fun readRecoveryAuthBundle(): YouTubeAuthBundle {
        val raw = encryptedPrefs.getString(KEY_YOUTUBE_AUTH_BUNDLE, null).orEmpty()
        return raw.takeIf(String::isNotBlank)?.let(YouTubeAuthBundle::fromJson)
            ?: YouTubeAuthBundle()
    }

    private fun switchToRecoveryStorage() {
        encryptedPrefs = recoveryPrefs
        usingRecoveryStorage = true
    }

    private fun persistAuthBundle(bundle: YouTubeAuthBundle) {
        val serialized = bundle.toJson()
        if (!usingRecoveryStorage) {
            val committed = commitAuthBundle(encryptedPrefs, serialized)
            if (committed && encryptedPrefs.isDurable) {
                persistRecoveryBackup(serialized)
                return
            }
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Primary YouTube secure prefs are unavailable; using recovery storage."
            )
            switchToRecoveryStorage()
        }
        val committed = commitAuthBundle(encryptedPrefs, serialized)
        if (!committed) {
            NPLogger.w("NERI-YouTubeAuthRepo", "Failed to persist YouTube auth bundle")
        }
        if (!encryptedPrefs.isDurable) {
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "YouTube credentials are memory-only until secure storage becomes available."
            )
        }
    }

    private fun persistRecoveryBackup(serialized: String) {
        // 主存储正常时也保留独立加密副本, 主文件损坏后仍能恢复最近一次登录
        val activeRecoveryPrefs = recoveryPrefs
        if (!commitAuthBundle(activeRecoveryPrefs, serialized)) {
            NPLogger.w("NERI-YouTubeAuthRepo", "Failed to persist YouTube recovery backup")
        }
        if (!activeRecoveryPrefs.isDurable) {
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "YouTube recovery backup is memory-only; primary auth remains available."
            )
        }
    }

    private fun commitAuthBundle(
        prefs: SharedPreferences,
        serialized: String
    ): Boolean = prefs.commitEdit {
        putString(KEY_YOUTUBE_AUTH_BUNDLE, serialized)
    }

    private fun createRecoveryEncryptedPrefs(): RecoveringEncryptedSharedPreferences {
        return createEncryptedPrefs(
            name = YOUTUBE_AUTH_RECOVERY_PREFS,
            masterKeyAlias = YOUTUBE_AUTH_RECOVERY_MASTER_KEY_ALIAS
        )
    }

    private fun createEncryptedPrefs(
        name: String,
        masterKeyAlias: String? = null
    ): RecoveringEncryptedSharedPreferences {
        return RecoveringEncryptedSharedPreferences(
            context = context,
            storageName = name,
            logTag = "NERI-YouTubeAuthRepo",
            masterKeyAlias = masterKeyAlias
        )
    }

    private fun clearStoredAuth(prefs: SharedPreferences, label: String) {
        if (!prefs.commitEdit { remove(KEY_YOUTUBE_AUTH_BUNDLE) }) {
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to clear $label YouTube secure prefs."
            )
        }
    }
}

@SuppressLint("UseKtx")
private inline fun SharedPreferences.commitEdit(
    action: SharedPreferences.Editor.() -> Unit
): Boolean {
    // androidx edit(commit = true) does not expose commit failure
    val editor = edit()
    editor.action()
    return editor.commit()
}
