@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.youtube.auth


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

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.api.youtube.auth.YouTubeAuthProvider
import moe.ouom.neriplayer.api.youtube.model.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.api.youtube.model.auth.YouTubeAuthHealth
import moe.ouom.neriplayer.api.youtube.model.auth.evaluateYouTubeAuthHealth
import moe.ouom.neriplayer.core.logging.NPLogger

private const val YOUTUBE_AUTH_PREFS = "youtube_auth_secure_prefs"
private const val YOUTUBE_AUTH_RECOVERY_PREFS = "youtube_auth_secure_prefs_recovery"
private const val YOUTUBE_AUTH_RECOVERY_MASTER_KEY_ALIAS =
    "youtube_auth_recovery_master_key"
private const val KEY_YOUTUBE_AUTH_BUNDLE = "youtube_auth_bundle"

class YouTubeAuthRepository(private val context: Context) : YouTubeAuthProvider {
    private var encryptedPrefs: SharedPreferences
    private var usingRecoveryStorage = false
    private val _authFlow: MutableStateFlow<YouTubeAuthBundle>
    private val _authHealthFlow: MutableStateFlow<YouTubeAuthHealth>
    private val authMutationLock = Any()

    val authFlow: StateFlow<YouTubeAuthBundle>
        get() = _authFlow.asStateFlow()

    val authHealthFlow: StateFlow<YouTubeAuthHealth>
        get() = _authHealthFlow.asStateFlow()

    init {
        encryptedPrefs = openEncryptedPrefsWithRecovery()
        val initialBundle = loadAuthBundle()
        _authFlow = MutableStateFlow(initialBundle)
        _authHealthFlow = MutableStateFlow(
            evaluateYouTubeAuthHealth(initialBundle)
        )
    }

    override fun getAuthOnce(): YouTubeAuthBundle = _authFlow.value

    override fun getAuthHealthOnce(): YouTubeAuthHealth = _authHealthFlow.value

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
                runCatching { createEncryptedPrefs(YOUTUBE_AUTH_PREFS) }
                    .onSuccess { clearStoredAuth(it, "primary") }
                    .onFailure { error ->
                        NPLogger.w(
                            "NERI-YouTubeAuthRepo",
                            "Failed to clear primary YouTube secure prefs.",
                            error
                        )
                    }
            } else {
                runCatching { createRecoveryEncryptedPrefs() }
                    .onSuccess { clearStoredAuth(it, "recovery") }
                    .onFailure { error ->
                        NPLogger.w(
                            "NERI-YouTubeAuthRepo",
                            "Failed to clear recovery YouTube secure prefs.",
                            error
                        )
                    }
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
        val primaryRead = runCatching {
            encryptedPrefs.getString(KEY_YOUTUBE_AUTH_BUNDLE, null)
        }
        if (primaryRead.isFailure && !usingRecoveryStorage) {
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to read primary YouTube secure prefs, preserving it and switching to recovery storage.",
                primaryRead.exceptionOrNull()
            )
            if (switchToRecoveryStorage()) {
                return readRecoveryAuthBundle()
            }
        }

        val raw = primaryRead.getOrNull().orEmpty()
        if (raw.isBlank() && !usingRecoveryStorage) {
            // 旧的恢复存储可能是在一次加密异常后写入的, 优先取它以免恢复成功后又显示游客态
            runCatching { createRecoveryEncryptedPrefs() }
                .onSuccess { recoveryPrefs ->
                    val recoveryRaw = runCatching {
                        recoveryPrefs.getString(KEY_YOUTUBE_AUTH_BUNDLE, null).orEmpty()
                    }.getOrDefault("")
                    if (recoveryRaw.isNotBlank()) {
                        encryptedPrefs = recoveryPrefs
                        usingRecoveryStorage = true
                    }
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
        val raw = runCatching {
            encryptedPrefs.getString(KEY_YOUTUBE_AUTH_BUNDLE, null).orEmpty()
        }.onFailure { error ->
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to read YouTube recovery secure prefs.",
                error
            )
        }.getOrDefault("")
        return raw.takeIf(String::isNotBlank)?.let(YouTubeAuthBundle::fromJson)
            ?: YouTubeAuthBundle()
    }

    private fun switchToRecoveryStorage(): Boolean {
        val recoveryPrefs = runCatching { createRecoveryEncryptedPrefs() }
            .onFailure { error ->
                NPLogger.e(
                    "NERI-YouTubeAuthRepo",
                    "Failed to open YouTube recovery secure prefs; original storage was preserved.",
                    error
                )
            }
            .getOrNull()
            ?: return false
        encryptedPrefs = recoveryPrefs
        usingRecoveryStorage = true
        return true
    }

    private fun persistAuthBundle(bundle: YouTubeAuthBundle) {
        val serialized = bundle.toJson()
        val primaryWrite = runCatching {
            commitAuthBundle(encryptedPrefs, serialized)
        }
        if (primaryWrite.getOrNull() != true) {
            if (usingRecoveryStorage) {
                throw primaryWrite.exceptionOrNull()
                    ?: IOException("Failed to commit YouTube recovery secure prefs")
            }
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to commit primary YouTube secure prefs, preserving it and switching to recovery storage.",
                primaryWrite.exceptionOrNull()
            )
            if (!switchToRecoveryStorage()) {
                throw primaryWrite.exceptionOrNull()
                    ?: IOException("Failed to commit YouTube primary secure prefs")
            }
            if (!commitAuthBundle(encryptedPrefs, serialized)) {
                throw IOException("Failed to commit YouTube recovery secure prefs")
            }
            return
        }

        // 主存储正常时也保留独立加密副本, 主文件损坏后仍能恢复最近一次登录
        runCatching {
            val recoveryPrefs = createRecoveryEncryptedPrefs()
            if (!commitAuthBundle(recoveryPrefs, serialized)) {
                NPLogger.w(
                    "NERI-YouTubeAuthRepo",
                    "Failed to commit YouTube recovery backup; primary auth remains available"
                )
            }
        }.onFailure { error ->
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to update YouTube recovery backup; primary auth remains available.",
                error
            )
        }
    }

    private fun commitAuthBundle(
        prefs: SharedPreferences,
        serialized: String
    ): Boolean = prefs.commitEdit {
        putString(KEY_YOUTUBE_AUTH_BUNDLE, serialized)
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences {
        return runCatching {
            createEncryptedPrefs(YOUTUBE_AUTH_PREFS)
        }.getOrElse { error ->
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to open primary YouTube secure prefs, preserving it and using recovery storage.",
                error
            )
            switchToRecoveryStorageOrThrow()
        }
    }

    private fun switchToRecoveryStorageOrThrow(): SharedPreferences {
        val recoveryPrefs = runCatching { createRecoveryEncryptedPrefs() }
            .onFailure { recoveryError ->
                NPLogger.e(
                    "NERI-YouTubeAuthRepo",
                    "Unable to open either YouTube secure storage; original storage was preserved.",
                    recoveryError
                )
            }
            .getOrElse { recoveryError ->
                throw IllegalStateException(
                    "Unable to open YouTube secure storage without deleting credentials",
                    recoveryError
                )
            }
        usingRecoveryStorage = true
        return recoveryPrefs
    }

    private fun createRecoveryEncryptedPrefs(): SharedPreferences {
        return createEncryptedPrefs(
            name = YOUTUBE_AUTH_RECOVERY_PREFS,
            masterKeyAlias = YOUTUBE_AUTH_RECOVERY_MASTER_KEY_ALIAS
        )
    }

    private fun createEncryptedPrefs(
        name: String,
        masterKeyAlias: String? = null
    ): SharedPreferences {
        val masterKeyBuilder = if (masterKeyAlias.isNullOrBlank()) {
            MasterKey.Builder(context)
        } else {
            MasterKey.Builder(context, masterKeyAlias)
        }
        val masterKey = masterKeyBuilder
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearStoredAuth(prefs: SharedPreferences, label: String) {
        runCatching {
            if (!prefs.commitEdit { remove(KEY_YOUTUBE_AUTH_BUNDLE) }) {
                throw IOException("SharedPreferences commit returned false")
            }
        }.onFailure { error ->
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to clear $label YouTube secure prefs.",
                error
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
