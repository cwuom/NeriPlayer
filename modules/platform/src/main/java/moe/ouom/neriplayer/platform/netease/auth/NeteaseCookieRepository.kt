@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.platform.netease.auth

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
 *
 * File: moe.ouom.neriplayer.platform.netease.auth/NeteaseCookieRepository
 * Created: 2025/8/9
 */

import moe.ouom.neriplayer.data.model.netease.auth.NETEASE_LOGIN_COOKIE_KEYS
import moe.ouom.neriplayer.data.model.netease.auth.NeteaseAuthBundle
import moe.ouom.neriplayer.data.model.netease.auth.NeteaseCookieValidationResult
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener
import moe.ouom.neriplayer.common.storage.VolatileSharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.common.logging.NPLogger
import org.json.JSONObject

private const val NETEASE_AUTH_PREFS = "netease_auth_secure_prefs"
private const val KEY_NETEASE_AUTH_BUNDLE = "netease_auth_bundle"
private const val NETEASE_COOKIE_FALLBACK_OS = "pc"
private const val NETEASE_COOKIE_FALLBACK_APPVER = "8.10.35"

private val Context.cookieDataStore by preferencesDataStore("auth_store")

object CookieKeys {
    val NETEASE_COOKIE_JSON = stringPreferencesKey("netease_cookie_json")
}

private val NETEASE_COOKIE_NAME_REGEX = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

fun validateAndSanitizeNeteaseCookies(
    cookies: Map<String, String>,
    includeFallbackCookies: Boolean = true
): NeteaseCookieValidationResult {
    val sanitized = linkedMapOf<String, String>()
    val rejected = linkedSetOf<String>()

    cookies.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        val rejectedKey = key.ifBlank { "<blank>" }
        when {
            key.isBlank() -> rejected += rejectedKey
            !NETEASE_COOKIE_NAME_REGEX.matches(key) -> rejected += rejectedKey
            value.isBlank() -> rejected += rejectedKey
            value.any { it.isISOControl() } -> rejected += rejectedKey
            ';' in value -> rejected += rejectedKey
            else -> sanitized[key] = value
        }
    }

    if (includeFallbackCookies && sanitized.isNotEmpty()) {
        sanitized.putIfAbsent("os", NETEASE_COOKIE_FALLBACK_OS)
        sanitized.putIfAbsent("appver", NETEASE_COOKIE_FALLBACK_APPVER)
    }

    return NeteaseCookieValidationResult(
        sanitizedCookies = sanitized,
        rejectedKeys = rejected.toList()
    )
}

fun evaluateNeteaseAuthHealth(
    bundle: NeteaseAuthBundle,
    now: Long = System.currentTimeMillis()
): SavedCookieAuthHealth {
    val normalized = bundle.normalized(savedAt = bundle.savedAt)
    val loginCookieKeys = NETEASE_LOGIN_COOKIE_KEYS.filter { key ->
        !normalized.cookies[key].isNullOrBlank()
    }
    if (loginCookieKeys.isEmpty()) {
        return SavedCookieAuthHealth(
            state = SavedCookieAuthState.Missing,
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
    return SavedCookieAuthHealth(
        state = SavedCookieAuthState.Valid,
        savedAt = savedAt,
        checkedAt = now,
        ageMs = ageMs,
        loginCookieKeys = loginCookieKeys
    )
}

class NeteaseCookieRepository(private val context: Context) {
    private val mutationLock = Any()
    private var encryptedPrefs: SharedPreferences
    private val _authFlow: MutableStateFlow<NeteaseAuthBundle>
    private val _cookieFlow: MutableStateFlow<Map<String, String>>
    private val _authHealthFlow: MutableStateFlow<SavedCookieAuthHealth>

    val cookieFlow: StateFlow<Map<String, String>>
        get() = _cookieFlow.asStateFlow()

    val authHealthFlow: StateFlow<SavedCookieAuthHealth>
        get() = _authHealthFlow.asStateFlow()

    init {
        encryptedPrefs = openEncryptedPrefsWithRecovery()
        val initialBundle = loadAuthBundle()
        _authFlow = MutableStateFlow(initialBundle)
        _cookieFlow = MutableStateFlow(initialBundle.cookies)
        _authHealthFlow = MutableStateFlow(
            evaluateNeteaseAuthHealth(initialBundle)
        )
    }

    fun getCookiesOnce(): Map<String, String> = _cookieFlow.value

    fun <T> withCurrentCookies(action: (Map<String, String>) -> T): T {
        return synchronized(mutationLock) {
            action(_cookieFlow.value)
        }
    }

    fun withCurrentCookiesIfMatches(
        expectedCookies: Map<String, String>,
        action: (Map<String, String>) -> Unit
    ): Boolean {
        return synchronized(mutationLock) {
            if (_cookieFlow.value != expectedCookies) return@synchronized false
            action(_cookieFlow.value)
            true
        }
    }

    fun getAuthHealthOnce(): SavedCookieAuthHealth = _authHealthFlow.value

    fun getAuthHealth(
        now: Long = System.currentTimeMillis()
    ): SavedCookieAuthHealth = evaluateNeteaseAuthHealth(_authFlow.value, now)

    fun validateCookies(cookies: Map<String, String>): NeteaseCookieValidationResult {
        return validateAndSanitizeNeteaseCookies(cookies)
    }

    fun saveCookies(
        cookies: Map<String, String>,
        savedAt: Long = System.currentTimeMillis()
    ): Boolean {
        return synchronized(mutationLock) {
            saveCookiesLocked(cookies, savedAt)
        }
    }

    /** only applies a session update when the account snapshot is still current */
    fun saveCookiesIfCurrent(
        expectedCookies: Map<String, String>,
        cookies: Map<String, String>,
        savedAt: Long = System.currentTimeMillis()
    ): Boolean {
        return synchronized(mutationLock) {
            if (_cookieFlow.value != expectedCookies) return@synchronized false
            saveCookiesLocked(cookies, savedAt)
        }
    }

    private fun saveCookiesLocked(
        cookies: Map<String, String>,
        savedAt: Long
    ): Boolean {
        val validation = validateCookies(cookies)
        if (!validation.isAccepted) {
            NPLogger.w(
                "NERI-CookieRepo",
                "Rejected invalid NetEase cookies. rejectedKeys=${validation.rejectedKeys.joinToString()}"
            )
            return false
        }
        val normalized = NeteaseAuthBundle(
            cookies = validation.sanitizedCookies,
            savedAt = savedAt
        ).normalized(savedAt = savedAt)
        writeAuthBundle(normalized)
        _authFlow.value = normalized
        _cookieFlow.value = normalized.cookies
        _authHealthFlow.value = evaluateNeteaseAuthHealth(normalized)
        NPLogger.d(
            "NERI-CookieRepo",
            "Saved cookies to secure storage: keys=${normalized.cookies.keys.joinToString()}"
        )
        return true
    }

    fun clear() {
        synchronized(mutationLock) {
            writeAuthBundle(null)
            val cleared = NeteaseAuthBundle()
            _authFlow.value = cleared
            _cookieFlow.value = cleared.cookies
            _authHealthFlow.value = evaluateNeteaseAuthHealth(cleared)
            NPLogger.d("NERI-CookieRepo", "Cleared all saved cookies.")
        }
    }

    fun refreshHealth(now: Long = System.currentTimeMillis()) {
        _authHealthFlow.value = evaluateNeteaseAuthHealth(
            bundle = _authFlow.value,
            now = now
        )
    }

    private fun loadAuthBundle(): NeteaseAuthBundle {
        val raw = runCatching {
            encryptedPrefs.getString(KEY_NETEASE_AUTH_BUNDLE, null).orEmpty()
        }.getOrElse { error ->
            NPLogger.w(
                "NERI-CookieRepo",
                "Failed to read NetEase secure prefs, recovering storage.",
                error
            )
            recoverUnreadableStorage(error)
            ""
        }
        if (raw.isNotBlank()) {
            return NeteaseAuthBundle.fromJson(raw)
        }

        return migrateLegacyCookies() ?: NeteaseAuthBundle()
    }

    private fun loadLegacyCookies(): Map<String, String> {
        return runCatching {
            val prefs = runBlocking { context.cookieDataStore.data.first() }
            val json = prefs[CookieKeys.NETEASE_COOKIE_JSON] ?: "{}"
            jsonToMap(json)
        }.getOrDefault(emptyMap())
    }

    private fun migrateLegacyCookies(): NeteaseAuthBundle? {
        val legacyCookies = validateAndSanitizeNeteaseCookies(loadLegacyCookies()).sanitizedCookies
        if (legacyCookies.isEmpty()) {
            return null
        }

        val migrated = NeteaseAuthBundle(
            cookies = legacyCookies,
            savedAt = 0L
        ).normalized(savedAt = 0L)
        // 加密存储不可写时保留旧 Cookie，下次启动再迁移
        if (!writeAuthBundle(migrated) || !SecurePreferencesOpener.isPersistent(encryptedPrefs)) {
            return migrated
        }
        runCatching {
            runBlocking {
                context.cookieDataStore.edit { prefs ->
                    prefs.remove(CookieKeys.NETEASE_COOKIE_JSON)
                }
            }
        }
        return migrated
    }

    private fun jsonToMap(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val result = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = obj.optString(key, "")
        }
        return result
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences = SecurePreferencesOpener.open(
        name = NETEASE_AUTH_PREFS,
        create = { createEncryptedPrefs() },
        delete = { clearEncryptedStorage() },
        report = { event, error -> NPLogger.w("NERI-CookieRepo", "NetEase secure prefs $event", error) }
    )

    /** Keystore 不可用时删除文件也读不回凭据，只有数据损坏才删除重建 */
    private fun recoverUnreadableStorage(error: Throwable) {
        encryptedPrefs = if (SecurePreferencesOpener.isKeystoreUnavailable(error)) {
            VolatileSharedPreferences.shared(NETEASE_AUTH_PREFS)
        } else {
            clearEncryptedStorage()
            openEncryptedPrefsWithRecovery()
        }
    }

    private fun writeAuthBundle(bundle: NeteaseAuthBundle?): Boolean = runCatching {
        encryptedPrefs.edit {
            if (bundle == null) remove(KEY_NETEASE_AUTH_BUNDLE)
            else putString(KEY_NETEASE_AUTH_BUNDLE, bundle.toJson())
        }
    }.onFailure { error ->
        NPLogger.w("NERI-CookieRepo", "Failed to write NetEase secure prefs", error)
    }.isSuccess

    private fun clearEncryptedStorage() {
        runCatching {
            context.deleteSharedPreferences(NETEASE_AUTH_PREFS)
        }.onFailure { error ->
            NPLogger.w(
                "NERI-CookieRepo",
                "Failed to delete corrupted NetEase secure prefs file.",
                error
            )
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            NETEASE_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
