@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.auth.kugou

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
 * File: moe.ouom.neriplayer.data.auth.kugou/KugouCookieRepository
 * Created: 2025/07/05
 */

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject

private const val TAG = "KugouCookieRepo"
private const val KUGOU_AUTH_PREFS = "kugou_auth_secure_prefs"
private const val KEY_KUGOU_AUTH_BUNDLE = "kugou_auth_bundle"

private val KUGOU_LOGIN_ESSENTIAL_KEYS = listOf("token", "userid")

data class KugouAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    fun hasLoginCookies(): Boolean {
        return KUGOU_LOGIN_ESSENTIAL_KEYS.all { key -> !cookies[key].isNullOrBlank() }
    }

    fun toJson(): String {
        return JSONObject().apply {
            put(
                "cookies",
                JSONObject().apply {
                    cookies.forEach { (key, value) -> put(key, value) }
                }
            )
            put("savedAt", savedAt)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): KugouAuthBundle {
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
                KugouAuthBundle(
                    cookies = cookies,
                    savedAt = savedAt
                )
            }.getOrDefault(KugouAuthBundle())
        }
    }
}

internal fun evaluateKugouAuthHealth(
    bundle: KugouAuthBundle,
    now: Long = System.currentTimeMillis()
): SavedCookieAuthHealth {
    if (!bundle.hasLoginCookies()) {
        return SavedCookieAuthHealth(
            state = SavedCookieAuthState.Missing,
            savedAt = bundle.savedAt,
            checkedAt = now
        )
    }

    val ageMs = if (bundle.savedAt > 0L) {
        (now - bundle.savedAt).coerceAtLeast(0L)
    } else {
        Long.MAX_VALUE
    }
    return SavedCookieAuthHealth(
        state = SavedCookieAuthState.Valid,
        savedAt = bundle.savedAt,
        checkedAt = now,
        ageMs = ageMs,
        loginCookieKeys = KUGOU_LOGIN_ESSENTIAL_KEYS.filter { bundle.cookies.containsKey(it) }
    )
}

/**
 * Thread-safe repository for persisting and observing Kugou authentication cookies.
 *
 * All public methods are safe to call from any thread.
 */
class KugouCookieRepository(private val context: Context) {
    private val lock = Any()
    private var encryptedPrefs: SharedPreferences
    private val _authFlow: MutableStateFlow<KugouAuthBundle>
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
            evaluateKugouAuthHealth(initialBundle)
        )
    }

    // ────────────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────────────

    /**
     * Returns a snapshot of the currently persisted cookies.
     */
    fun loadCookies(): Map<String, String> = synchronized(lock) {
        _cookieFlow.value
    }

    /**
     * Convenience alias used by [AppContainer.startCookieObserver].
     */
    fun getCookiesOnce(): Map<String, String> = loadCookies()

    /**
     * Replaces the persisted cookie set with [cookies] and notifies observers.
     */
    fun saveCookies(
        cookies: Map<String, String>,
        savedAt: Long = System.currentTimeMillis()
    ): Boolean = synchronized(lock) {
        val bundle = KugouAuthBundle(cookies = cookies, savedAt = savedAt)
        persistBundle(bundle)
        emitBundle(bundle)
        NPLogger.d(TAG, "Saved ${cookies.size} cookies.")
        true
    }

    /**
     * Merges [incoming] into the existing cookie map.
     * - Existing keys are **overwritten** by [incoming].
     * - Keys absent from [incoming] are **preserved**.
     * - Values that are blank are treated as "delete" and removed.
     *
     * The merged result is persisted and pushed to [cookieFlow].
     */
    fun updateCookies(incoming: Map<String, String>) = synchronized(lock) {
        val current = _authFlow.value.cookies.toMutableMap()
        incoming.forEach { (key, value) ->
            if (value.isBlank()) {
                current.remove(key)
            } else {
                current[key] = value
            }
        }
        val bundle = KugouAuthBundle(cookies = current, savedAt = System.currentTimeMillis())
        persistBundle(bundle)
        emitBundle(bundle)
        NPLogger.d(TAG, "Updated cookies: merged=${current.size}, incoming=${incoming.size}.")
    }

    /**
     * Deletes all persisted cookies and resets the auth state.
     */
    fun clear() = synchronized(lock) {
        encryptedPrefs.edit { remove(KEY_KUGOU_AUTH_BUNDLE) }
        val cleared = KugouAuthBundle()
        emitBundle(cleared)
        NPLogger.d(TAG, "Cleared all cookies.")
    }

    fun getAuthHealthOnce(): SavedCookieAuthHealth = _authHealthFlow.value

    fun refreshHealth(now: Long = System.currentTimeMillis()) {
        _authHealthFlow.value = evaluateKugouAuthHealth(_authFlow.value, now)
    }

    // ────────────────────────────────────────────────────────────────
    //  Internal persistence
    // ────────────────────────────────────────────────────────────────

    private fun persistBundle(bundle: KugouAuthBundle) {
        encryptedPrefs.edit {
            putString(KEY_KUGOU_AUTH_BUNDLE, bundle.toJson())
        }
    }

    private fun emitBundle(bundle: KugouAuthBundle) {
        _authFlow.value = bundle
        _cookieFlow.value = bundle.cookies
        _authHealthFlow.value = evaluateKugouAuthHealth(bundle)
    }

    private fun loadAuthBundle(): KugouAuthBundle {
        val raw = encryptedPrefs.getString(KEY_KUGOU_AUTH_BUNDLE, null).orEmpty()
        return if (raw.isNotBlank()) {
            KugouAuthBundle.fromJson(raw)
        } else {
            KugouAuthBundle()
        }
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences {
        return runCatching {
            createEncryptedPrefs()
        }.getOrElse {
            NPLogger.w(TAG, "Encrypted prefs corrupted, recreating.")
            context.deleteSharedPreferences(KUGOU_AUTH_PREFS)
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            KUGOU_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
